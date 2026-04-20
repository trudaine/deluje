/*
  Deluge Emulator — Production Engine v1.13 (Production + Logging + Silence Fixed)
  Concurrent shreds: clock, kit, synth, fx_bus, sidechain, master, midi.
*/

// ── global bridge variables ────────────────────────────────────────────────
global float g_bpm; global float g_swing; global float g_master_vol; global float g_master_pan;
global int   g_play; global int g_current_step; global int g_scale; global int g_root_key;
global float g_delay_time; global float g_delay_fb; global float g_reverb_room; global float g_reverb_damp;
global int   g_stutter_on; global int g_record_on;
global float g_stutter_div;

// Advanced DSP
global float g_fm_ratio[]; global float g_fm_amount[]; 
global float g_sidechain_amount; global float g_master_comp;
global Gain g_delay_in; global Gain g_reverb_in; global Gain g_mod_in; global Gain g_synth_bus;
global Event tick_event; global Event sidechain_event;

// MIDI Events
global Event midi_note_on; global Event midi_note_off;
global int g_midi_note; global int g_midi_vel;

// Arpeggiator
global int   g_arp_on[];
global int   g_arp_mode[];
global float g_arp_rate[];
global int   g_arp_octave[];

// ── helpers ──────────────────────────────────────────────────────────────
fun dur stepDuration(int step) {
    float s, b;
    if (g_bpm < 1.0) 120.0 => g_bpm;
    Math.max(0.0, Math.min(1.0, g_swing)) => s;
    60.0 / g_bpm / 4.0 => b;
    if (step % 2 == 0) return b * (1.0 + (s - 0.5) * 0.4) * second;
    else return b * (1.0 - (s - 0.5) * 0.4) * second;
}

fun void monitor_sample_end(SndBuf b, int e) {
    if (b == null) return;
    while (b.pos() < e && b.rate() > 0) 5::ms => now;
    if (b == null) return;
    b.rate(0); b.pos(b.samples());
}

// ── kit shred ─────────────────────────────────────────────────────────────
fun void kit_shred() {
    SndBuf kit[4]; Pan2 pan[4]; Gain d_send[4], r_send[4];
    Gain master; HPF hpf; Dyno limit; DelugeAdsr gate;
    master => hpf => limit => gate => dac;
    hpf.freq(20); limit.limit();
    100::ms => now; gate.forceMute(); gate.set(0.001, 0, 1, 0.001);

    "examples/data/kick.wav" => kit[0].read; "examples/data/snare.wav" => kit[1].read;
    "examples/data/hihat.wav" => kit[2].read; "examples/data/hihat-open.wav" => kit[3].read;

    for (0 => int i; i < 4; i++) {
        kit[i].rate(0); kit[i].pos(kit[i].samples());
        kit[i] => pan[i] => master;
        pan[i] => d_send[i] => g_delay_in; pan[i] => r_send[i] => g_reverb_in;
    }

    int pat[], mute[], r, idx; long last_step; -1 => last_step;

    while (true) {
        tick_event => now;
        if (g_play == 0) { -1 => last_step; gate.keyOff(); continue; }
        g_current_step => int current_step;
        if (current_step == last_step) continue;
        current_step => last_step;

        Machine.getGlobalObject("g_pattern") $ int[] @=> pat;
        Machine.getGlobalObject("g_velocity") $ float[] @=> vel;
        Machine.getGlobalObject("g_mute") $ int[] @=> mute;
        Machine.getGlobalObject("g_track_level") $ float[] @=> trk_lvl;
        Machine.getGlobalObject("g_step_start") $ float[] @=> s_start;
        Machine.getGlobalObject("g_step_end") $ float[] @=> s_end;
        if (pat == null || trk_lvl == null) continue;
        
        g_master_vol => master.gain;
        current_step % 16 => int step;
        for (0 => r; r < 4; r++) {
            if (mute[r] != 0) continue;
            r * 16 + step => idx;
            if (pat[idx] == 0) continue;
            if (r == 0) sidechain_event.broadcast();
            kit[r].rate(1); kit[r].pos((s_start[idx] * kit[r].samples()) $ int);
            vel[idx] * trk_lvl[r] * 0.8 => kit[r].gain;
            spork ~ monitor_sample_end(kit[r], (s_end[idx] * kit[r].samples()) $ int);
            gate.keyOn();

            if (Machine.loglevel() >= 2) {
                <<< "KIT trigger track:", r, "step:", step, "vel:", vel[idx] >>>;
            }
        }
    }
}

// ── synth shred (8 Voice Polyphony + Arp + Per-track FM) ──────────────────
fun void synth_shred() {
    MorphingWavetable car[8], mod[8]; SVFilter fil[8]; DelugeAdsr env[8]; Pan2 pan[8];
    for (0 => int i; i < 8; i++) {
        mod[i] => car[i]; 
        car[i] => fil[i] => env[i] => pan[i] => g_synth_bus;
        fil[i].reset(); fil[i].freq(5000); env[i].set(0.05, 0.2, 0.5, 0.3);
        env[i].forceMute(); 
    }

    int pat[], pitch[], mute[], idx, step, is_new_step; float tf, tq, tp, f;
    long last_step; -1 => last_step;

    while (true) {
        tick_event => now;
        if (g_play == 0) { -1 => last_step; for(0=>int i; i<8; i++) env[i].keyOff(); continue; }
        g_current_step => int current_step;
        (current_step != last_step) => is_new_step;
        current_step => last_step;

        Machine.getGlobalObject("g_pattern") $ int[] @=> pat;
        Machine.getGlobalObject("g_velocity") $ float[] @=> vel;
        Machine.getGlobalObject("g_pitch") $ int[] @=> pitch;
        Machine.getGlobalObject("g_mute") $ int[] @=> mute;
        Machine.getGlobalObject("g_track_level") $ float[] @=> trk_lvl;
        Machine.getGlobalObject("g_filter") $ float[] @=> g_fil;
        Machine.getGlobalObject("g_step_filter") $ float[] @=> s_fil;
        Machine.getGlobalObject("g_step_res") $ float[] @=> s_res;
        Machine.getGlobalObject("g_step_pan") $ float[] @=> s_pan;
        if (pat == null || g_fil == null) continue;

        current_step % 16 => step;
        for (4 => int r; r < 8; r++) {
            r * 16 + step => idx;
            (g_fil[r * 2] + s_fil[idx]) * 10000.0 + 100.0 => tf;
            (g_fil[r * 2 + 1] + s_res[idx]) * 4.0 + 1.0 => tq;
            g_master_pan + s_pan[idx] => tp;
            fil[r].freq(tf); fil[r].Q(tq); pan[r].pan(tp);

            if (mute[r] != 0) { env[r].keyOff(); continue; }
            if (pat[idx] == 0) { env[r].keyOff(); continue; }

            if (is_new_step != 0) {
                if (g_arp_on[r] == 1) {
                    spork ~ run_arp(r, pitch[idx] + 60, vel[idx] * trk_lvl[r], car[r], mod[r], env[r]);
                } else {
                    Std.mtof(pitch[idx] + 60) => f;
                    car[r].freq(f); mod[r].freq(f * g_fm_ratio[r]); mod[r].gain(g_fm_amount[r] * 1000.0);
                    vel[idx] * trk_lvl[r] * 0.8 => env[r].gain;
                    env[r].keyOn();
                }
            }
        }
    }
}

// ── helper: arpeggiator runner ──────────────────────────────────────────
fun void run_arp(int v, int base_midi, float gain, MorphingWavetable car, MorphingWavetable mod, DelugeAdsr env) {
    int octaves, m; float f; dur d;
    g_arp_octave[v] => octaves; if (octaves < 1) 1 => octaves;
    for (0 => int o; o < octaves; o++) {
        base_midi + (o * 12) => m; Std.mtof(m) => f;
        car.freq(f); mod.freq(f * g_fm_ratio[v]); mod.gain(g_fm_amount[v] * 1000.0);
        gain * 0.8 => env.gain; env.keyOn();
        (60.0 / g_bpm / 4.0 / g_arp_rate[v]) * second => d;
        d * 0.8 => now; env.keyOff(); d * 0.2 => now;
        if (g_play == 0 || g_arp_on[v] == 0) break;
    }
}

// ── sidechain & master ──────────────────────────────────────────────────
fun void sidechain_shred() { while (true) { sidechain_event => now; 100::ms => now; } }

fun void master_shred() {
    HPF hpf; Dyno limit, comp; DelugeAdsr gate; float th;
    g_synth_bus => hpf => comp => limit => gate => dac;
    hpf.freq(20); limit.limit(); comp.compress();
    100::ms => now; gate.forceMute(); gate.set(0.005, 0, 1, 0.005);
    while (true) {
        tick_event => now;
        if (g_play != 0) { gate.keyOn(); } else { gate.keyOff(); }
        1.0 - g_master_comp => th; comp.thresh(Math.max(0.01, Math.min(0.9, th)));
    }
}

// ── clock & transport ──────────────────────────────────────────────────
fun void clock_shred() {
    0 => int step;
    stepDuration(0) - (now % stepDuration(0)) => now;
    while (true) {
        if (g_play == 0) { 0 => step; 10::ms => now; continue; }
        if (g_stutter_on == 0) {
            step % 16 => g_current_step; tick_event.broadcast();
            stepDuration(step % 2) => now; step++;
        } else {
            tick_event.broadcast();
            stepDuration(step % 2) / Math.max(1.0, g_stutter_div) => now;
        }
    }
}

fun void fx_bus_shred() {
    Gain fx_in; DelugeAdsr gate; Echo d; JCRev r; Chorus m;
    fx_in => gate => dac;
    g_delay_in => d => fx_in; g_reverb_in => r => fx_in; g_mod_in => m => fx_in;
    100::ms => now; gate.forceMute(); gate.set(0.01, 0, 1, 0.01);
    while (true) {
        tick_event => now; if (g_play != 0) gate.keyOn(); else gate.keyOff();
        g_delay_time * second => d.delay; g_delay_fb => d.gain; g_reverb_room => r.mix;
    }
}

fun void midi_handler_shred() {
    MorphingWavetable osc; SVFilter fil; DelugeAdsr env; Pan2 p;
    osc => fil => env => p => g_synth_bus;
    fil.freq(2000); env.set(0.01, 0.1, 0.7, 0.2); env.forceMute();
    while (true) {
        (midi_note_on, midi_note_off) => now;
        if (g_midi_vel > 0) { osc.freq(Std.mtof(g_midi_note)); g_midi_vel/127.0*0.8 => env.gain; env.keyOn(); }
        else env.keyOff();
    }
}

spork ~ fx_bus_shred(); spork ~ sidechain_shred(); spork ~ master_shred(); 
spork ~ midi_handler_shred(); spork ~ clock_shred(); spork ~ kit_shred(); spork ~ synth_shred();

while (true) { 100::ms => now; }
