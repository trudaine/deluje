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
// (defined here so ChucK sees them; Java-side values take precedence via setGlobal*)

global float g_bpm;
global float g_swing;       // 0.0–1.0; 0.5 = no swing
global int   g_play;
global int   g_current_step;
global float g_master_vol;
global float g_master_pan;

// Global FX parameters (scalars)
global float g_delay_time;
global float g_delay_fb;
global float g_reverb_room;
global float g_reverb_damp;

// Stutter
global int   g_stutter_on;  // 0 = off, 1 = on
global float g_stutter_div; // 1.0=1/16, 2.0=1/32, etc.

// Scale/Key
global int   g_scale;
global int   g_root_key;

// Global FX input buses
global Gain g_delay_in;
global Gain g_reverb_in;
global Gain g_mod_in;

// ── tick event — broadcast once per step ──────────────────────────────────
global Event tick_event;

// ── helper: BPM → step duration (16th note) with swing ──────────────────
fun dur stepDuration(int step) {
    // base 16th-note duration at current BPM
    if (g_bpm < 1.0) 120.0 => g_bpm;
    // Clamp swing to safe range (0.0 to 1.0)
    Math.max(0.0, Math.min(1.0, g_swing)) => float swing;
    60.0 / g_bpm / 4.0 => float base_sec;
    base_sec * second => dur base;
    // even steps (0,2,4...) get swing push; odd get pull
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
    0 => buf.rate(); // Stop playback
    buf.samples() => buf.pos(); // Move to end
}

// ── kit shred ─────────────────────────────────────────────────────────────
fun void kit_shred() {
    SndBuf kit[4];
    Pan2   pan[4];
    Gain   delay_send[4];
    Gain   reverb_send[4];
    Gain   mod_send[4];
    Gain   master;
    HPF    hpf;
    Dyno   limiter;
    DelugeAdsr safety_gate => dac;
    
    master => hpf => limiter => safety_gate;
    master !=> dac;
    hpf !=> dac;
    limiter !=> dac;

    "KIT_MASTER" => master.setName;
    "KIT_LIMIT"  => limiter.setName;
    "KIT_GATE"   => safety_gate.setName;
    
    0 => master.gain; // Prevent start-up clicks
    20 => hpf.freq;
    limiter.limit();

    100::ms => now; // Settle time
    safety_gate.forceMute();
    safety_gate.set(0.001, 0.0, 1.0, 0.001);
    0 => int gates_open;

    // Load samples for first 4 tracks
    "examples/data/kick.wav"       => kit[0].read;
    "examples/data/snare.wav"      => kit[1].read;
    "examples/data/hihat.wav"      => kit[2].read;
    "examples/data/hihat-open.wav" => kit[3].read;

    for (0 => int i; i < 4; i++) {
        0 => kit[i].rate;
        kit[i].samples() => kit[i].pos; // silence on load
        kit[i] => pan[i] => master;
        pan[i] => delay_send[i]  => g_delay_in;
        pan[i] => reverb_send[i] => g_reverb_in;
        pan[i] => mod_send[i]    => g_mod_in;
    }

    g_master_vol => master.gain;

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

        if (pattern == null || velocity == null || probability == null || mute == null || step_pan == null 
            || g_delay_send == null || g_reverb_send == null || step_delay == null || step_reverb == null 
            || step_mod == null || step_start == null || step_end == null || track_level == null) continue;

        g_master_vol => master.gain;
        int step;
        g_current_step % 16 => step;

        // Tracks 0 to 3 are Kit
        for (0 => int r; r < 4; r++) {
            if (mute[r] != 0) continue;
            r * 16 + step => int idx;
            
            // Set FX send levels (track level + step offset)
            Math.max(0.0, Math.min(1.0, g_delay_send[r] + step_delay[idx])) => delay_send[r].gain;
            Math.max(0.0, Math.min(1.0, g_reverb_send[r] + step_reverb[idx])) => reverb_send[r].gain;
            Math.max(0.0, Math.min(1.0, step_mod[idx])) => mod_send[r].gain;

            // Set Pan (master pan + step offset)
            Math.max(-1.0, Math.min(1.0, g_master_pan + step_pan[idx])) => pan[r].pan;

            if (Math.random2f(0.0, 1.0) > probability[idx]) continue;
            if (pattern[idx] == 0) continue;

            velocity[idx] => float vel;
            1 => kit[r].rate;
            
            // Calculate playback range
            (step_start[idx] * kit[r].samples()) $ int => int start_pos;
            (step_end[idx] * kit[r].samples()) $ int => int end_pos;
            start_pos => kit[r].pos;
            vel * track_level[r] * 0.8 => kit[r].gain;
            
            // Monitor end point in a sporked shred
            spork ~ monitor_sample_end(kit[r], end_pos);

            if (gates_open == 0) {
                1 => gates_open;
                safety_gate.keyOn();
            }

            if (Machine.loglevel() >= 2) {
                <<< "KIT trigger track:", r, "step:", step, "vel:", vel >>>;
            }
        }
    }
}

// ── synth shred ───────────────────────────────────────────────────────────
fun void synth_shred() {
    MorphingWavetable osc[4];
    SVFilter filter[4];
    ShelfEQ eq[4];
    DelugeAdsr env[4];
    Pan2  pan[4];
    Gain delay_send[4];
    Gain reverb_send[4];
    Gain mod_send[4];
    Gain master;
    HPF hpf;
    Dyno limiter;
    DelugeAdsr safety_gate => dac;
    
    // Strict serial chain: master -> hpf -> limiter -> safety_gate -> dac
    master => hpf => limiter => safety_gate;
    
    // DEFENSIVE: Ensure intermediate stages are NOT connected to dac
    master !=> dac;
    hpf !=> dac;
    limiter !=> dac;

    "SYNTH_MASTER"  => master.setName;
    "SYNTH_HPF"     => hpf.setName;
    "SYNTH_LIMIT"   => limiter.setName;
    "SYNTH_GATE"    => safety_gate.setName;
    
    0 => master.gain; // Prevent start-up clicks
    20 => hpf.freq;   // Kill DC/infra noise
    limiter.limit();  // Hard safety limit
    
    // Safety gate is IDLE (muted) by default
    100::ms => now; // Settle time
    safety_gate.forceMute();
    // Use a very fast envelope for the master gate
    safety_gate.set(0.001, 0.0, 1.0, 0.001);
    
    0 => int gates_open;

    // Also defensive: Ensure voices only connect to master, not dac
    for (0 => int i; i < 4; i++) {
        osc[i] => filter[i];
        filter[i] => eq[i];
        eq[i] => env[i];
        env[i] => pan[i] => master;
        pan[i] => delay_send[i]  => g_delay_in;
        pan[i] => reverb_send[i] => g_reverb_in;
        pan[i] => mod_send[i]    => g_mod_in;

        filter[i].reset();
        eq[i].reset();
        // set some base params
        filter[i].morph(0.0); // LP
        filter[i].freq(5000);
        filter[i].Q(1.5);
        eq[i].bassGain(0);
        eq[i].trebleGain(0);
        env[i].set(0.05, 0.2, 0.5, 0.3); // A D S R
    }

    g_master_vol => master.gain;

    float mute[];
    float g_filter[];
    float step_filter[];
    float step_res[];
    float g_delay_send[];
    float g_reverb_send[];
    float step_delay[];
    float step_reverb[];
    float step_mod[];
    float track_level[];

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
        Machine.getGlobalObject("g_delay_send") $ float[] @=> g_delay_send;
        Machine.getGlobalObject("g_reverb_send") $ float[] @=> g_reverb_send;
        Machine.getGlobalObject("g_step_delay") $ float[] @=> step_delay;
        Machine.getGlobalObject("g_step_reverb") $ float[] @=> step_reverb;
        Machine.getGlobalObject("g_step_mod") $ float[] @=> step_mod;
        Machine.getGlobalObject("g_track_level") $ float[] @=> track_level;

        if (pattern == null || pitch == null || g_filter == null || step_filter == null || step_res == null
            || g_delay_send == null || g_reverb_send == null || step_delay == null || step_reverb == null || step_mod == null || track_level == null) continue;

        g_master_vol => master.gain;
        int step;
        g_current_step % 16 => step;

        // Tracks 4 to 7 are Synth
        for (4 => int r; r < 8; r++) {
            r - 4 => int v; // Voice index 0-3
            r * 16 + step => int idx;
            
            velocity[idx] => float vel;
            vel * track_level[r] * 0.8 => env[v].gain;

            // Set FX send levels (track level + step offset)
            Math.max(0.0, Math.min(1.0, g_delay_send[r] + step_delay[idx])) => delay_send[v].gain;
            Math.max(0.0, Math.min(1.0, g_reverb_send[r] + step_reverb[idx])) => reverb_send[v].gain;
            Math.max(0.0, Math.min(1.0, step_mod[idx])) => mod_send[v].gain;

            // Set Pan (master pan + step offset)
            Math.max(-1.0, Math.min(1.0, g_master_pan + step_pan[idx])) => pan[v].pan;

            // Map g_filter + step offset to synth
            (g_filter[r * 2] + step_filter[idx]) * 20000.0 => float cutoff;
            filter[v].freq(Math.max(20.0, Math.min(20000.0, cutoff)));
            
            (g_filter[r * 2 + 1] + step_res[idx]) * 4.0 + 1.0 => float Q;
            filter[v].Q(Math.max(1.0, Math.min(10.0, Q)));

            if (mute[r] != 0) continue;
            r * 16 + step => int idx;
            if (Math.random2f(0.0, 1.0) > probability[idx]) continue;

            if (pattern[idx] == 0) {
                // simple gate off if not triggered (in reality we need step duration)
                env[v].keyOff();
                continue;
            }

            // Convert pitch to Hz (assuming 60 is middle C)
            pitch[idx] => int p;
            Std.mtof(p + 60) => osc[v].freq;

            if (gates_open == 0) {
                1 => gates_open;
                safety_gate.keyOn();
            }
            env[v].keyOn();

            if (Machine.loglevel() >= 2) {
                <<< "SYNTH trigger track:", r, "step:", step, "pitch:", p >>>;
            }
        }
    }
}

// ── clock shred ──────────────────────────────────────────────────────────
fun void clock_shred() {
    0 => int step;

    // Align to grid
    stepDuration(0) - (now % stepDuration(0)) => now;

    while (g_play != 0) {
        if (g_stutter_on == 0) {
            step % 16 => g_current_step;
            tick_event.broadcast();

            if (Machine.loglevel() >= 2 && step % 16 == 0) {
                <<< "CLOCK: beat", step / 16, "BPM:", g_bpm, "swing:", g_swing >>>;
            }

            stepDuration(step % 2) => now;
            step++;
        } else {
            // STUTTER: repeat current step at faster rate
            g_current_step => int current;
            tick_event.broadcast();
            
            // Subdivide the step duration
            if (g_stutter_div < 1.0) 1.0 => g_stutter_div;
            stepDuration(step % 2) / g_stutter_div => dur stutter_dur;
            stutter_dur => now;
            // No step++ here, we repeat the same step
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

    "FX_MASTER" => fx_in.setName;
    "FX_LIMIT"  => limiter.setName;
    "FX_GATE"   => safety_gate.setName;
    20 => hpf.freq;
    limiter.limit();

    100::ms => now; // Settle time
    safety_gate.forceMute();
    safety_gate.set(0.001, 0.0, 1.0, 0.001);
    0 => int gates_open;

    Echo   delay;
    JCRev  rev;
    Chorus mod;
    
    g_delay_in  => delay => fx_in;
    g_reverb_in => rev   => fx_in;
    
    // Wire Chorus as a global insert or parallel? 
    // On Deluge, Mod FX can be Chorus/Flanger/Phaser.
    // Let's use a parallel bus for now.
    global Gain g_mod_in;
    g_mod_in => mod => fx_in;

    0.3   => fx_in.gain;
    0.2   => mod.modDepth;
    0.5   => mod.modFreq;
    g_delay_time * second => delay.delay;
    g_delay_fb            => delay.gain;
    g_reverb_room         => rev.mix;

    // Refresh every 8 steps
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

// ── diagnostic heartbeat ─────────────────────────────────────────────────
fun void heartbeat_shred() {
    0 => int beats;
    while (true) {
        1::second => now;
        beats++;
        if (Machine.loglevel() >= 2) {
            <<< "HEARTBEAT beat:", beats, "step:", g_current_step,
                "BPM:", g_bpm, "play:", g_play >>>;
        }
    }
}

// ── transport shred — entry point ────────────────────────────────────────
fun void transport_shred() {
    0 => int last_play;

    spork ~ heartbeat_shred();
    spork ~ fx_bus_shred();

    while (true) {
        10::ms => now;  // poll every 10 ms

        if (g_play != last_play) {
            g_play => last_play;
            if (g_play == 1) {
                if (Machine.loglevel() >= 1) <<< "ENGINE: play" >>>;
                
                spork ~ clock_shred();
                spork ~ kit_shred();
                spork ~ synth_shred();
                
                // Allow DSP chains to settle for 100ms before opening master gates
                100::ms => now;
                
                // We'll use a broadcast event or just let shreds handle it.
                // Re-enabling explicit keyOn in shreds but WITH a delay.
            } else {
                if (Machine.loglevel() >= 1) <<< "ENGINE: stop" >>>;
                -1 => g_current_step;
                // Clock + kit will exit on next tick_event when g_play==0
                tick_event.broadcast(); 
            }
        }
    }
}

// ── boot ──────────────────────────────────────────────────────────────────
if (Machine.loglevel() >= 1) <<< "ENGINE v1.1: loaded" >>>;

// Safety: ensure critical globals have values if Java didn't pre-register them
if (g_bpm < 20.0) 120.0 => g_bpm;
if (g_master_vol <= 0.0) 0.7 => g_master_vol;
if (g_swing < 0.01) 0.5 => g_swing;

transport_shred();
