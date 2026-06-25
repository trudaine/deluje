package org.deluge.midi;

import org.deluge.BridgeContract;
import org.deluge.project.PreferencesManager;
import org.deluge.shadow.midi.MidiIn;
import org.deluge.shadow.midi.MidiMsg;
import org.deluge.ui.SwingDelugeApp;

/**
 * Manages the hardware MIDI Input connection, MIDI Learn state, and the MidiEngine for the Deluge.
 *
 * <p>Delegates message dispatch to the MidiEngine while retaining MIDI Learn and device definition
 * management at this layer. Uses rtmidijava via MidiIn — no javax.sound.midi dependency.
 */
public class MidiService {
  private final BridgeContract bridge;

  private final MidiInputRouter router;
  private final MidiEngine engine;
  private MidiIn midiIn;
  private org.deluge.shadow.midi.MidiOut midiOut;
  private final DelugeSysExManager sysExManager = new DelugeSysExManager();
  private final DelugeFileSyncService fileSyncService = new DelugeFileSyncService(sysExManager);
  private boolean learning = false;
  private String learnTargetParam;
  private MidiDeviceDefinition currentDevice;
  private int activeTrack = 4; // Default to first synth track
  private boolean disableHwSync = false;

  public MidiService(final BridgeContract bridge, MidiInputRouter router) {
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

    // Bi-directional parameter synchronization listener
    this.bridge.setParameterChangeListener(
        (paramName, trackIndex, val) -> {
          if (trackIndex == activeTrack) {
            sendParameterChangeToHardware(paramName, val);
          }
        });
  }

  public BridgeContract getBridge() {
    return bridge;
  }

  public MidiInputRouter getRouter() {
    return router;
  }

  public DelugeSysExManager getSysExManager() {
    return sysExManager;
  }

  public DelugeFileSyncService getFileSyncService() {
    return fileSyncService;
  }

  /** Create and configure the MidiFollow instance for this service. */
  private MidiFollow createMidiFollow() {
    MidiFollow follow = new MidiFollow();
    follow.setTakeover(new MidiTakeover());

    // Route params through the VM
    follow.setOnSetParam(
        (paramName, value) -> {
          if (bridge != null && paramName != null) {
            bridge.setGlobalFloat(paramName, (double) value);
            if (bridge.getLogLevel() >= 2) {
              System.out.println(
                  "[MidiFollow] Set " + paramName + " = " + String.format("%.3f", value));
            }
          }
        });

    // Get live param levels from the VM
    follow.setOnGetParam(
        paramName -> {
          if (bridge != null && paramName != null) {
            return (float) bridge.getGlobalFloat(paramName);
          }
          return 0.5f;
        });

    // Forward unhandled CCs to the existing fallback path
    follow.setOnUnhandledCC(
        msg -> {
          String portName = PreferencesManager.get("midi.input", "None");
          fallbackCCHandler(msg.data1(), msg.data2() & 0x7F, portName);
        });

    follow.setLogLevel(bridge != null ? bridge.getLogLevel() : 0);
    return follow;
  }

  /** Returns the MidiEngine instance for direct access. */
  public MidiEngine getEngine() {
    return engine;
  }

  public void setActiveTrack(int track) {
    this.activeTrack = track;
    if (router != null) {
      router.setActiveTrack(track);
    }
  }

  private org.deluge.firmware2.GlobalEffectable getActiveTrackSound(int track) {
    Object ph = bridge.getGlobalObject(BridgeContract.G_PLAYBACK_HANDLER);
    if (ph instanceof org.deluge.playback.PlaybackHandler playbackHandler) {
      org.deluge.model.ProjectModel project = playbackHandler.getProject();
      if (project != null && track >= 0 && track < project.getTracks().size()) {
        org.deluge.model.ClipModel clip = project.getTracks().get(track).getActiveClip();
        if (clip != null) {
          return (org.deluge.firmware2.GlobalEffectable) clip.getSound();
        }
      }
    }
    return null;
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

    // Load MIDI Follow settings
    boolean followEnabled = PreferencesManager.get("midi.follow.enabled", "true").equals("true");
    router.setFollowModeEnabled(followEnabled);
    char[] followLabels = {'A', 'B', 'C'};
    for (int i = 0; i < 3; i++) {
      char fLabel = followLabels[i];
      int savedCh = Integer.parseInt(PreferencesManager.get("midi.follow.ch" + fLabel, "1"));
      int savedTr =
          Integer.parseInt(PreferencesManager.get("midi.follow.track" + fLabel, String.valueOf(i)));
      router.setFollowChannel(i, savedCh - 1, savedTr);
    }

    try {
      midiIn = new MidiIn();
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
        midiIn.ignoreTypes(false, false, false);
        System.out.println("MIDI: Opened input port and allowed SysEx: " + portName);

        // Try to open matching output port for SysEx
        try {
          midiOut = new org.deluge.shadow.midi.MidiOut();
          String[] outPorts = org.deluge.shadow.midi.MidiOut.list();
          int outPortIdx = -1;
          for (int i = 0; i < outPorts.length; i++) {
            if (outPorts[i].equals(portName)) {
              outPortIdx = i;
              break;
            }
          }
          if (outPortIdx >= 0) {
            midiOut.open(outPortIdx);
            sysExManager.setMidiOut(midiOut);
            System.out.println(
                "MIDI: Automatically opened matching output port for SysEx: " + portName);
            sysExManager
                .negotiateSession("Deluge-Java Workstation")
                .thenRun(
                    () ->
                        System.out.println(
                            "MIDI: Session handshake completed. Active SID="
                                + sysExManager.getSessionId()))
                .exceptionally(
                    ex -> {
                      System.err.println(
                          "MIDI: Session negotiation failed, falling back to session 0: "
                              + ex.getMessage());
                      return null;
                    });
          }
        } catch (Exception e) {
          System.err.println(
              "MIDI: Failed to auto-open output port for SysEx: "
                  + portName
                  + " - "
                  + e.getMessage());
        }

        // Start a virtual thread to poll the MIDI queue and route through engine
        Thread.ofVirtual()
            .name("DelugeMidiReader")
            .start(
                () -> {
                  MidiMsg m = new MidiMsg();
                  java.io.ByteArrayOutputStream sysexAccumulator =
                      new java.io.ByteArrayOutputStream();
                  boolean accumulatingSysex = false;

                  while (midiIn != null) {
                    if (midiIn.recv(m)) {
                      int firstByte = m.data1 & 0xFF;
                      byte[] chunk = m.getData();

                      if (firstByte == 0xF0) {
                        sysexAccumulator.reset();
                        accumulatingSysex = true;
                        if (chunk != null) {
                          sysexAccumulator.write(chunk, 0, chunk.length);
                        }
                      } else if (accumulatingSysex) {
                        if (chunk != null) {
                          sysexAccumulator.write(chunk, 0, chunk.length);
                        }
                      }

                      if (accumulatingSysex) {
                        byte[] currentAccumulated = sysexAccumulator.toByteArray();
                        boolean isEnd =
                            (currentAccumulated.length > 0
                                && currentAccumulated[currentAccumulated.length - 1]
                                    == (byte) 0xF7);
                        if (isEnd) {
                          accumulatingSysex = false;
                          System.out.println(
                              "[MidiService] Reassembled complete SysEx message, length="
                                  + currentAccumulated.length
                                  + (currentAccumulated.length > 4
                                      ? String.format(
                                          ", header=0x%02X 0x%02X 0x%02X 0x%02X",
                                          currentAccumulated[1],
                                          currentAccumulated[2],
                                          currentAccumulated[3],
                                          currentAccumulated[4])
                                      : ""));
                          if (sysExManager.handleIncomingSysEx(currentAccumulated)) {
                            continue;
                          }
                        } else {
                          // Wait for more chunks of this SysEx message
                          continue;
                        }
                      }

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

  public void handleMidiMessage(int status, int data1, int data2) {
    org.deluge.shadow.midi.MidiMsg msg = new org.deluge.shadow.midi.MidiMsg();
    msg.status = status;
    msg.data1 = data1;
    msg.data2 = data2;
    msg.data3 = data2;
    router.handleMidiMessage(msg);
  }

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

    // 1. Intercept MPE Y-Slide (CC 74) on voice channels (> 0) for Firmware2 Sound
    if (cc == 74 && msg.channel() > 0) {
      org.deluge.firmware2.GlobalEffectable sound = getActiveTrackSound(activeTrack);
      if (sound instanceof org.deluge.firmware2.Sound s) {
        int newValue = (msg.data2() - 64) << 25;
        s.polyphonicExpressionEventOnChannelOrNote(
            newValue, 1, msg.channel(), 1); // 1 = Y_SLIDE_TIMBRE, 1 = CHANNEL
        return;
      }
    }

    // 2. Intercept physical Deluge knob turns / global CC for Cutoff / Resonance (Master Channel
    // only)
    if (cc == 74 && msg.channel() == 0) { // Cutoff
      int val = msg.data2();
      double normalized = val / 127.0;
      disableHwSync = true;
      bridge.setFilterFreq(activeTrack, normalized);
      disableHwSync = false;

      javax.swing.SwingUtilities.invokeLater(
          () -> {
            if (SwingDelugeApp.mainInstance != null) {
              SwingDelugeApp.mainInstance.refreshTrackInspector();
            }
          });
      return;
    } else if (cc == 71) { // Resonance
      int val = msg.data2();
      double normalized = val / 127.0;
      disableHwSync = true;
      bridge.setFilterRes(activeTrack, normalized);
      disableHwSync = false;

      javax.swing.SwingUtilities.invokeLater(
          () -> {
            if (SwingDelugeApp.mainInstance != null) {
              SwingDelugeApp.mainInstance.refreshTrackInspector();
            }
          });
      return;
    }

    // Delegate to MidiFollow for all CC routing
    if (engine.getMidiFollow() != null) {
      engine.getMidiFollow().handleCC(msg);
    }
  }

  /**
   * Sends a MIDI CC message back to the physical Deluge over USB MIDI when a mapped parameter is
   * updated in the desktop UI.
   *
   * @param paramName The parameter name (e.g. "cutoff" or "resonance")
   * @param value Normalized float value (0.0 to 1.0)
   */
  public void sendParameterChangeToHardware(String paramName, double value) {
    if (midiOut == null || disableHwSync) return;

    int cc = -1;
    if ("cutoff".equals(paramName)) {
      cc = 74; // Filter Cutoff
    } else if ("resonance".equals(paramName)) {
      cc = 71; // Filter Resonance
    }

    if (cc != -1) {
      int val = (int) Math.round(value * 127.0);
      val = Math.max(0, Math.min(127, val));

      // Control Change: 0xB0 (Channel 1 CC), cc, val
      byte[] data = {(byte) 0xB0, (byte) cc, (byte) val};

      org.deluge.shadow.midi.MidiMsg msg = new org.deluge.shadow.midi.MidiMsg();
      msg.setData(data);
      try {
        midiOut.send(msg);
      } catch (Exception e) {
        // Shield
      }
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
          bridge.setGlobalFloat(paramName, normalizedVal);
          if (bridge.getLogLevel() >= 2) {
            System.out.println(
                "MIDI: Updated " + paramName + " to " + normalizedVal + " via " + portName);
          }
        }
      }
    }
  }

  /** Called by engine when a Pitch Bend is received. */
  private void handlePitchBend(MIDIMessage msg) {
    int track = activeTrack;
    int bend = msg.pitchBendValue(); // 0 - 16383

    // 1. Update the bridge's step pitch for visual step grid representation
    double pitchOffset = (bend - 8192.0) / 8192.0 * 2.0; // +/- 2 semitones
    bridge.setStepPitch(track, 0, pitchOffset / 24.0);

    // 2. Route pitch bend directly to the active track's audio engine voices
    org.deluge.firmware2.GlobalEffectable sound = getActiveTrackSound(track);
    if (sound instanceof org.deluge.engine.FirmwareSound fs) {
      fs.mpePitchBend(msg.channel(), bend);
    } else if (sound instanceof org.deluge.engine.FirmwareKit kit) {
      for (org.deluge.engine.FirmwareSound drum : kit.drumSounds) {
        drum.mpePitchBend(msg.channel(), bend);
      }
    } else if (sound instanceof org.deluge.firmware2.Sound s) {
      int newValue = (bend - 8192) << 18;
      if (msg.channel() == 0) {
        s.monophonicExpressionEvent(newValue, 0); // 0 = X_PITCH_BEND
      } else {
        s.polyphonicExpressionEventOnChannelOrNote(
            newValue, 0, msg.channel(), 1); // 0 = X_PITCH_BEND, 1 = CHANNEL
      }
    }

    if (bridge.getLogLevel() >= 2) {
      System.out.println(
          "MIDI: Pitch Bend track=" + track + " ch=" + msg.channel() + " value=" + bend);
    }
  }

  /** Called by engine when a Channel Aftertouch is received. */
  private void handleChannelAftertouch(MIDIMessage msg) {
    int track = activeTrack;
    int pressure = msg.aftertouchValue(); // 0 - 127

    org.deluge.firmware2.GlobalEffectable sound = getActiveTrackSound(track);
    if (sound instanceof org.deluge.engine.FirmwareSound fs) {
      fs.mpePressure(msg.channel(), pressure);
    } else if (sound instanceof org.deluge.engine.FirmwareKit kit) {
      for (org.deluge.engine.FirmwareSound drum : kit.drumSounds) {
        drum.mpePressure(msg.channel(), pressure);
      }
    } else if (sound instanceof org.deluge.firmware2.Sound s) {
      int newValue = pressure << 24;
      if (msg.channel() == 0) {
        s.monophonicExpressionEvent(newValue, 2); // 2 = Z_PRESSURE / AFTERTOUCH
      } else {
        s.polyphonicExpressionEventOnChannelOrNote(
            newValue, 2, msg.channel(), 1); // 2 = Z_PRESSURE, 1 = CHANNEL
      }
    }

    if (bridge.getLogLevel() >= 2) {
      System.out.println(
          "MIDI: Aftertouch track=" + track + " ch=" + msg.channel() + " value=" + pressure);
    }
  }

  /** Called by engine for System Real-Time messages (clock, start, stop). */
  private void handleSystemRealtime(MIDIMessage msg) {
    if (bridge == null) return;
    Object ph = bridge.getGlobalObject(BridgeContract.G_PLAYBACK_HANDLER);
    if (ph instanceof org.deluge.playback.PlaybackHandler playbackHandler) {
      if (msg.isMidiStart() || msg.isMidiContinue()) {
        playbackHandler.start();
        if (bridge.getLogLevel() >= 2) {
          System.out.println("[MidiService] Real-Time Transport: START");
        }
      } else if (msg.isMidiStop()) {
        playbackHandler.stop();
        if (bridge.getLogLevel() >= 2) {
          System.out.println("[MidiService] Real-Time Transport: STOP");
        }
      } else if (msg.isClock()) {
        if (playbackHandler.getSyncMode() == 1) { // Only advance if EXTERNAL sync mode
          playbackHandler.advanceTicks(4);
        }
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
    if (midiOut != null) {
      try {
        sysExManager.setMidiOut(null);
        midiOut.close();
      } catch (Exception ignored) {
      }
      midiOut = null;
    }
    engine.close();
  }

  /**
   * Tears down and re-opens the MIDI connection. Used as a manual recovery path when the configured
   * port was not enumerable at app startup (USB/ALSA race) and {@code start()} hit "Port not
   * found".
   */
  public synchronized void reconnect() {
    stop();
    start();
  }

  /**
   * @return true if an output port is currently open (SysEx can be sent).
   */
  public boolean isOutputConnected() {
    return midiOut != null;
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

  public void cancelLearn() {
    this.learning = false;
    this.learnTargetParam = null;
    System.out.println("MIDI LEARN: Cancelled active learning loop hook.");
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
