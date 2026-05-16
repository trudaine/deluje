package org.chuck.deluge.midi;

import java.util.HashMap;
import java.util.Map;

/**
 * Port of the C++ midi_follow.cpp (~1394 lines). Maps incoming MIDI messages (CC, Note, Pitch Bend,
 * Aftertouch) to Sound/global parameters.
 *
 * <p>Phase A (current): CC → global float parameter mapping, device definition integration, note →
 * Sound parameter routing, MidiTakeover integration.
 *
 * <p>Phase B (extended): Feedback (send current param values back as CC), pitch bend / aftertouch →
 * parameter routing, per-clip MIDI Follow routing.
 */
public class MidiFollow {

  /**
   * A registered parameter: keyed by CC number, with the global float name and metadata used for
   * display, feedback, and takeover.
   */
  public static record ParamMapping(
      int cc, String globalName, String displayName, float min, float max, boolean bipolar) {

    public float normalize(int value) {
      float v = (value & 0x7F) / 127.0f;
      if (bipolar) v = v * 2.0f - 1.0f;
      return min + v * (max - min);
    }
  }

  // ── Built-in parameter registry: CC → global float param ──
  private final Map<Integer, ParamMapping> paramRegistry = new HashMap<>();

  // ── Device definition for the current MIDI input port ──
  private MidiDeviceDefinition currentDevice;

  // ── Takeover processor ──
  private MidiTakeover takeover;

  // ── Callbacks ──

  /** Called to set a global float parameter value (0-1 range). */
  private java.util.function.BiConsumer<String, Float> onSetParam;

  /** Called when a CC should be forwarded to the engine (unmapped / unresolved). */
  private java.util.function.Consumer<MIDIMessage> onUnhandledCC;

  /** Called when a Note On triggers a Sound parameter change. */
  private java.util.function.BiConsumer<Integer, Integer> onNoteToParam;

  // ── Logging ──
  private int logLevel = 0;

  public MidiFollow() {
    registerDefaultParams();
  }

  // ===================== Registration =====================

  /** Register all default Deluge sound params with their standard CC numbers. */
  private void registerDefaultParams() {
    // Global Sound params — see BridgeContract G_SP_* constants
    register(7, "g_sp_volume", "Volume", 0, 1, false);
    register(10, "g_sp_pan", "Pan", 0, 1, true);
    register(91, "g_sp_reverb_amount", "Reverb Amount", 0, 1, false);

    // Delay
    register(94, "g_sp_delay_rate", "Delay Rate", 0, 1, false);
    register(95, "g_sp_delay_feedback", "Delay Feedback", 0, 1, false);

    // Sidechain / Stutter
    register(102, "g_sp_sidechain_shape", "Sidechain Shape", 0, 1, false);
    register(103, "g_sp_stutter_rate", "Stutter Rate", 0, 1, false);

    // Bitcrush / Sample rate reduction
    register(104, "g_sp_srr", "Sample Rate Reduction", 0, 1, false);
    register(105, "g_sp_bitcrush", "Bitcrush", 0, 1, false);

    // Modulation FX
    register(106, "g_sp_mod_fx_rate", "Mod FX Rate", 0, 1, false);
    register(107, "g_sp_mod_fx_depth", "Mod FX Depth", 0, 1, false);
    register(108, "g_sp_mod_fx_offset", "Mod FX Offset", 0, 1, false);
    register(109, "g_sp_mod_fx_feedback", "Mod FX Feedback", 0, 1, false);

    // Compressor
    register(110, "g_sp_comp_threshold", "Compressor Threshold", 0, 1, false);

    // Filters
    register(71, "g_sp_lpf_freq", "LPF Frequency", 0, 1, false);
    register(72, "g_sp_lpf_res", "LPF Resonance", 0, 1, false);
    register(74, "g_sp_lpf_morph", "LPF Morph", 0, 1, false);
    register(75, "g_sp_hpf_freq", "HPF Frequency", 0, 1, false);
    register(76, "g_sp_hpf_res", "HPF Resonance", 0, 1, false);
    register(77, "g_sp_hpf_morph", "HPF Morph", 0, 1, false);

    // EQ
    register(80, "g_sp_eq_bass", "EQ Bass", 0, 1, false);
    register(81, "g_sp_eq_treble", "EQ Treble", 0, 1, false);
    register(82, "g_sp_eq_bass_freq", "EQ Bass Freq", 0, 1, false);
    register(83, "g_sp_eq_treble_freq", "EQ Treble Freq", 0, 1, false);
  }

  /**
   * Register a custom param mapping.
   *
   * @param cc the MIDI CC number (0-127)
   * @param globalName the global float name used with vm.setGlobalFloat()
   * @param displayName human-readable name for UI
   * @param min minimum parameter value (after normalization)
   * @param max maximum parameter value (after normalization)
   * @param bipolar true if CC 64 is center (e.g. pan)
   */
  public void register(
      int cc, String globalName, String displayName, float min, float max, boolean bipolar) {
    if (cc < 0 || cc > 127) throw new IllegalArgumentException("CC out of range: " + cc);
    paramRegistry.put(cc, new ParamMapping(cc, globalName, displayName, min, max, bipolar));
  }

  /** Remove a registered mapping. */
  public void unregister(int cc) {
    paramRegistry.remove(cc);
  }

  /** Look up a registered param by CC number. */
  public ParamMapping getMapping(int cc) {
    return paramRegistry.get(cc);
  }

  // ===================== Configuration =====================

  public void setDeviceDefinition(MidiDeviceDefinition def) {
    this.currentDevice = def;
  }

  public MidiDeviceDefinition getDeviceDefinition() {
    return currentDevice;
  }

  public void setTakeover(MidiTakeover takeover) {
    this.takeover = takeover;
  }

  public void setLogLevel(int level) {
    this.logLevel = level;
  }

  // ===================== Callback Registration =====================

  public void setOnSetParam(java.util.function.BiConsumer<String, Float> callback) {
    this.onSetParam = callback;
  }

  public void setOnUnhandledCC(java.util.function.Consumer<MIDIMessage> callback) {
    this.onUnhandledCC = callback;
  }

  public void setOnNoteToParam(java.util.function.BiConsumer<Integer, Integer> callback) {
    this.onNoteToParam = callback;
  }

  // ===================== CC Handling =====================

  /**
   * Handle an incoming Control Change message. Routes to the appropriate parameter using:
   *
   * <ol>
   *   <li>Device definition mapping (highest priority)
   *   <li>Built-in param registry
   *   <li>Unhandled callback (lowest priority, e.g. for engine-side forwarding)
   * </ol>
   *
   * MidiTakeover is applied before the parameter is set.
   */
  public void handleCC(MIDIMessage msg) {
    int cc = msg.data1();
    int rawValue = msg.data2();

    if (logLevel >= 2) {
      System.out.println("[MidiFollow] CC " + cc + " = " + rawValue + " ch=" + msg.channel());
    }

    // 1. Apply takeover
    int effectiveValue = rawValue;
    if (takeover != null) {
      effectiveValue = takeover.process(cc, rawValue);
      if (effectiveValue < 0) {
        if (logLevel >= 2) {
          System.out.println("[MidiFollow] Takeover blocked CC " + cc);
        }
        return; // Takeover says ignore this message
      }
    }

    // 2. Check device definition mapping (highest priority)
    if (currentDevice != null) {
      MidiDeviceDefinition.CcMapping mapping = currentDevice.findMapping(cc);
      if (mapping != null) {
        float normalized = (effectiveValue & 0x7F) / 127.0f;
        if (onSetParam != null) {
          onSetParam.accept(mapping.paramName(), normalized);
        }
        if (logLevel >= 1) {
          System.out.println(
              "[MidiFollow] Device "
                  + currentDevice.getName()
                  + " → "
                  + mapping.paramName()
                  + " = "
                  + String.format("%.3f", normalized));
        }
        return;
      }
    }

    // 3. Check built-in param registry
    ParamMapping pm = paramRegistry.get(cc);
    if (pm != null) {
      float value = pm.normalize(effectiveValue);
      if (onSetParam != null) {
        onSetParam.accept(pm.globalName(), value);
      }
      if (logLevel >= 1) {
        System.out.println(
            "[MidiFollow] " + pm.displayName() + " = " + String.format("%.3f", value));
      }
      return;
    }

    // 4. Unhandled — forward to engine
    if (onUnhandledCC != null) {
      onUnhandledCC.accept(msg);
    }
  }

  // ===================== Note Handling =====================

  /**
   * Handle an incoming Note On/Off for MIDI Follow param mapping.
   *
   * <p>In MIDI Follow mode, notes on specific channels can be mapped to Sound parameters (e.g.,
   * using a keyboard's lowest octave to control filter cutoff).
   *
   * <p>TODO: Note → Sound parameter routing (Phase B).
   */
  public void handleNote(MIDIMessage msg) {
    if (onNoteToParam != null) {
      onNoteToParam.accept(msg.data1(), msg.data2());
    }
    // Phase A: no-op; notes are routed by MidiInputRouter
  }

  // ===================== Pitch Bend =====================

  /**
   * Handle an incoming Pitch Bend for MIDI Follow param mapping.
   *
   * <p>TODO: Pitch bend → parameter routing (Phase B).
   */
  public void handlePitchBend(MIDIMessage msg) {
    // Reserved for Phase B
  }

  // ===================== Aftertouch =====================

  /**
   * Handle incoming Channel Aftertouch for MIDI Follow param mapping.
   *
   * <p>TODO: Aftertouch → parameter routing (Phase B).
   */
  public void handleChannelAftertouch(MIDIMessage msg) {
    // Reserved for Phase B
  }

  /**
   * Handle incoming Polyphonic Aftertouch for MIDI Follow param mapping.
   *
   * <p>TODO: Poly aftertouch → parameter routing (Phase B).
   */
  public void handlePolyAftertouch(MIDIMessage msg) {
    // Reserved for Phase B
  }
}
