package org.chuck.deluge.midi;

import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.project.PreferencesManager;
import org.chuck.midi.MidiIn;
import org.chuck.midi.MidiMsg;

/**
 * Manages the hardware MIDI Input connection, MIDI Learn state, and the MidiEngine for the Deluge.
 *
 * <p>Delegates message dispatch to the MidiEngine while retaining MIDI Learn and device definition
 * management at this layer. Uses rtmidijava via MidiIn — no javax.sound.midi dependency.
 */
public class MidiService {
  private final ChuckVM vm;
  private final BridgeContract bridge;
  private final MidiInputRouter router;
  private final MidiEngine engine;
  private MidiIn midiIn;
  private boolean learning = false;
  private String learnTargetParam;
  private MidiDeviceDefinition currentDevice;

  public MidiService(ChuckVM vm, BridgeContract bridge, MidiInputRouter router) {
    this.vm = vm;
    this.bridge = bridge;
    this.router = router;
    this.engine = new MidiEngine();

    // Wire up engine callbacks
    engine.setOnNoteOn(this::handleNoteOn);
    engine.setOnNoteOff(this::handleNoteOff);
    engine.setOnControlChange(this::handleControlChangeFromEngine);
    engine.setOnPitchBend(this::handlePitchBend);
    engine.setOnChannelAftertouch(this::handleChannelAftertouch);
    engine.setOnSystemRealtime(this::handleSystemRealtime);

    // Wire MidiFollow into the engine's CC path
    engine.setMidiFollow(createMidiFollow());
  }

  public MidiInputRouter getRouter() {
    return router;
  }

  /** Create and configure the MidiFollow instance for this service. */
  private MidiFollow createMidiFollow() {
    MidiFollow follow = new MidiFollow();
    follow.setTakeover(new MidiTakeover());

    // Route params through the VM
    follow.setOnSetParam(
        (paramName, value) -> {
          if (vm != null && paramName != null) {
            vm.setGlobalFloat(paramName, (double) value);
            if (vm.getLogLevel() >= 2) {
              System.out.println(
                  "[MidiFollow] Set " + paramName + " = " + String.format("%.3f", value));
            }
          }
        });

    // Get live param levels from the VM
    follow.setOnGetParam(
        paramName -> {
          if (vm != null && paramName != null) {
            return (float) vm.getGlobalFloat(paramName);
          }
          return 0.5f;
        });

    // Forward unhandled CCs to the existing fallback path
    follow.setOnUnhandledCC(
        msg -> {
          String portName = PreferencesManager.get("midi.input", "None");
          fallbackCCHandler(msg.data1(), msg.data2() & 0x7F, portName);
        });

    follow.setLogLevel(vm != null ? vm.getLogLevel() : 0);
    return follow;
  }

  /** Returns the MidiEngine instance for direct access. */
  public MidiEngine getEngine() {
    return engine;
  }

  public void start() {
    String portName = PreferencesManager.get("midi.input", "None");
    if (portName.equals("None")) {
      System.out.println("MIDI: No input port selected.");
      return;
    }

    // Load device definition for this port
    String deviceId = PreferencesManager.get("midi.device." + portName, "");
    if (!deviceId.isEmpty()) {
      currentDevice = MidiDeviceDefinitionLoader.findById(deviceId);
      if (currentDevice != null) {
        System.out.println("MIDI: Loaded device definition: " + currentDevice.getName());
        router.setDeviceDefinition(currentDevice);
      }
    }

    try {
      midiIn = new MidiIn(vm);
      String[] ports = MidiIn.list();
      int portIdx = -1;
      for (int i = 0; i < ports.length; i++) {
        if (ports[i].equals(portName)) {
          portIdx = i;
          break;
        }
      }

      if (portIdx >= 0) {
        midiIn.open(portIdx);
        System.out.println("MIDI: Opened input port: " + portName);

        // Start a virtual thread to poll the MIDI queue and route through engine
        Thread.ofVirtual()
            .name("DelugeMidiReader")
            .start(
                () -> {
                  MidiMsg m = new MidiMsg();
                  while (midiIn != null) {
                    if (midiIn.recv(m)) {
                      engine.midiMessageReceived(MIDIMessage.fromMidiMsg(m), midiIn);
                    } else {
                      try {
                        Thread.sleep(5);
                      } catch (InterruptedException e) {
                        break;
                      }
                    }
                  }
                });

      } else {
        System.err.println("MIDI: Port not found: " + portName);
      }
    } catch (Exception e) {
      System.err.println("MIDI: Failed to open port: " + portName);
      e.printStackTrace();
    }
  }

  // ===================== Engine Callbacks =====================

  /** Called by engine when a Note On is received. Routes to MidiInputRouter. */
  private void handleNoteOn(MIDIMessage msg) {
    MidiMsg legacy = msg.toMidiMsg();
    router.handleMidiMessage(legacy);
  }

  /** Called by engine when a Note Off is received. Routes to MidiInputRouter. */
  private void handleNoteOff(MIDIMessage msg) {
    MidiMsg legacy = msg.toMidiMsg();
    router.handleMidiMessage(legacy);
  }

  /**
   * Called by engine when a CC is received. Delegates to MidiFollow for routing, with MIDI Learn
   * intercepted before the normal path.
   */
  private void handleControlChangeFromEngine(MIDIMessage msg) {
    int cc = msg.data1();

    // MIDI Learn intercept — take over before MidiFollow routes
    if (learning) {
      String portName = PreferencesManager.get("midi.input", "None");
      System.out.println(
          "MIDI LEARN: Bound CC " + cc + " to " + learnTargetParam + " on device " + portName);
      PreferencesManager.set("midi.learn." + portName + "." + learnTargetParam, String.valueOf(cc));
      learning = false;
      learnTargetParam = null;
      return;
    }

    // Delegate to MidiFollow for all CC routing
    if (engine.getMidiFollow() != null) {
      engine.getMidiFollow().handleCC(msg);
    }
  }

  /**
   * Fallback CC handler — used by MidiFollow when no device definition or built-in registry mapping
   * exists for a CC. Uses PreferencesManager-based learned mappings.
   */
  private void fallbackCCHandler(int cc, int val, String portName) {
    String[] keys = PreferencesManager.getKeys();
    String prefix = "midi.learn." + portName + ".";
    for (String key : keys) {
      if (key.startsWith(prefix)) {
        String mappedCc = PreferencesManager.get(key, "None");
        if (mappedCc.equals(String.valueOf(cc))) {
          String paramName = key.substring(prefix.length());
          float normalizedVal = val / 127.0f;
          vm.setGlobalFloat(paramName, normalizedVal);
          if (vm.getLogLevel() >= 2) {
            System.out.println(
                "MIDI: Updated " + paramName + " to " + normalizedVal + " via " + portName);
          }
        }
      }
    }
  }

  /** Called by engine when a Pitch Bend is received. */
  private void handlePitchBend(MIDIMessage msg) {
    // TODO: Route pitch bend to MPE zone or Sound parameter
    if (vm.getLogLevel() >= 2) {
      System.out.println("MIDI: Pitch Bend ch=" + msg.channel() + " value=" + msg.pitchBendValue());
    }
  }

  /** Called by engine when a Channel Aftertouch is received. */
  private void handleChannelAftertouch(MIDIMessage msg) {
    // TODO: Route aftertouch to Sound parameter
  }

  /** Called by engine for System Real-Time messages (clock, start, stop). */
  private void handleSystemRealtime(MIDIMessage msg) {
    if (vm == null) return;
    Object ph = vm.getGlobalObject(BridgeContract.G_PLAYBACK_HANDLER);
    if (ph instanceof org.chuck.deluge.firmware.playback.PlaybackHandler playbackHandler) {
      if (msg.isMidiStart() || msg.isMidiContinue()) {
        playbackHandler.start();
        if (vm.getLogLevel() >= 2) {
          System.out.println("[MidiService] Real-Time Transport: START");
        }
      } else if (msg.isMidiStop()) {
        playbackHandler.stop();
        if (vm.getLogLevel() >= 2) {
          System.out.println("[MidiService] Real-Time Transport: STOP");
        }
      } else if (msg.isClock()) {
        // External clock tick advance: 4 Deluge ticks per 24 PPQN MIDI clock pulse!
        // (Only active if external clock sync mode is desired - for now, always accept clock
        // ticks!)
        playbackHandler.advanceTicks(4);
      }
    }
  }

  // ===================== Legacy API (kept for backward compat, delegates to engine)
  // =====================

  /**
   * @deprecated Use engine.midiMessageReceived() directly.
   */
  @Deprecated
  private void handleMessage(MidiMsg msg) {
    engine.midiMessageReceived(MIDIMessage.fromMidiMsg(msg), midiIn);
  }

  public void stop() {
    if (midiIn != null) {
      midiIn.close();
      midiIn = null;
    }
    engine.close();
  }

  public void startLearn(String param) {
    this.learning = true;
    this.learnTargetParam = param;
    String portName = PreferencesManager.get("midi.input", "None");
    System.out.println("MIDI LEARN: Listening for CC for " + param + " on device " + portName);
  }

  public boolean isLearning() {
    return learning;
  }

  public void setRecording(boolean active) {
    router.setFollowModeEnabled(active);
  }

  public boolean isRecording() {
    return router.isFollowModeEnabled();
  }

  public void unlearn(String paramName) {
    String portName = PreferencesManager.get("midi.input", "None");
    PreferencesManager.set("midi.learn." + portName + "." + paramName, "None");
  }

  public void setDeviceDefinition(MidiDeviceDefinition def) {
    this.currentDevice = def;
    router.setDeviceDefinition(def);
    if (engine.getMidiFollow() != null) {
      engine.getMidiFollow().setDeviceDefinition(def);
    }
    String portName = PreferencesManager.get("midi.input", "None");
    PreferencesManager.set("midi.device." + portName, def != null ? def.getId() : "");
  }

  public MidiDeviceDefinition getDeviceDefinition() {
    return currentDevice;
  }

  /**
   * Returns merged mappings: device definition CCs (keyed by param name) overlaid with learn-based
   * CCs.
   */
  public java.util.Map<String, Integer> getMappings() {
    java.util.Map<String, Integer> mappings = new java.util.HashMap<>();

    // Include device definition mappings
    if (currentDevice != null) {
      for (MidiDeviceDefinition.CcMapping m : currentDevice.getCcMappings()) {
        mappings.put(m.paramName(), m.cc());
      }
    }

    // Overlay with learn-based mappings (preferences take priority)
    String portName = PreferencesManager.get("midi.input", "None");
    String prefix = "midi.learn." + portName + ".";
    String[] keys = PreferencesManager.getKeys();
    for (String key : keys) {
      if (key.startsWith(prefix)) {
        String val = PreferencesManager.get(key, "None");
        if (!val.equals("None")) {
          try {
            mappings.put(key.substring(prefix.length()), Integer.parseInt(val));
          } catch (NumberFormatException e) {
            // Ignore invalid values
          }
        }
      }
    }
    return mappings;
  }
}
