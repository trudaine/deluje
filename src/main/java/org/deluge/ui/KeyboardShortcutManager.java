package org.deluge.ui;

import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.UnsupportedAudioFileException;
import org.deluge.BridgeContract;
import org.deluge.shadow.core.ChuckArray;
import org.deluge.shadow.core.ChuckEvent;
import org.deluge.shadow.hid.HidMsg;

/**
 * Controller class managing application keyboard shortcuts, isomorphic keyboard notes, and
 * forwarding raw input events to the native simulation bridge.
 */
public class KeyboardShortcutManager extends KeyAdapter {
  private final SwingDelugeApp app;
  private final BridgeContract bridge;

  public KeyboardShortcutManager(SwingDelugeApp app) {
    this.app = app;
    this.bridge = app.bridge;
  }

  @Override
  public void keyPressed(KeyEvent e) {
    int kc = e.getKeyCode();
    boolean ctrl = (e.getModifiersEx() & KeyEvent.CTRL_DOWN_MASK) != 0;
    boolean shift = (e.getModifiersEx() & KeyEvent.SHIFT_DOWN_MASK) != 0;

    // 1. Isomorphic keyboard play (Z, S, X, D, C, V, G, B, H, N, J, M)
    int note = -1;
    if (!ctrl) {
      switch (kc) {
        case KeyEvent.VK_Z -> note = 60; // C4
        case KeyEvent.VK_S -> note = 61; // C#4
        case KeyEvent.VK_X -> note = 62; // D4
        case KeyEvent.VK_D -> note = 63; // D#4
        case KeyEvent.VK_C -> note = 64; // E4
        case KeyEvent.VK_V -> note = 65; // F4
        case KeyEvent.VK_G -> note = 66; // F#4
        case KeyEvent.VK_B -> note = 67; // G4
        case KeyEvent.VK_H -> note = 68; // G#4
        case KeyEvent.VK_N -> note = 69; // A4
        case KeyEvent.VK_J -> note = 70; // A#4
        case KeyEvent.VK_M -> note = 71; // B4
      }
    }

    if (note != -1) {
      playIsomorphicNote(note);
      return;
    }

    // 2. Control Shortcuts

    // Ctrl+Shift+C / Ctrl+Shift+V — copy / paste clip notes
    if (ctrl && shift && kc == KeyEvent.VK_C) {
      SwingGridPanel active = app.activeGridPanel();
      if (active != null) {
        active.copyClipNotes();
      }
      return;
    }
    if (ctrl && shift && kc == KeyEvent.VK_V) {
      SwingGridPanel active = app.activeGridPanel();
      if (active != null) {
        active.pasteClipNotes();
      }
      return;
    }

    // Ctrl+[ / Ctrl+] — adjust focused track length
    if (ctrl && kc == KeyEvent.VK_OPEN_BRACKET) {
      SwingGridPanel active = app.activeGridPanel();
      if (active != null) {
        int trk = active.getFocusTrack();
        int len = bridge.getTrackLength(trk);
        bridge.setTrackLength(trk, Math.max(1, len - 1));
        active.refresh();
      }
      return;
    }
    if (ctrl && kc == KeyEvent.VK_CLOSE_BRACKET) {
      SwingGridPanel active = app.activeGridPanel();
      if (active != null) {
        int trk = active.getFocusTrack();
        int len = bridge.getTrackLength(trk);
        bridge.setTrackLength(trk, Math.min(64, len + 1));
        active.refresh();
      }
      return;
    }

    // Alt + Left/Right — shift all notes in the focused clip sideways one step (wraps around)
    boolean alt = (e.getModifiersEx() & KeyEvent.ALT_DOWN_MASK) != 0;
    if (alt && (kc == KeyEvent.VK_LEFT || kc == KeyEvent.VK_RIGHT)) {
      SwingGridPanel active = app.activeGridPanel();
      if (active != null) {
        active.shiftActiveClipNotes(kc == KeyEvent.VK_RIGHT ? 1 : -1);
      }
      return;
    }

    // T — tap tempo (or Shift+T to toggle metronome)
    if (!ctrl && kc == KeyEvent.VK_T) {
      if (shift) {
        app.transportController.toggleMetronome();
      } else {
        app.transportController.tapTempo();
      }
      return;
    }

    // Q — Stutter live step repeating (momentary)
    if (!ctrl && kc == KeyEvent.VK_Q) {
      if (app.getTopBar() != null) {
        app.getTopBar().setStutterActive(true);
      }
      return;
    }

    // 3. Dispatch other key events to native simulation
    HidMsg msg = new HidMsg();
    msg.deviceType = "keyboard";
    msg.type = HidMsg.BUTTON_DOWN;
    msg.which = kc;
    msg.key = kc;
    char c = e.getKeyChar();
    if (c != KeyEvent.CHAR_UNDEFINED) {
      msg.ascii = c;
    }
    bridge.dispatchHidMsg(msg);
  }

  @Override
  public void keyReleased(KeyEvent e) {
    int kc = e.getKeyCode();
    if (kc == KeyEvent.VK_Q) {
      if (app.getTopBar() != null) {
        app.getTopBar().setStutterActive(false);
      }
    }
    HidMsg msg = new HidMsg();
    msg.deviceType = "keyboard";
    msg.type = HidMsg.BUTTON_UP;
    msg.which = e.getKeyCode();
    msg.key = e.getKeyCode();
    bridge.dispatchHidMsg(msg);
  }

  private void playIsomorphicNote(int note) {
    if (app.clipPanel == null) {
      return;
    }
    app.clipPanel.flashIsomorphicNote(note);
    int trackId = app.clipPanel.getFocusTrack();

    boolean isSynth =
        app.clipPanel.getProjectModel() != null
            && !app.clipPanel.getProjectModel().getTracks().isEmpty()
            && app.clipPanel.getProjectModel().getTracks().get(0)
                instanceof org.deluge.model.SynthTrackModel;

    if (isSynth) {
      try {
        ChuckEvent noteEv = (ChuckEvent) bridge.getGlobalObject("g_ck_noteOn");
        if (noteEv != null) {
          ChuckArray pitchArr = (ChuckArray) bridge.getGlobalObject(BridgeContract.G_PITCH);
          pitchArr.setInt(0, (long) (note - 60));
          noteEv.broadcast();
        }
      } catch (Exception ex) {
        // ignore
      }
    } else {
      String sp = (String) bridge.getGlobalObject("g_sample_" + trackId);
      if (sp != null && !sp.isEmpty()) {
        new Thread(
                () -> {
                  try {
                    File file = new File(sp);
                    if (file.exists()) {
                      AudioInputStream stream = AudioSystem.getAudioInputStream(file);
                      Clip c = AudioSystem.getClip();
                      c.open(stream);
                      c.start();
                    }
                  } catch (IOException
                      | LineUnavailableException
                      | UnsupportedAudioFileException ex) {
                    // ignore
                  }
                })
            .start();
      }
    }
  }
}
