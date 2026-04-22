package org.chuck.deluge.midi;

import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.project.PreferencesManager;
import org.chuck.midi.MidiIn;
import org.chuck.midi.MidiMsg;

/** Manages the hardware MIDI Input connection and MIDI Learn state for the Deluge. */
public class MidiService {
  private final ChuckVM vm;
  private final BridgeContract bridge;
  private final MidiInputRouter router;
  private MidiIn midiIn;
  private boolean learning = false;
  private String learnTargetParam;

  public MidiService(ChuckVM vm, BridgeContract bridge, MidiInputRouter router) {
    this.vm = vm;
    this.bridge = bridge;
    this.router = router;
  }

  public void start() {
    String portName = PreferencesManager.get("midi.input", "None");
    if (portName.equals("None")) {
      System.out.println("MIDI: No input port selected.");
      return;
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

        // Start a virtual thread to poll the MIDI queue
        Thread.ofVirtual()
            .name("DelugeMidiReader")
            .start(
                () -> {
                  MidiMsg m = new MidiMsg();
                  while (midiIn != null) {
                    if (midiIn.recv(m)) {
                      handleMessage(m);
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

  private void handleMessage(MidiMsg msg) {
    boolean isCc = (msg.data1 & 0xF0) == 0xB0;
    String portName = PreferencesManager.get("midi.input", "None");

    if (learning && isCc) {
      int cc = msg.data2;
      System.out.println(
          "MIDI LEARN: Bound CC " + cc + " to " + learnTargetParam + " on device " + portName);
      PreferencesManager.set("midi.learn." + portName + "." + learnTargetParam, String.valueOf(cc));
      learning = false;
      learnTargetParam = null;
    } else if (!learning && isCc) {
      int cc = msg.data2;
      int val = msg.data3;

      // Look up mapped parameters specifically for this port
      String[] keys = PreferencesManager.getKeys();
      String prefix = "midi.learn." + portName + ".";
      for (String key : keys) {
        if (key.startsWith(prefix)) {
          String mappedCc = PreferencesManager.get(key, "None");
          if (mappedCc.equals(String.valueOf(cc))) {
            String paramName = key.substring(prefix.length());
            // Map 0-127 to 0.0-1.0 (or appropriate range)
            float normalizedVal = val / 127.0f;
            vm.setGlobalFloat(paramName, normalizedVal);
            if (vm.getLogLevel() >= 2) {
              System.out.println(
                  "MIDI: Updated " + paramName + " to " + normalizedVal + " via " + portName);
            }
          }
        }
      }
    } else {
      router.handleMidiMessage(msg);
    }
  }

  public void stop() {
    if (midiIn != null) {
      midiIn.close();
      midiIn = null;
    }
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

  public void unlearn(String paramName) {
    String portName = PreferencesManager.get("midi.input", "None");
    PreferencesManager.set("midi.learn." + portName + "." + paramName, "None");
  }

  public java.util.Map<String, Integer> getMappings() {
    java.util.Map<String, Integer> mappings = new java.util.HashMap<>();
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
