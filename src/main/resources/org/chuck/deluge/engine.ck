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

// Global FX parameters (scalars)
global float g_delay_time;
global float g_delay_fb;
global float g_reverb_room;
global float g_reverb_damp;

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

// ── kit shred ─────────────────────────────────────────────────────────────
fun void kit_shred() {
    // Drum samples (SndBuf per track)
    SndBuf kit[8];
    Gain   master => dac;

    // Load samples
    "examples/data/kick.wav"                              => kit[0].read;
    "examples/data/snare.wav"                             => kit[1].read;
    "examples/data/hihat.wav"                             => kit[2].read;
    "examples/data/hihat-open.wav"                        => kit[3].read;
    "examples/book/digital-artists/audio/clap_01.wav"    => kit[4].read;
    "examples/book/digital-artists/audio/cowbell_01.wav" => kit[5].read;
    "examples/book/digital-artists/audio/click_01.wav"   => kit[6].read;
    "examples/data/snare-hop.wav"                         => kit[7].read;

    for (0 => int i; i < 8; i++) {
        kit[i] => master;
        kit[i].samples() => kit[i].pos; // silence on load
    }

    // Park volume at master
    g_master_vol => master.gain;

    while (g_play != 0) {
        tick_event => now;
        if (g_play == 0) break;

        // FETCH GLOBALS DYNAMICALLY (prevents stale refs on hot-reload)
        Machine.getGlobalObject("g_pattern") $ int[] @=> int pattern[];
        Machine.getGlobalObject("g_velocity") $ float[] @=> float velocity[];
        Machine.getGlobalObject("g_probability") $ float[] @=> float probability[];
        Machine.getGlobalObject("g_mute") $ int[] @=> int mute[];

        if (pattern == null || velocity == null || probability == null || mute == null) continue;

        // Re-read master vol each step
        g_master_vol => master.gain;

        int step;
        g_current_step % 16 => step;

        for (0 => int r; r < 8; r++) {
            // Skip muted tracks
            if (mute[r] != 0) continue;

            // Probability gate
            if (Math.random2f(0.0, 1.0) > probability[r]) continue;

            // Pattern check
            r * 16 + step => int idx;
            if (pattern[idx] == 0) continue;

            // Velocity
            velocity[idx] => float vel;

            // Trigger sample
            0 => kit[r].pos;
            vel * 0.8 => kit[r].gain;

            if (Machine.loglevel() >= 2) {
                <<< "KIT trigger track:", r, "step:", step, "vel:", vel >>>;
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
        step % 16 => g_current_step;
        tick_event.broadcast();

        if (Machine.loglevel() >= 2 && step % 16 == 0) {
            <<< "CLOCK: beat", step / 16, "BPM:", g_bpm, "swing:", g_swing >>>;
        }

        stepDuration(step % 2) => now;
        step++;
    }
}

// ── fx_bus shred ─────────────────────────────────────────────────────────
fun void fx_bus_shred() {
    Gain   fx_in => dac;
    Echo   delay => fx_in;
    JCRev  rev   => fx_in;

    0.3   => fx_in.gain;
    g_delay_time * second => delay.delay;
    g_delay_fb            => delay.mix;
    g_reverb_room         => rev.mix;

    // Refresh every 8 steps
    while (true) {
        tick_event => now;
        g_delay_time * second => delay.delay;
        g_delay_fb            => delay.mix;
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
