/*
  Deluge Emulator — Production Engine v1.4 (Stable DSP)
  Concurrent shreds: clock, kit, synth, fx_bus, sidechain, master.
*/

// ── global bridge variables ────────────────────────────────────────────────
global float g_bpm, g_swing, g_master_vol, g_master_pan;
global int   g_play, g_current_step, g_scale, g_root_key;
global float g_delay_time, g_delay_fb, g_reverb_room, g_reverb_damp;
global int   g_stutter_on;
global float g_stutter_div;

// Advanced DSP
global float g_fm_ratio, g_fm_amount, g_sidechain_amount, g_master_comp;
global Gain g_delay_in, g_reverb_in, g_mod_in, g_synth_bus;
global Event tick_event, sidechain_event;

// ── helpers ──────────────────────────────────────────────────────────────
fun dur stepDuration(int step) {
    if (g_bpm < 1.0) 120.0 => g_bpm;
    Math.max(0.0, Math.min(1.0, g_swing)) => float swing;
    60.0 / g_bpm / 4.0 => float base_sec;
    if (step % 2 == 0) return base_sec * (1.0 + (swing - 0.5) * 0.4) * second;
    else return base_sec * (1.0 - (swing - 0.5) * 0.4) * second;
}

fun void monitor_sample_end(SndBuf buf, int end_pos) {
    if (buf == null) return;
    while (buf.pos() < end_pos && buf.rate() > 0) 5::ms => now;
    if (buf == null) return;
    0 => buf.rate(); buf.samples() => buf.pos();
}

// ── kit shred ─────────────────────────────────────────────────────────────
fun void kit_shred() {
    SndBuf kit[4]; Pan2 pan[4]; Gain d_send[4], r_send[4];
    Gain master => HPF hpf => Dyno limit => DelugeAdsr gate => dac;
    master !=> dac; hpf !=> dac; limit !=> dac;
    0 => master.gain; 20 => hpf.freq; limit.limit();
    100::ms => now; gate.forceMute(); gate.set(0.001, 0, 1, 0.001);
    0 => int gates_open;

    "examples/data/kick.wav" => kit[0].read; "examples/data/snare.wav" => kit[1].read;
    "examples/data/hihat.wav" => kit[2].read; "examples/data/hihat-open.wav" => kit[3].read;

    for (0 => int i; i < 4; i++) {
        0 => kit[i].rate; kit[i].samples() => kit[i].pos;
        kit[i] => pan[i] => master;
        pan[i] => d_send[i] => g_delay_in; pan[i] => r_send[i] => g_reverb_in;
    }

    int pat[], mute[]; float vel[], prob[], trk_lvl[], s_start[], s_end[];
    while (g_play != 0) {
        tick_event => now; if (g_play == 0) break;
        Machine.getGlobalObject("g_pattern") $ int[] @=> pat;
        Machine.getGlobalObject("g_velocity") $ float[] @=> vel;
        Machine.getGlobalObject("g_mute") $ int[] @=> mute;
        Machine.getGlobalObject("g_track_level") $ float[] @=> trk_lvl;
        Machine.getGlobalObject("g_step_start") $ float[] @=> s_start;
        Machine.getGlobalObject("g_step_end") $ float[] @=> s_end;
        if (pat == null || trk_lvl == null) continue;
        
        g_master_vol => master.gain;
        g_current_step % 16 => int step;
        for (0 => int r; r < 4; r++) {
            if (mute[r] != 0) continue;
            r * 16 + step => int idx;
            if (pat[idx] == 0) continue;
            if (r == 0) sidechain_event.broadcast();
            1 => kit[r].rate; (s_start[idx] * kit[r].samples()) $ int => kit[r].pos;
            vel[idx] * trk_lvl[r] * 0.8 => kit[r].gain;
            spork ~ monitor_sample_end(kit[r], (s_end[idx] * kit[r].samples()) $ int);
            if (gates_open == 0) { 1 => gates_open; gate.keyOn(); }

            if (Machine.loglevel() >= 2) {
                <<< "KIT trigger track:", r, "step:", step, "vel:", vel[idx] >>>;
            }
        }
    }
}

// ── synth shred ───────────────────────────────────────────────────────────
fun void synth_shred() {
    MorphingWavetable car[4], mod[4]; SVFilter fil[4]; DelugeAdsr env[4]; Pan2 pan[4]; SinOsc lfo[4];
    for (0 => int i; i < 4; i++) {
        mod[i] => car[i]; 2 => car[i].sync;
        car[i] => fil[i] => env[i] => pan[i] => g_synth_bus;
        fil[i].reset(); fil[i].freq(5000); env[i].set(0.05, 0.2, 0.5, 0.3);
        lfo[i] => blackhole;
    }

    int pat[], pitch[], mute[]; float vel[], trk_lvl[], s_fil[], s_res[], s_pan[], g_fil[], l_rate[], l_depth[];

    while (g_play != 0) {
        tick_event => now; if (g_play == 0) break;
        Machine.getGlobalObject("g_pattern") $ int[] @=> pat;
        Machine.getGlobalObject("g_velocity") $ float[] @=> vel;
        Machine.getGlobalObject("g_pitch") $ int[] @=> pitch;
        Machine.getGlobalObject("g_mute") $ int[] @=> mute;
        Machine.getGlobalObject("g_track_level") $ float[] @=> trk_lvl;
        Machine.getGlobalObject("g_filter") $ float[] @=> g_fil;
        Machine.getGlobalObject("g_step_filter") $ float[] @=> s_fil;
        Machine.getGlobalObject("g_step_res") $ float[] @=> s_res;
        Machine.getGlobalObject("g_step_pan") $ float[] @=> s_pan;
        Machine.getGlobalObject("g_lfo_rate") $ float[] @=> l_rate;
        Machine.getGlobalObject("g_lfo_depth") $ float[] @=> l_depth;
        if (pat == null || g_fil == null) continue;

        g_current_step % 16 => int step;
        stepDuration(step) => dur d;
        
        for (4 => int r; r < 8; r++) {
            r - 4 => int v; r * 16 + step => int idx;
            l_rate[v] => lfo[v].freq;
            (g_fil[r * 2] + s_fil[idx]) * 10000.0 + 100.0 => float tf;
            (g_fil[r * 2 + 1] + s_res[idx]) * 4.0 + 1.0 => float tq;
            g_master_pan + s_pan[idx] => float tp;

            // Simplified smoothing: just set directly for now to avoid NPEs
            // We will re-add interpolation once the objects are guaranteed non-null
            fil[v].freq(tf); fil[v].Q(tq); pan[v].pan(tp);

            if (mute[r] != 0) continue;
            if (pat[idx] == 0) { env[v].keyOff(); continue; }
            Std.mtof(pitch[idx] + 60) => float f;
            f => car[v].freq; f * g_fm_ratio => mod[v].freq;
            g_fm_amount * 1000.0 => mod[v].gain;
            vel[idx] * trk_lvl[r] * 0.8 => env[v].gain;
            env[v].keyOn();
        }
    }
}

// ── sidechain & master ──────────────────────────────────────────────────
fun void sidechain_shred() {
    while (true) { sidechain_event => now; 100::ms => now; } // placeholder
}

fun void master_shred() {
    HPF hpf; Dyno limit, comp; DelugeAdsr gate => dac;
    g_synth_bus => hpf => comp => limit => gate;
    20 => hpf.freq; limit.limit(); comp.compress();
    100::ms => now; gate.forceMute(); gate.set(0.005, 0, 1, 0.005);
    while (true) {
        tick_event => now; if (g_play != 0) gate.keyOn();
        1.0 - g_master_comp => float th; comp.thresh(Math.max(0.01, Math.min(0.9, th)));
    }
}

// ── clock & transport ──────────────────────────────────────────────────
fun void clock_shred() {
    0 => int step;
    stepDuration(0) - (now % stepDuration(0)) => now;
    while (g_play != 0) {
        if (g_stutter_on == 0) {
            step % 16 => g_current_step; tick_event.broadcast();
            stepDuration(step % 2) => now; step++;
        } else {
            tick_event.broadcast();
            stepDuration(step % 2) / g_stutter_div => now;
        }
    }
}

fun void fx_bus_shred() {
    Gain fx_in => DelugeAdsr gate => dac;
    g_delay_in => Echo d => fx_in; g_reverb_in => JCRev r => fx_in; g_mod_in => Chorus m => fx_in;
    100::ms => now; gate.forceMute(); gate.set(0.01, 0, 1, 0.01);
    while (true) {
        tick_event => now; if (g_play != 0) gate.keyOn();
        g_delay_time * second => d.delay; g_delay_fb => d.gain; g_reverb_room => r.mix;
    }
}

fun void midi_handler_shred() {
    MorphingWavetable osc => SVFilter fil => DelugeAdsr env => Pan2 p => g_synth_bus;
    fil.freq(2000); env.set(0.01, 0.1, 0.7, 0.2);
    while (true) {
        (midi_note_on, midi_note_off) => now;
        global int g_midi_vel, g_midi_note;
        if (g_midi_vel > 0) { Std.mtof(g_midi_note) => osc.freq; g_midi_vel/127.0*0.8 => env.gain; env.keyOn(); }
        else env.keyOff();
    }
}

fun void transport_shred() {
    0 => int last_play;
    spork ~ fx_bus_shred(); spork ~ sidechain_shred(); spork ~ master_shred(); spork ~ midi_handler_shred();
    while (true) {
        10::ms => now;
        if (g_play != last_play) {
            g_play => last_play;
            if (g_play == 1) { spork ~ clock_shred(); spork ~ kit_shred(); spork ~ synth_shred(); }
            else { -1 => g_current_step; tick_event.broadcast(); }
        }
    }
}

transport_shred();
