package org.deluge.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JSlider;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import org.deluge.BridgeContract;
import org.deluge.firmware.hid.FirmwareDisplay;
import org.deluge.midi.DelugeSysExManager;
import org.deluge.model.ProjectModel;
import org.deluge.ui.controls.DelugeEncoderKnob;
import org.deluge.ui.controls.DelugeParamReadout;

/**
 * Top toolbar panel with view mode toggles, track add buttons, transport controls, and master
 * sliders. Uses FlowLayout so controls wrap to multiple rows when the window is too narrow to fit
 * them on one line.
 */
public class SwingTopBarPanel extends JPanel {

  private JButton recBtn;
  private JButton resampleBtn;
  private JToggleButton captureBtn;
  public static boolean isAffectEntireActive = false;

  public void stopRecordingIfActive() {
    if (org.deluge.engine.JavaAudioDriver.isResamplingActive) {
      if (resampleBtn != null) {
        for (java.awt.event.ActionListener al : resampleBtn.getActionListeners()) {
          al.actionPerformed(
              new java.awt.event.ActionEvent(
                  resampleBtn, java.awt.event.ActionEvent.ACTION_PERFORMED, ""));
        }
      }
    }
  }

  /** Callback interface for actions that need to reach the parent frame. */
  public interface TopBarListener {
    void onLiveRecordToggle(JButton btn);

    void onResampleToggle(JButton btn);

    void onArrangerCaptureToggle(boolean active);

    void onViewModeChanged(String viewMode);

    void onAddTrack(String type, boolean isShift);

    void onPlayToggle();

    void onStop();

    void onMasterVolumeChanged(float vol);
  }

  private final ProjectModel projectModel;
  private final BridgeContract bridge;

  private final JToggleButton clipBtn;
  private final JToggleButton songBtn;
  private final JToggleButton arrBtn;
  private final JToggleButton autoBtn;
  private final JToggleButton perfBtn;
  private final JToggleButton keyplayBtn;
  private final JSlider masterVolSlider;
  private final TopBarListener listener;
  private final DelugeParamReadout paramReadout;
  private final SwingOledPanel oledPanel;

  private String currentViewMode = "CLIP";
  private boolean isSaved = false;
  private final java.util.List<Long> tapTimes = new java.util.ArrayList<>();

  public void setSaved(boolean saved) {
    this.isSaved = saved;
  }

  public DelugeParamReadout getParamReadout() {
    return paramReadout;
  }

  /** Convert the edited track of the active grid to Synth (true) or MIDI (false). */
  private void convertEditedTrack(boolean toSynth) {
    if (SwingDelugeApp.mainInstance == null) {
      return;
    }
    SwingGridPanel active = SwingDelugeApp.mainInstance.getActiveGridPanel();
    if (active == null) {
      return;
    }
    int trk = active.getEditedModelTrack();
    boolean done = toSynth ? active.convertTrackToSynth(trk) : active.convertTrackToMidi(trk);
    paramReadout.printTransient("TYPE", done ? (toSynth ? "SYNTH" : "MIDI") : "—");
  }

  /** Toggle the engine metronome (SHIFT+TAP), reflecting state on the readout. */
  private void toggleMetronome() {
    Object eng = bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
    if (eng instanceof org.deluge.firmware.engine.FirmwareAudioEngine engine) {
      engine.metronomeEnabled = !engine.metronomeEnabled;
      paramReadout.printTransient("METRO", engine.metronomeEnabled ? "ON" : "OFF");
    }
  }

  public SwingOledPanel getOledPanel() {
    return oledPanel;
  }

  /**
   * @param bridge ChucK virtual machine for direct bridge writes
   * @param bridge active project real-time bridge
   * @param projectModel current project model (used for track count in dialogs)
   * @param leftFloat the explorer JDialog toggled by the EXPLORER button
   * @param listener callback for view-mode changes and track additions
   */
  public SwingTopBarPanel(
      final BridgeContract bridge,
      ProjectModel projectModel,
      JDialog leftFloat,
      TopBarListener listener) {
    this.projectModel = projectModel;
    this.bridge = bridge;

    this.listener = listener;
    this.paramReadout = new DelugeParamReadout();
    this.oledPanel = new SwingOledPanel();

    // Wire physical hardware OLED stream directly into our virtual OLED panel
    if (SwingDelugeApp.mainInstance != null
        && SwingDelugeApp.mainInstance.getMidiService() != null) {
      SwingDelugeApp.mainInstance
          .getMidiService()
          .getSysExManager()
          .setDisplayListener(
              new DelugeSysExManager.DisplayListener() {
                @Override
                public void onOledFrame(byte[] frameBuffer) {
                  oledPanel.drawRawFrameBuffer(frameBuffer);
                }

                @Override
                public void onSevenSegment(String text) {
                  paramReadout.printTransient("7SEG", text);
                }
              });
    }

    setLayout(new WrapLayout());
    setBackground(new Color(0x12, 0x12, 0x14));

    // ── View mode toggles styled as macOS tab segments ──

    clipBtn = new JToggleButton("CLIP", true);
    songBtn = new JToggleButton("SONG");
    arrBtn = new JToggleButton("ARR");
    autoBtn = new JToggleButton("AUTO");
    perfBtn = new JToggleButton("PERF");
    keyplayBtn = new JToggleButton("KEYPLAY");
    ButtonGroup modeGroup = new ButtonGroup();
    modeGroup.add(clipBtn);
    modeGroup.add(songBtn);
    modeGroup.add(arrBtn);
    modeGroup.add(autoBtn);
    modeGroup.add(perfBtn);
    modeGroup.add(keyplayBtn);

    // Initial static styling
    initTabStyles(clipBtn);
    initTabStyles(songBtn);
    initTabStyles(arrBtn);
    initTabStyles(autoBtn);
    initTabStyles(perfBtn);
    initTabStyles(keyplayBtn);

    clipBtn.setToolTipText(
        "CLIP View: Click 1 time for Sequence Grid / Click 2 times for Automation View");
    songBtn.setToolTipText(
        "SONG View: Click 1 time for Clip Launch / Click 2 times for Arrangement View");
    arrBtn.setToolTipText(
        "ARRANGEMENT View: Click 1 time for Linear Timeline / Click 2 times for Song View");
    autoBtn.setToolTipText(
        "AUTOMATION View: Click 1 time for Step Automation / Click 2 times for Clip View");
    perfBtn.setToolTipText(
        "PERFORMANCE View: Click 1 time for Live Stutter and Mute Punch effects");
    keyplayBtn.setToolTipText(
        "KEYBOARD Play Mode: Play the track synthesizer instrument live using an isomorphic grid layout");

    // Initial dynamic styling
    updateTabStyles(clipBtn, songBtn, arrBtn, autoBtn, perfBtn, keyplayBtn);

    clipBtn.addActionListener(
        e -> {
          if ("CLIP".equals(currentViewMode)) {
            autoBtn.setSelected(true);
            currentViewMode = "AUTO";
            updateTabStyles(clipBtn, songBtn, arrBtn, autoBtn, perfBtn, keyplayBtn);
            listener.onViewModeChanged("AUTO");
          } else {
            currentViewMode = "CLIP";
            updateTabStyles(clipBtn, songBtn, arrBtn, autoBtn, perfBtn, keyplayBtn);
            listener.onViewModeChanged("CLIP");
          }
        });
    songBtn.addActionListener(
        e -> {
          if ("SONG".equals(currentViewMode)) {
            arrBtn.setSelected(true);
            currentViewMode = "ARR";
            updateTabStyles(clipBtn, songBtn, arrBtn, autoBtn, perfBtn, keyplayBtn);
            listener.onViewModeChanged("ARR");
          } else {
            currentViewMode = "SONG";
            updateTabStyles(clipBtn, songBtn, arrBtn, autoBtn, perfBtn, keyplayBtn);
            listener.onViewModeChanged("SONG");
            String saveStatus = isSaved ? "SONG" : "UNSAVED";
            String keyStr = projectModel.getKey().toUpperCase();
            if (keyStr.isEmpty() || keyStr.equals("NONE")) keyStr = "C";
            paramReadout.scrollMessage(
                saveStatus
                    + "   "
                    + keyStr
                    + "-2 MAJOR   "
                    + ((int) projectModel.getBpm())
                    + " BPM");

            try {
              FirmwareDisplay.get()
                  .getVirtualOLED()
                  .drawThreeLineDisplay(
                      saveStatus, keyStr + "-2 MAJOR", ((int) projectModel.getBpm()) + " BPM");
              if (oledPanel != null) oledPanel.repaint();
            } catch (Throwable t) {
              // Shield for headless test environments
            }
          }
        });
    arrBtn.addActionListener(
        e -> {
          if ("ARR".equals(currentViewMode)) {
            songBtn.setSelected(true);
            currentViewMode = "SONG";
            updateTabStyles(clipBtn, songBtn, arrBtn, autoBtn, perfBtn, keyplayBtn);
            listener.onViewModeChanged("SONG");
          } else {
            currentViewMode = "ARR";
            updateTabStyles(clipBtn, songBtn, arrBtn, autoBtn, perfBtn, keyplayBtn);
            listener.onViewModeChanged("ARR");
          }
        });
    autoBtn.addActionListener(
        e -> {
          if ("AUTO".equals(currentViewMode)) {
            clipBtn.setSelected(true);
            currentViewMode = "CLIP";
            updateTabStyles(clipBtn, songBtn, arrBtn, autoBtn, perfBtn, keyplayBtn);
            listener.onViewModeChanged("CLIP");
          } else {
            currentViewMode = "AUTO";
            updateTabStyles(clipBtn, songBtn, arrBtn, autoBtn, perfBtn, keyplayBtn);
            listener.onViewModeChanged("AUTO");
          }
        });
    perfBtn.addActionListener(
        e -> {
          currentViewMode = "PERF";
          updateTabStyles(clipBtn, songBtn, arrBtn, autoBtn, perfBtn, keyplayBtn);
          listener.onViewModeChanged("PERF");
        });
    keyplayBtn.addActionListener(
        e -> {
          currentViewMode = "KEYPLAY";
          updateTabStyles(clipBtn, songBtn, arrBtn, autoBtn, perfBtn, keyplayBtn);
          listener.onViewModeChanged("KEYPLAY");
        });

    add(clipBtn);
    add(songBtn);
    add(arrBtn);
    add(autoBtn);
    add(perfBtn);
    add(keyplayBtn);
    add(new JSeparator(JSeparator.VERTICAL));

    // ── Track add buttons with high contrast and custom themes ──

    JButton addKitBtn = new JButton("+ KIT");
    styleButton(addKitBtn, new Color(0x1e, 0x32, 0x32), new Color(0x00, 0xff, 0xcc));
    addKitBtn.setToolTipText(
        "Click to add Kit (prompts name) / Shift-Click to instantly create blank KIT");
    addKitBtn.addActionListener(
        e -> {
          boolean isShift = (e.getModifiers() & java.awt.event.ActionEvent.SHIFT_MASK) != 0;
          listener.onAddTrack("KIT", isShift);
        });

    JButton addSynthBtn = new JButton("+ SYNTH");
    styleButton(addSynthBtn, new Color(0x32, 0x1e, 0x32), new Color(0xff, 0x33, 0xcc));
    addSynthBtn.setToolTipText(
        "Click to add Synth (prompts name) / Shift-Click to instantly create default SYNTH");
    addSynthBtn.addActionListener(
        e -> {
          boolean isShift = (e.getModifiers() & java.awt.event.ActionEvent.SHIFT_MASK) != 0;
          listener.onAddTrack("SYNTH", isShift);
        });

    JButton addAudioBtn = new JButton("+ AUDIO");
    styleButton(addAudioBtn, new Color(0x1e, 0x32, 0x22), new Color(0x33, 0xff, 0x33));
    addAudioBtn.setToolTipText(
        "Click to add Audio track (prompts name) / Shift-Click to create instant AUDIO");
    addAudioBtn.addActionListener(
        e -> {
          boolean isShift = (e.getModifiers() & java.awt.event.ActionEvent.SHIFT_MASK) != 0;
          listener.onAddTrack("AUDIO", isShift);
        });

    add(addKitBtn);
    add(addSynthBtn);
    add(addAudioBtn);
    add(new JSeparator(JSeparator.VERTICAL));

    // ── Explorer toggle ──

    JButton btnExplorer = new JButton("EXPLORER");
    styleButton(btnExplorer, new Color(0x23, 0x23, 0x28), Color.WHITE);
    btnExplorer.addActionListener(e -> leftFloat.setVisible(!leftFloat.isVisible()));
    add(btnExplorer);

    add(new JSeparator(JSeparator.VERTICAL));

    // ── Transport ──

    JButton playBtn = new JButton("\u25B6 PLAY");
    styleButton(playBtn, new Color(0x1a, 0x4a, 0x1a), new Color(0x00, 0xff, 0x66));
    playBtn.addActionListener(
        e -> {
          listener.onPlayToggle();
          paramReadout.printTransient("PLAY", "ON");
        });
    add(playBtn);

    JButton stopBtn = new JButton("\u25A0 STOP");
    styleButton(stopBtn, new Color(0x4a, 0x1a, 0x1a), new Color(0xff, 0x33, 0x33));
    stopBtn.addActionListener(
        e -> {
          listener.onStop();
          paramReadout.printTransient("STOP", "OFF");
        });
    add(stopBtn);

    recBtn = new JButton("\u25CF REC");
    styleButton(recBtn, new Color(0x3a, 0x0c, 0x0c), new Color(0xff, 0x33, 0x33));
    recBtn.addActionListener(e -> listener.onLiveRecordToggle(recBtn));
    add(recBtn);

    resampleBtn = new JButton("\u25CF RESAMPLE");
    styleButton(resampleBtn, new Color(0x3e, 0x27, 0x0c), new Color(0xff, 0xb3, 0x00));
    resampleBtn.addActionListener(e -> listener.onResampleToggle(resampleBtn));
    add(resampleBtn);

    captureBtn = new JToggleButton("\u25CF CAPTURE");
    styleButton(captureBtn, new Color(0x2a, 0x0a, 0x0a), new Color(0xff, 0x00, 0x55));
    captureBtn.addActionListener(
        e -> {
          boolean active = captureBtn.isSelected();
          listener.onArrangerCaptureToggle(active);
          paramReadout.printTransient("CAP ", active ? "ON" : "OFF");
        });
    add(captureBtn);

    // Physical Deluge Hardware Status Panel
    if (SwingDelugeApp.mainInstance != null
        && SwingDelugeApp.mainInstance.getMidiService() != null) {
      DelugeHwStatusPanel hwStatus =
          new DelugeHwStatusPanel(SwingDelugeApp.mainInstance.getMidiService());
      add(hwStatus);
    }

    add(new JSeparator(JSeparator.VERTICAL));

    // ── Sliders ──

    JLabel bpmLabel = new JLabel("BPM:");
    bpmLabel.setForeground(Color.WHITE);
    bpmLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
    add(bpmLabel);

    JSlider bpmSlider = new JSlider(60, 200, (int) projectModel.getBpm());

    JButton tapBtn = new JButton("TAP");
    styleButton(tapBtn, new Color(0x2d, 0x2d, 0x3d), Color.WHITE);
    tapBtn.setPreferredSize(new Dimension(42, 22));
    tapBtn.setMargin(new Insets(0, 0, 0, 0));
    tapBtn.setFont(new Font("SansSerif", Font.BOLD, 10));
    tapBtn.setFocusable(false);
    tapBtn.setToolTipText("TAP tempo. Shift+click toggles the metronome.");
    tapBtn.addActionListener(
        e -> {
          if ((e.getModifiers() & java.awt.event.ActionEvent.SHIFT_MASK) != 0) {
            toggleMetronome();
            return;
          }
          long now = System.currentTimeMillis();
          // Start a fresh tap sequence if it's been too long since the last tap, so a stale
          // tap from minutes ago can't drag the average down (mirrors hardware tap-tempo reset).
          if (!tapTimes.isEmpty() && (now - tapTimes.get(tapTimes.size() - 1)) > 2000) {
            tapTimes.clear();
          }
          tapTimes.add(now);
          if (tapTimes.size() > 4) {
            tapTimes.remove(0);
          }
          if (tapTimes.size() >= 2) {
            long sum = 0;
            for (int i = 1; i < tapTimes.size(); i++) {
              sum += (tapTimes.get(i) - tapTimes.get(i - 1));
            }
            long avgMs = sum / (tapTimes.size() - 1);
            if (avgMs > 0) {
              int bpm = (int) (60000.0 / avgMs);
              if (bpm >= 60 && bpm <= 200) {
                projectModel.setBpm(bpm);
                bpmSlider.setValue(bpm);
                paramReadout.printTransient("TEM ", String.valueOf(bpm));
              }
            }
          }
        });
    add(tapBtn);

    JToggleButton affectEntireBtn = new JToggleButton("ALL");
    styleButton(affectEntireBtn, new Color(0x3d, 0x23, 0x23), new Color(0xff, 0x55, 0x55));
    affectEntireBtn.setPreferredSize(new Dimension(42, 22));
    affectEntireBtn.setMargin(new Insets(0, 0, 0, 0));
    affectEntireBtn.setFont(new Font("SansSerif", Font.BOLD, 10));
    affectEntireBtn.setFocusable(false);
    affectEntireBtn.setToolTipText(
        "Affect Entire: on a Kit, a parameter tweak applies to every drum in the kit (hardware"
            + " behaviour). Has no effect on Synth tracks.");
    affectEntireBtn.addActionListener(
        e -> {
          isAffectEntireActive = affectEntireBtn.isSelected();
          paramReadout.printTransient("ALL ", isAffectEntireActive ? "ON" : "OFF");
        });
    add(affectEntireBtn);

    // SCALE button: cycle the song scale (Deluge SCALE / SHIFT+SCALE), updating grid colouring.
    JButton scaleBtn = new JButton("SCALE");
    styleButton(scaleBtn, new Color(0x23, 0x2d, 0x3d), new Color(0x66, 0xcc, 0xff));
    scaleBtn.setPreferredSize(new Dimension(52, 22));
    scaleBtn.setMargin(new Insets(0, 0, 0, 0));
    scaleBtn.setFont(new Font("SansSerif", Font.BOLD, 10));
    scaleBtn.setFocusable(false);
    scaleBtn.setToolTipText("Cycle the song scale");
    scaleBtn.addActionListener(
        e -> {
          if (SwingDelugeApp.mainInstance != null) {
            SwingDelugeApp.mainInstance.cycleScale();
          }
        });
    add(scaleBtn);

    // Track type convert (Deluge SYNTH / MIDI buttons) — converts the edited track, keeping clips.
    JButton toSynthBtn = new JButton("→SYN");
    styleButton(toSynthBtn, new Color(0x23, 0x3d, 0x23), new Color(0x66, 0xff, 0x88));
    toSynthBtn.setPreferredSize(new Dimension(50, 22));
    toSynthBtn.setMargin(new Insets(0, 0, 0, 0));
    toSynthBtn.setFont(new Font("SansSerif", Font.BOLD, 10));
    toSynthBtn.setFocusable(false);
    toSynthBtn.setToolTipText("Convert the edited track to a Synth track");
    toSynthBtn.addActionListener(e -> convertEditedTrack(true));
    add(toSynthBtn);

    JButton toMidiBtn = new JButton("→MIDI");
    styleButton(toMidiBtn, new Color(0x23, 0x23, 0x3d), new Color(0x88, 0x88, 0xff));
    toMidiBtn.setPreferredSize(new Dimension(50, 22));
    toMidiBtn.setMargin(new Insets(0, 0, 0, 0));
    toMidiBtn.setFont(new Font("SansSerif", Font.BOLD, 10));
    toMidiBtn.setFocusable(false);
    toMidiBtn.setToolTipText("Convert the edited track to a MIDI track");
    toMidiBtn.addActionListener(e -> convertEditedTrack(false));
    add(toMidiBtn);

    // RACK: show/hide the EAST synth param rack (collapse to give the grid more width).
    JButton rackBtn = new JButton("RACK");
    styleButton(rackBtn, new Color(0x2d, 0x2d, 0x3d), new Color(0x00, 0xbb, 0xff));
    rackBtn.setPreferredSize(new Dimension(50, 22));
    rackBtn.setMargin(new Insets(0, 0, 0, 0));
    rackBtn.setFont(new Font("SansSerif", Font.BOLD, 10));
    rackBtn.setFocusable(false);
    rackBtn.setToolTipText("Show/hide the synth parameter rack on the right");
    rackBtn.addActionListener(
        e -> {
          if (SwingDelugeApp.mainInstance != null) {
            SwingDelugeApp.mainInstance.toggleParamRack();
          }
        });
    add(rackBtn);
    bpmSlider.setBackground(new Color(0x12, 0x12, 0x14));
    bpmSlider.setForeground(new Color(0x00, 0xff, 0xcc));
    bpmSlider.setOpaque(false);
    bpmSlider.setFocusable(false);
    bpmSlider.setPreferredSize(new Dimension(100, 22));
    bpmSlider.setUI(
        new javax.swing.plaf.basic.BasicSliderUI(bpmSlider) {
          @Override
          public void paintTrack(java.awt.Graphics g) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(
                java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            int cy = trackRect.y + (trackRect.height / 2) - 2;
            g2.setColor(new Color(0x66, 0x66, 0x6e));
            g2.fillRoundRect(trackRect.x, cy, trackRect.width, 4, 2, 2);
            int thumbPos = thumbRect.x + (thumbRect.width / 2);
            g2.setColor(new Color(0x00, 0xff, 0xcc));
            g2.fillRoundRect(trackRect.x, cy, Math.max(0, thumbPos - trackRect.x), 4, 2, 2);
            g2.dispose();
          }

          @Override
          public void paintThumb(java.awt.Graphics g) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(
                java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Color.WHITE);
            g2.fillOval(
                thumbRect.x + 2, thumbRect.y + 2, thumbRect.width - 4, thumbRect.height - 4);
            g2.setColor(new Color(0x00, 0xff, 0xcc));
            g2.drawOval(
                thumbRect.x + 2, thumbRect.y + 2, thumbRect.width - 4, thumbRect.height - 4);
            g2.dispose();
          }
        });
    bpmSlider.addChangeListener(
        e -> {
          int val = bpmSlider.getValue();
          projectModel.setBpm(val);
          paramReadout.printTransient("TEM ", String.valueOf(val));
        });
    add(bpmSlider);

    // TEMPO encoder: turn nudges BPM via the slider (whose listener updates model + readout);
    // press shows the current tempo (mirrors the hardware tempo-encoder press, buttons.cpp:252).
    DelugeEncoderKnob tempoKnob = new DelugeEncoderKnob("TEMPO", new Color(0x00, 0xff, 0xcc));
    tempoKnob.setToolTipText("Tempo encoder: drag to change BPM, click to show tempo");
    tempoKnob.onTurn(d -> bpmSlider.setValue(bpmSlider.getValue() + d));
    tempoKnob.onPress(
        () -> paramReadout.printTransient("TEM ", String.valueOf(bpmSlider.getValue())));
    add(tempoKnob);

    JLabel masterLabel = new JLabel("MASTER:");
    masterLabel.setForeground(Color.WHITE);
    masterLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
    add(masterLabel);

    masterVolSlider = new JSlider(0, 100, (int) (projectModel.getMasterVolume() * 100));
    masterVolSlider.setBackground(new Color(0x12, 0x12, 0x14));
    masterVolSlider.setForeground(new Color(0x00, 0xff, 0xcc));
    masterVolSlider.setOpaque(false);
    masterVolSlider.setFocusable(false);
    masterVolSlider.setPreferredSize(new Dimension(100, 22));
    masterVolSlider.setUI(
        new javax.swing.plaf.basic.BasicSliderUI(masterVolSlider) {
          @Override
          public void paintTrack(java.awt.Graphics g) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(
                java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            int cy = trackRect.y + (trackRect.height / 2) - 2;
            g2.setColor(new Color(0x66, 0x66, 0x6e));
            g2.fillRoundRect(trackRect.x, cy, trackRect.width, 4, 2, 2);
            int thumbPos = thumbRect.x + (thumbRect.width / 2);
            g2.setColor(new Color(0x00, 0xff, 0xcc));
            g2.fillRoundRect(trackRect.x, cy, Math.max(0, thumbPos - trackRect.x), 4, 2, 2);
            g2.dispose();
          }

          @Override
          public void paintThumb(java.awt.Graphics g) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(
                java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Color.WHITE);
            g2.fillOval(
                thumbRect.x + 2, thumbRect.y + 2, thumbRect.width - 4, thumbRect.height - 4);
            g2.setColor(new Color(0x00, 0xff, 0xcc));
            g2.drawOval(
                thumbRect.x + 2, thumbRect.y + 2, thumbRect.width - 4, thumbRect.height - 4);
            g2.dispose();
          }
        });
    masterVolSlider.addChangeListener(
        e -> {
          int val = masterVolSlider.getValue();
          projectModel.setMasterVolume(val / 100.0f);
          paramReadout.printTransient("VOL ", val + "%");
        });
    add(masterVolSlider);

    // Gold master-volume encoder (a Deluge gold mod-knob): turn nudges the master volume slider.
    DelugeEncoderKnob volKnob = new DelugeEncoderKnob("VOL", new Color(0xff, 0xb3, 0x00));
    volKnob.setToolTipText("Master volume encoder: drag to change level");
    volKnob.onTurn(d -> masterVolSlider.setValue(masterVolSlider.getValue() + d));
    add(volKnob);

    // ── Firmware LED Display (OLED) ──
    oledPanel.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY, 2));
    add(oledPanel);

    // Modern transient parameter readout (replaces the legacy 4-char retro LED).
    add(paramReadout);

    FirmwareDisplay.get()
        .setListener(
            (main, popup) -> {
              // Handled by oledPanel
            });

    applyDisplayPreferences();
  }

  private JPanel createEncoderSim(
      String name,
      java.util.function.Consumer<Integer> onRotate,
      java.util.function.Consumer<Boolean> onClick) {
    JPanel p = new JPanel(new BorderLayout());
    p.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    p.setPreferredSize(new Dimension(80, 38));
    p.setMaximumSize(new Dimension(80, 38));

    JLabel l = new JLabel(name, SwingConstants.CENTER);
    l.setForeground(Color.WHITE);
    l.setFont(new Font("SansSerif", Font.BOLD, 8));
    p.add(l, BorderLayout.NORTH);

    JButton left = new JButton("<");
    left.setFocusPainted(false);
    left.setMargin(new Insets(1, 2, 1, 2));
    left.setFont(new Font("SansSerif", Font.PLAIN, 8));
    left.addActionListener(e -> onRotate.accept(-1));

    JButton right = new JButton(">");
    right.setFocusPainted(false);
    right.setMargin(new Insets(1, 2, 1, 2));
    right.setFont(new Font("SansSerif", Font.PLAIN, 8));
    right.addActionListener(e -> onRotate.accept(1));

    p.add(left, BorderLayout.WEST);
    p.add(right, BorderLayout.EAST);

    JButton click = new JButton("●");
    click.setFocusPainted(false);
    click.setFont(new Font("SansSerif", Font.PLAIN, 7));
    click.setMargin(new Insets(1, 1, 1, 1));
    click.addMouseListener(
        new java.awt.event.MouseAdapter() {
          @Override
          public void mousePressed(java.awt.event.MouseEvent e) {
            onClick.accept(true);
          }

          @Override
          public void mouseReleased(java.awt.event.MouseEvent e) {
            onClick.accept(false);
          }
        });
    p.add(click, BorderLayout.CENTER);

    return p;
  }

  public void selectViewModeButton(String mode) {
    if ("CLIP".equals(mode)) clipBtn.setSelected(true);
    else if ("SONG".equals(mode)) songBtn.setSelected(true);
    else if ("ARR".equals(mode)) arrBtn.setSelected(true);
    else if ("AUTO".equals(mode)) autoBtn.setSelected(true);
    else if ("PERF".equals(mode)) perfBtn.setSelected(true);

    this.currentViewMode = mode;
    updateTabStyles(clipBtn, songBtn, arrBtn, autoBtn, perfBtn);
  }

  public void selectClipView() {
    selectViewModeButton("CLIP");
  }

  public void applyDisplayPreferences() {
    org.deluge.project.PreferencesManager.DisplayType dt =
        org.deluge.project.PreferencesManager.getDisplayType();
    if (oledPanel != null) {
      oledPanel.setVisible(
          dt == org.deluge.project.PreferencesManager.DisplayType.BOTH
              || dt == org.deluge.project.PreferencesManager.DisplayType.OLED_ONLY);
    }
    if (paramReadout != null) {
      paramReadout.setVisible(
          dt == org.deluge.project.PreferencesManager.DisplayType.BOTH
              || dt == org.deluge.project.PreferencesManager.DisplayType.LED_ONLY);
    }
    revalidate();
    repaint();
  }

  public void setMasterVol(int value) {
    masterVolSlider.setValue(value);
  }

  public int getMasterVol() {
    return masterVolSlider.getValue();
  }

  private void initTabStyles(JToggleButton b) {
    b.setContentAreaFilled(false);
    b.setOpaque(true);
    b.setFocusPainted(false);
    b.setBorder(BorderFactory.createLineBorder(new Color(0x44, 0x44, 0x4f), 1));
    b.setFont(new Font("SansSerif", Font.BOLD, 12));
    b.setMargin(new Insets(2, 6, 2, 6));

    // Dynamic pixel-perfect 28px clamp heights!
    Dimension pref = b.getPreferredSize();
    int targetW = Math.max(65, pref.width + 12);
    b.setPreferredSize(new Dimension(targetW, 28));
    b.setMinimumSize(new Dimension(targetW, 28));
    b.setMaximumSize(new Dimension(targetW, 28));
  }

  private void updateTabStyles(JToggleButton... buttons) {
    for (JToggleButton b : buttons) {
      if (b.isSelected()) {
        b.setBackground(Color.WHITE);
        b.setForeground(Color.BLACK);
      } else {
        b.setBackground(new Color(0x18, 0x18, 0x1c));
        b.setForeground(Color.LIGHT_GRAY);
      }
    }
  }

  private void styleButton(AbstractButton btn, Color bg, Color fg) {
    btn.setContentAreaFilled(false);
    btn.setOpaque(true);
    btn.setFocusPainted(false);
    btn.setBackground(bg);
    btn.setForeground(fg);
    btn.setFont(new Font("SansSerif", Font.BOLD, 12));
    btn.setBorder(BorderFactory.createLineBorder(fg, 1));
    btn.setMargin(new Insets(2, 6, 2, 6));

    // Dynamic pixel-perfect 28px clamp heights!
    Dimension pref = btn.getPreferredSize();
    int targetW = Math.max(70, pref.width + 12);
    btn.setPreferredSize(new Dimension(targetW, 28));
    btn.setMinimumSize(new Dimension(targetW, 28));
    btn.setMaximumSize(new Dimension(targetW, 28));

    btn.addMouseListener(
        new java.awt.event.MouseAdapter() {
          @Override
          public void mouseEntered(java.awt.event.MouseEvent e) {
            btn.setBackground(bg.brighter());
          }

          @Override
          public void mouseExited(java.awt.event.MouseEvent e) {
            btn.setBackground(bg);
          }
        });
  }

  /**
   * A FlowLayout subclass that dynamically wraps components on multiple lines, expanding the
   * container height to fit wrapped components rather than clipping them.
   */
  public static class WrapLayout extends java.awt.FlowLayout {
    public WrapLayout() {
      super(java.awt.FlowLayout.LEFT, 10, 4);
    }

    @Override
    public java.awt.Dimension preferredLayoutSize(java.awt.Container target) {
      return layoutSize(target, true);
    }

    @Override
    public java.awt.Dimension minimumLayoutSize(java.awt.Container target) {
      return layoutSize(target, false);
    }

    private java.awt.Dimension layoutSize(java.awt.Container target, boolean preferred) {
      synchronized (target.getTreeLock()) {
        int targetWidth = target.getWidth();
        if (targetWidth == 0) targetWidth = 1200; // Default fallback width

        java.awt.Insets insets = target.getInsets();
        int hgap = getHgap();
        int vgap = getVgap();
        int maxwidth = targetWidth - (insets.left + insets.right + hgap * 2);

        int x = 0;
        int y = insets.top + vgap;
        int rowHeight = 0;
        int reqWidth = 0;

        for (java.awt.Component m : target.getComponents()) {
          if (m.isVisible()) {
            java.awt.Dimension d = preferred ? m.getPreferredSize() : m.getMinimumSize();
            if (x == 0 || x + d.width <= maxwidth) {
              if (x > 0) x += hgap;
              x += d.width;
              rowHeight = Math.max(rowHeight, d.height);
            } else {
              x = d.width;
              y += vgap + rowHeight;
              rowHeight = d.height;
            }
            reqWidth = Math.max(reqWidth, x);
          }
        }
        y += rowHeight + vgap + insets.bottom;
        return new java.awt.Dimension(reqWidth + insets.left + insets.right, y);
      }
    }
  }
}
