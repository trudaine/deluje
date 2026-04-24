/*
  Deluge Emulator — Production Engine v1.1
  Five concurrent shreds:
    1. clock      — tempo/swing master; broadcasts tick events
    2. kit        — 8-track drum kit, reads g_pattern + g_velocity + g_probability + g_mute
    3. fx_bus     — global delay + reverb bus, reads g_delay_* + g_reverb_*
    4. heartbeat  — diagnostic heartbeat at log level 2+
    5. transport  — watches g_play; re-sporks clock+kit on play, removes on stop
*/

// ── global bridge variables ────────────────────────────────────────────────
global float g_bpm;
global float g_swing;
global int   g_play;
global int   g_current_step;
global float g_master_vol;
global float g_master_pan;

global float g_delay_time;
global float g_delay_fb;
global float g_reverb_room;
global float g_reverb_damp;

global int   g_stutter_on;
global float g_stutter_div;

global int   g_scale;
global int   g_root_key;

global Gain g_delay_in;
global Gain g_reverb_in;
global Gain g_mod_in;

global string g_sample_0;
global string g_sample_1;
global string g_sample_2;
global string g_sample_3;
global string g_sample_4;
global string g_sample_5;
global string g_sample_6;
global string g_sample_7;
global Event  g_load_trigger;

global Event tick_event;

// ── helper: BPM → step duration ──────────────────────────────────────────
fun dur stepDuration(int step) {
    if (g_bpm < 1.0) 120.0 => g_bpm;
    Math.max(0.0, Math.min(1.0, g_swing)) => float swing;
    60.0 / g_bpm / 4.0 => float base_sec;
    base_sec * second => dur base;
    if (step % 2 == 0) {
        return base * (1.0 + (swing - 0.5) * 0.4);
    } else {
        return base * (1.0 - (swing - 0.5) * 0.4);
    }
}

// ── helper: monitor sample end ───────────────────────────────────────────
fun void monitor_sample_end(SndBuf buf, int end_pos) {
    if (buf == null) return;
    while (buf.pos() < end_pos && buf.rate() > 0) {
        5::ms => now;
    }
    if (buf == null) return;
    0 => buf.rate();
    buf.samples() => buf.pos();
}

// ── Kit Class for Dynamic Loading ────────────────────────────────────────
class DelugeKit {
    SndBuf kit[64];
    Pan2   pan[64];
    Gain   delay_send[64];
    Gain   reverb_send[64];
    Gain   mod_send[64];

    fun void init(Gain master) {
        for (0 => int i; i < 64; i++) {
            0 => kit[i].rate;
            kit[i].samples() => kit[i].pos;
            kit[i] => pan[i] => master;
            pan[i] => delay_send[i]  => g_delay_in;
            pan[i] => reverb_send[i] => g_reverb_in;
            pan[i] => mod_send[i]    => g_mod_in;
        }
    }

    fun void load_all() {
        if (g_sample_0 != "") g_sample_0 => kit[0].read;
        if (g_sample_1 != "") g_sample_1 => kit[1].read;
        if (g_sample_2 != "") g_sample_2 => kit[2].read;
        if (g_sample_3 != "") g_sample_3 => kit[3].read;
        if (g_sample_4 != "") g_sample_4 => kit[4].read;
        if (g_sample_5 != "") g_sample_5 => kit[5].read;
        if (g_sample_6 != "") g_sample_6 => kit[6].read;
        if (g_sample_7 != "") g_sample_7 => kit[7].read;
        for (0 => int i; i < 64; i++) {
            0 => kit[i].rate;
            kit[i].samples() => kit[i].pos;
        }

        if (Machine.loglevel() >= 1) <<< "ENGINE: Kit samples loaded" >>>;
    }

    fun void load_listener() {
        while (true) {
            g_load_trigger => now;
            load_all();
        }
    }
}

// ── kit shred ─────────────────────────────────────────────────────────────
fun void kit_shred() {
    Gain   master;
    HPF    hpf;
    Dyno   limiter;
    DelugeAdsr safety_gate => dac;
    
    master => hpf => limiter => safety_gate;
    master !=> dac;
    hpf !=> dac;
    limiter !=> dac;

    0 => master.gain;
    20 => hpf.freq;
    limiter.limit();

    100::ms => now;
    safety_gate.forceMute();
    safety_gate.set(0.001, 0.0, 1.0, 0.001);
    0 => int gates_open;

    DelugeKit dkit;
    dkit.init(master);
    dkit.load_all();
    spork ~ dkit.load_listener();

    int pattern[];
    float velocity[];
    float probability[];
    int mute[];
    float step_pan[];
    float g_delay_send[];
    float g_reverb_send[];
    float step_delay[];
    float step_reverb[];
    float step_mod[];
    float step_start[];
    float step_end[];
    float track_level[];
    int track_type[];

    while (g_play != 0) {
        tick_event => now;
        if (g_play == 0) break;

        Machine.getGlobalObject("g_pattern") $ int[] @=> pattern;
        Machine.getGlobalObject("g_velocity") $ float[] @=> velocity;
        Machine.getGlobalObject("g_probability") $ float[] @=> probability;
        Machine.getGlobalObject("g_mute") $ int[] @=> mute;
        Machine.getGlobalObject("g_step_pan") $ float[] @=> step_pan;
        Machine.getGlobalObject("g_delay_send") $ float[] @=> g_delay_send;
        Machine.getGlobalObject("g_reverb_send") $ float[] @=> g_reverb_send;
        Machine.getGlobalObject("g_step_delay") $ float[] @=> step_delay;
        Machine.getGlobalObject("g_step_reverb") $ float[] @=> step_reverb;
        Machine.getGlobalObject("g_step_mod") $ float[] @=> step_mod;
        Machine.getGlobalObject("g_step_start") $ float[] @=> step_start;
        Machine.getGlobalObject("g_step_end") $ float[] @=> step_end;
        Machine.getGlobalObject("g_track_level") $ float[] @=> track_level;
        Machine.getGlobalObject("g_track_type") $ int[] @=> track_type;

        if (pattern == null || velocity == null || probability == null || mute == null || step_pan == null 
            || g_delay_send == null || g_reverb_send == null || step_delay == null || step_reverb == null 
            || step_mod == null || step_start == null || step_end == null || track_level == null || track_type == null) continue;

        g_master_vol => master.gain;
        int step;
        g_current_step % 16 => step;

        for (0 => int r; r < 8; r++) {
            if (track_type[r] != 0) continue;
            if (mute[r] != 0) continue;

            r * 16 + step => int idx;
            
            Math.max(0.0, Math.min(1.0, g_delay_send[r] + step_delay[idx])) => dkit.delay_send[r].gain;
            Math.max(0.0, Math.min(1.0, g_reverb_send[r] + step_reverb[idx])) => dkit.reverb_send[r].gain;
            Math.max(0.0, Math.min(1.0, step_mod[idx])) => dkit.mod_send[r].gain;

            Math.max(-1.0, Math.min(1.0, g_master_pan + step_pan[idx])) => dkit.pan[r].pan;

            if (Math.random2f(0.0, 1.0) > probability[idx]) continue;
            if (pattern[idx] == 0) continue;

            velocity[idx] => float vel;
            1 => dkit.kit[r].rate;
            
            (step_start[idx] * dkit.kit[r].samples()) $ int => int start_pos;
            (step_end[idx] * dkit.kit[r].samples()) $ int => int end_pos;
            start_pos => dkit.kit[r].pos;
            vel * track_level[r] * 0.8 => dkit.kit[r].gain;
            
            spork ~ monitor_sample_end(dkit.kit[r], end_pos);

            if (gates_open == 0) {
                1 => gates_open;
                safety_gate.keyOn();
            }
        }
    }
}

// ── synth shred ───────────────────────────────────────────────────────────
fun void synth_shred() {
    MorphingWavetable osc[8];
    SVFilter filter[8];
    ShelfEQ eq[8];
    DelugeAdsr env[8];
    Pan2  pan[8];
    Gain delay_send[8];
    Gain reverb_send[8];
    Gain mod_send[8];
    Gain master;
    HPF hpf;
    Dyno limiter;
    DelugeAdsr safety_gate => dac;
    
    master => hpf => limiter => safety_gate;
    master !=> dac;
    hpf !=> dac;
    limiter !=> dac;

    0 => master.gain;
    20 => hpf.freq;
    limiter.limit();
    
    100::ms => now;
    safety_gate.forceMute();
    safety_gate.set(0.001, 0.0, 1.0, 0.001);
    0 => int gates_open;

    for (0 => int i; i < 8; i++) {
        osc[i] => filter[i] => eq[i] => env[i] => pan[i] => master;
        pan[i] => delay_send[i]  => g_delay_in;
        pan[i] => reverb_send[i] => g_reverb_in;
        pan[i] => mod_send[i]    => g_mod_in;

        filter[i].reset();
        eq[i].reset();
        filter[i].morph(0.0);
        filter[i].freq(5000);
        filter[i].Q(1.5);
        env[i].set(0.05, 0.2, 0.5, 0.3);
    }

    g_master_vol => master.gain;

    int   pattern[];
    float velocity[];
    int   pitch[];
    float probability[];
    int   mute[];
    float g_filter[];
    float step_filter[];
    float step_res[];
    float step_pan[];
    float g_delay_send[];
    float g_reverb_send[];
    float step_delay[];
    float step_reverb[];
    float step_mod[];
    float track_level[];
    int   track_type[];
    float gate[];

    // Helper to play a note with variable duration
    fun void play_note(DelugeAdsr e, MorphingWavetable o, int p, float vel, float gate_len, dur T) {
        if (Machine.loglevel() >= 2) <<< "!!! NOTE START !!! Pitch:", p, "Gate:", gate_len >>>;
        Std.mtof(p + 60) => o.freq;
        vel => e.gain;
        e.keyOn();
        (gate_len * T) => now;
        e.keyOff();
        if (Machine.loglevel() >= 2) <<< "!!! NOTE END !!!" >>>;
    }

    while (g_play != 0) {
        tick_event => now;
        if (g_play == 0) break;

        Machine.getGlobalObject("g_pattern") $ int[] @=> pattern;
        Machine.getGlobalObject("g_velocity") $ float[] @=> velocity;
        Machine.getGlobalObject("g_pitch") $ int[] @=> pitch;
        Machine.getGlobalObject("g_probability") $ float[] @=> probability;
        Machine.getGlobalObject("g_mute") $ int[] @=> mute;
        Machine.getGlobalObject("g_filter") $ float[] @=> g_filter;
        Machine.getGlobalObject("g_step_filter") $ float[] @=> step_filter;
        Machine.getGlobalObject("g_step_res") $ float[] @=> step_res;
        Machine.getGlobalObject("g_step_pan") $ float[] @=> step_pan;
        Machine.getGlobalObject("g_delay_send") $ float[] @=> g_delay_send;
        Machine.getGlobalObject("g_reverb_send") $ float[] @=> g_reverb_send;
        Machine.getGlobalObject("g_step_delay") $ float[] @=> step_delay;
        Machine.getGlobalObject("g_step_reverb") $ float[] @=> step_reverb;
        Machine.getGlobalObject("g_step_mod") $ float[] @=> step_mod;
        Machine.getGlobalObject("g_track_level") $ float[] @=> track_level;
        Machine.getGlobalObject("g_track_type") $ int[] @=> track_type;
        Machine.getGlobalObject("g_gate") $ float[] @=> gate;

        if (pattern == null || velocity == null || pitch == null || g_filter == null || step_filter == null || step_res == null
            || step_pan == null || g_delay_send == null || g_reverb_send == null || step_delay == null 
            || step_reverb == null || step_mod == null || track_level == null || track_type == null || gate == null) continue;

        g_master_vol => master.gain;
        int step;
        g_current_step % 16 => step;

        for (0 => int r; r < 8; r++) {
            if (track_type[r] != 1) continue;

            r => int v;
            r * 16 + step => int idx;
            
            velocity[idx] => float vel;
            vel * track_level[r] * 0.8 => env[v].gain;

            Math.max(0.0, Math.min(1.0, g_delay_send[r] + step_delay[idx])) => delay_send[v].gain;
            Math.max(0.0, Math.min(1.0, g_reverb_send[r] + step_reverb[idx])) => reverb_send[v].gain;
            Math.max(0.0, Math.min(1.0, step_mod[idx])) => mod_send[v].gain;

            Math.max(-1.0, Math.min(1.0, g_master_pan + step_pan[idx])) => pan[v].pan;

            (g_filter[r * 2] + step_filter[idx]) * 20000.0 => float cutoff;
            filter[v].freq(Math.max(20.0, Math.min(20000.0, cutoff)));
            
            (g_filter[r * 2 + 1] + step_res[idx]) * 4.0 + 1.0 => float Q;
            filter[v].Q(Math.max(1.0, Math.min(10.0, Q)));

            if (mute[r] != 0) continue;
            if (Math.random2f(0.0, 1.0) > probability[idx]) continue;

            if (pattern[idx] == 0) {
                continue;
            }

            pitch[idx] => int p;
            gate[idx] => float gate_len;

            if (gates_open == 0) {
                1 => gates_open;
                safety_gate.keyOn();
            }
            
            env[v].forceMute(); // Cut off previous note on this voice
            spork ~ play_note(env[v], osc[v], p, vel, gate_len, stepDuration(0));
        }
    }
}

// ── clock shred ──────────────────────────────────────────────────────────
fun void clock_shred() {
    0 => int step;
    stepDuration(0) - (now % stepDuration(0)) => now;

    while (g_play != 0) {
        if (g_stutter_on == 0) {
            step % 16 => g_current_step;
            tick_event.broadcast();
            stepDuration(step % 2) => now;
            step++;
        } else {
            tick_event.broadcast();
            if (g_stutter_div < 1.0) 1.0 => g_stutter_div;
            stepDuration(step % 2) / g_stutter_div => dur stutter_dur;
            stutter_dur => now;
        }
    }
}

// ── fx_bus shred ─────────────────────────────────────────────────────────
fun void fx_bus_shred() {
    Gain   fx_in;
    HPF    hpf;
    Dyno   limiter;
    DelugeAdsr safety_gate => dac;
    
    fx_in => hpf => limiter => safety_gate;
    fx_in !=> dac;
    hpf   !=> dac;
    limiter !=> dac;

    20 => hpf.freq;
    limiter.limit();

    100::ms => now;
    safety_gate.forceMute();
    safety_gate.set(0.001, 0.0, 1.0, 0.001);
    0 => int gates_open;

    Echo   delay;
    JCRev  rev;
    Chorus mod;
    
    g_delay_in  => delay => fx_in;
    g_reverb_in => rev   => fx_in;
    g_mod_in    => mod   => fx_in;

    0.3   => fx_in.gain;
    0.2   => mod.modDepth;
    0.5   => mod.modFreq;

    while (true) {
        tick_event => now;
        if (gates_open == 0 && g_play != 0) {
            1 => gates_open;
            safety_gate.keyOn();
        }
        g_delay_time * second => delay.delay;
        g_delay_fb            => delay.gain;
        g_reverb_room         => rev.mix;
    }
}

// ── transport shred ──────────────────────────────────────────────────────
fun void transport_shred() {
    0 => int last_play;
    while (true) {
        10::ms => now;
        if (g_play != last_play) {
            g_play => last_play;
            if (g_play == 1) {
                spork ~ clock_shred();
                spork ~ kit_shred();
                spork ~ synth_shred();
                100::ms => now;
            } else {
                -1 => g_current_step;
                tick_event.broadcast(); 
            }
        }
    }
}

// ── boot ──────────────────────────────────────────────────────────────────
spork ~ fx_bus_shred();
transport_shred();
