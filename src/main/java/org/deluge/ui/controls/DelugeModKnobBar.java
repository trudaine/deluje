package org.deluge.ui.controls;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import org.deluge.BridgeContract;
import org.deluge.model.EnvelopeModel;
import org.deluge.model.LfoModel;
import org.deluge.model.SynthTrackModel;
import org.deluge.ui.SwingDelugeApp;
import org.deluge.ui.SwingGridPanel;

/**
 * A beautiful, hardware-faithful custom control panel hosting two gold parameter encoders and a
 * vertical column of 8 mod buttons (shortcuts).
 *
 * <p>Maps 1-to-1 with the physical Deluge hardware workflow: - Tapping a mod button selects which
 * pair of parameters the gold encoders control. - Turning the gold knobs adjusts the active track's
 * global parameters. - Turning them while holding a step pad in the grid records parameter locks
 * (automation). - Pressing (clicking) an encoder resets the parameter or deletes its active
 * automation lock!
 */
public class DelugeModKnobBar extends JPanel {

  private static final Color GOLD = new Color(0xff, 0xb3, 0x00);
  private static final Color NEON_GREEN = new Color(0x00, 0xe6, 0x76);
  private static final Color BG_DARK = new Color(0x14, 0x14, 0x18);
  private static final Color BORDER_COLOR = new Color(0x2d, 0x2d, 0x30);

  private final BridgeContract bridge;
  private final Supplier<SynthTrackModel> trackSupplier;
  private final IntSupplier indexSupplier;
  private final Supplier<SwingGridPanel> gridSupplier;

  private final DelugeEncoderKnob topEncoder;
  private final DelugeEncoderKnob bottomEncoder;
  private final JLabel topLabel;
  private final JLabel bottomLabel;

  private final JToggleButton[] modButtons = new JToggleButton[8];
  private int activeMode = 1; // Default to Mode 1 (FILTER)

  private static final String[] MODE_NAMES = {
    "VOL / PAN", "FILTER", "ENV", "DELAY", "REVERB", "LFO", "STUTTER", "CUSTOM"
  };

  public DelugeModKnobBar(
      BridgeContract bridge,
      Supplier<SynthTrackModel> trackSupplier,
      IntSupplier indexSupplier,
      Supplier<SwingGridPanel> gridSupplier) {
    this.bridge = bridge;
    this.trackSupplier = trackSupplier;
    this.indexSupplier = indexSupplier;
    this.gridSupplier = gridSupplier;

    setBackground(BG_DARK);
    setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 0, 1, BORDER_COLOR),
            BorderFactory.createEmptyBorder(10, 8, 10, 8)));
    setLayout(new GridBagLayout());
    setPreferredSize(new Dimension(92, 600));

    GridBagConstraints c = new GridBagConstraints();
    c.gridx = 0;
    c.fill = GridBagConstraints.HORIZONTAL;
    c.weightx = 1.0;
    c.insets = new Insets(2, 0, 2, 0);

    // 1. Gold Title
    JLabel title = new JLabel("MACRO / MOD", JLabel.CENTER);
    title.setFont(new Font("SansSerif", Font.BOLD, 9));
    title.setForeground(GOLD);
    c.gridy = 0;
    add(title, c);

    c.gridy = 1;
    add(Box.createVerticalStrut(8), c);

    // 2. Top Encoder and Label
    topLabel = createLabel("LPF CUTOFF");
    c.gridy = 2;
    add(topLabel, c);

    topEncoder = new DelugeEncoderKnob("", GOLD);
    topEncoder.setPreferredSize(new Dimension(54, 54));
    topEncoder.onTurn(this::adjustTopKnob);
    topEncoder.onPress(this::pressTopKnob);
    c.gridy = 3;
    add(topEncoder, c);

    c.gridy = 4;
    add(Box.createVerticalStrut(10), c);

    // 3. Bottom Encoder and Label
    bottomLabel = createLabel("LPF RES");
    c.gridy = 5;
    add(bottomLabel, c);

    bottomEncoder = new DelugeEncoderKnob("", GOLD);
    bottomEncoder.setPreferredSize(new Dimension(54, 54));
    bottomEncoder.onTurn(this::adjustBottomKnob);
    bottomEncoder.onPress(this::pressBottomKnob);
    c.gridy = 6;
    add(bottomEncoder, c);

    c.gridy = 7;
    add(Box.createVerticalStrut(12), c);

    // 4. Mod Buttons stack
    JPanel btnCol = new JPanel(new GridBagLayout());
    btnCol.setOpaque(false);
    ButtonGroup group = new ButtonGroup();

    GridBagConstraints bc = new GridBagConstraints();
    bc.gridx = 0;
    bc.fill = GridBagConstraints.HORIZONTAL;
    bc.weightx = 1.0;
    bc.insets = new Insets(1, 0, 1, 0);

    for (int i = 0; i < 8; i++) {
      final int idx = i;
      JToggleButton btn = new JToggleButton(MODE_NAMES[i]);
      styleModButton(btn);
      group.add(btn);
      modButtons[i] = btn;

      btn.addActionListener(e -> selectMode(idx));
      bc.gridy = i;
      btnCol.add(btn, bc);
    }

    modButtons[activeMode].setSelected(true);
    updateLabels();

    c.gridy = 8;
    c.weighty = 1.0;
    c.fill = GridBagConstraints.BOTH;
    add(btnCol, c);
  }

  private JLabel createLabel(String text) {
    JLabel l = new JLabel(text, JLabel.CENTER);
    l.setFont(new Font("SansSerif", Font.BOLD, 8));
    l.setForeground(new Color(0x90, 0x90, 0x95));
    return l;
  }

  private void styleModButton(JToggleButton btn) {
    btn.setOpaque(true);
    btn.setContentAreaFilled(true);
    btn.setBackground(new Color(0x1a, 0x1a, 0x20));
    btn.setForeground(new Color(0x80, 0x80, 0x8a));
    btn.setFont(new Font("SansSerif", Font.BOLD, 8));
    btn.setFocusPainted(false);
    btn.setMargin(new Insets(4, 2, 4, 2));

    // Glowing neon border when active
    btn.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(0x2d, 0x2d, 0x32), 1),
            BorderFactory.createEmptyBorder(5, 2, 5, 2)));

    btn.addChangeListener(
        e -> {
          if (btn.isSelected()) {
            btn.setBackground(new Color(0x11, 0x28, 0x1c));
            btn.setForeground(NEON_GREEN);
            btn.setBorder(
                BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(NEON_GREEN, 1),
                    BorderFactory.createEmptyBorder(5, 2, 5, 2)));
          } else {
            btn.setBackground(new Color(0x1c, 0x1c, 0x22));
            btn.setForeground(new Color(0x8a, 0x8a, 0x95));
            btn.setBorder(
                BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(0x2d, 0x2d, 0x32), 1),
                    BorderFactory.createEmptyBorder(5, 2, 5, 2)));
          }
        });
  }

  public void selectMode(int mode) {
    this.activeMode = mode;
    updateLabels();

    // Alert OLED and hardware readout
    if (SwingDelugeApp.mainInstance != null) {
      SwingDelugeApp.mainInstance.updateHardwareLedDisplayTransient("MODE", MODE_NAMES[mode]);
    }
  }

  private void updateLabels() {
    switch (activeMode) {
      case 0:
        topLabel.setText("VOLUME");
        bottomLabel.setText("PAN");
        topEncoder.setToolTipText("Adjust Master Track Volume");
        bottomEncoder.setToolTipText("Adjust Track Pan (L/R)");
        break;
      case 1:
        topLabel.setText("LPF CUTOFF");
        bottomLabel.setText("LPF RES");
        topEncoder.setToolTipText("Low-Pass Filter Cutoff Frequency");
        bottomEncoder.setToolTipText("Low-Pass Filter Resonance");
        break;
      case 2:
        topLabel.setText("ENV ATTACK");
        bottomLabel.setText("ENV RELEASE");
        topEncoder.setToolTipText("Envelope 0 Attack Time");
        bottomEncoder.setToolTipText("Envelope 0 Release Time");
        break;
      case 3:
        topLabel.setText("DELAY RATE");
        bottomLabel.setText("DELAY FEEDBK");
        topEncoder.setToolTipText("BPM-synced Delay Rate");
        bottomEncoder.setToolTipText("Delay Feedback Level");
        break;
      case 4:
        topLabel.setText("REVERB SEND");
        bottomLabel.setText("HPF CUTOFF");
        topEncoder.setToolTipText("Reverb Send Level");
        bottomEncoder.setToolTipText("High-Pass Filter Cutoff Frequency");
        break;
      case 5:
        topLabel.setText("LFO RATE");
        bottomLabel.setText("LFO DEPTH");
        topEncoder.setToolTipText("LFO 1 Rate (Hz)");
        bottomEncoder.setToolTipText("LFO 1 Pitch Modulation Depth");
        break;
      case 6:
        topLabel.setText("ARP RATE");
        bottomLabel.setText("PORTAMENTO");
        topEncoder.setToolTipText("Arpeggiator Rate (Hz)");
        bottomEncoder.setToolTipText("Portamento Glide Time");
        break;
      case 7:
        topLabel.setText("OSC MIX");
        bottomLabel.setText("BITCRUSHER");
        topEncoder.setToolTipText("Oscillator 1/2 Crossfade Mix");
        bottomEncoder.setToolTipText("Decimation Bitcrusher Amount");
        break;
    }
    repaint();
  }

  private static float clamp(float v, float lo, float hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  private static int clamp(int v, int lo, int hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  private static String pct(float v) {
    return Math.round(v * 100) + "%";
  }

  // ── Encoder turn adjustments ──

  private void pushParamChange(String paramName, float oldValue, float newValue) {
    SwingGridPanel grid = gridSupplier.get();
    if (grid == null) return;
    org.deluge.model.ProjectModel pm = grid.getProjectModel();
    if (pm == null) return;
    int trackIndex = indexSupplier.getAsInt();
    if (trackIndex < 0) return;

    var stack = pm.getUndoRedoStack();
    long now = System.currentTimeMillis();

    if (stack.canUndo() && stack.peekUndo() instanceof org.deluge.model.Consequence.SynthParamConsequence prev) {
      if (prev.trackIndex() == trackIndex && prev.paramName().equals(paramName) && (now - prev.timestamp()) < 800) {
        stack.replaceLast(new org.deluge.model.Consequence.SynthParamConsequence(pm, trackIndex, paramName, prev.oldValue(), newValue, now));
        return;
      }
    }

    stack.push(new org.deluge.model.Consequence.SynthParamConsequence(pm, trackIndex, paramName, oldValue, newValue, now));
  }

  private void adjustTopKnob(int d) {
    SwingGridPanel grid = gridSupplier.get();
    if (grid != null && grid.isShiftHeld() && grid.getActiveShiftParam() != null) {
      grid.adjustRotaryParameter(d);
      return;
    }

    SynthTrackModel t = trackSupplier.get();
    if (t == null) return;

    float oldVal = 0.0f;
    String paramName = "";

    switch (activeMode) {
      case 0: // VOLUME
        oldVal = t.getVolume();
        paramName = "goldVolume";
        t.setVolume(clamp(t.getVolume() + d * 0.02f, 0f, 1.5f));
        apply(t, "LVL", pct(t.getVolume() / 1.5f));
        pushParamChange(paramName, oldVal, t.getVolume());
        break;
      case 1: // LPF CUTOFF
        oldVal = t.getLpfFreq();
        paramName = "goldLpfCutoff";
        t.setLpfFreq(clamp((float) (t.getLpfFreq() * Math.pow(1.05, d)), 20f, 20000f));
        apply(t, "CUT", String.valueOf((int) t.getLpfFreq()));
        pushParamChange(paramName, oldVal, t.getLpfFreq());
        break;
      case 2: // ENV ATTACK
        var env0 = t.getEnv(0);
        oldVal = env0.attack();
        paramName = "goldEnv0Attack";
        float a0 = clamp(env0.attack() + d * 0.05f, 0f, 10f);
        t.setEnv(
            0,
            new EnvelopeModel(
                a0, env0.decay(), env0.sustain(), env0.release(), env0.target(), env0.amount()));
        apply(t, "ATK", String.format("%.2fs", a0));
        pushParamChange(paramName, oldVal, a0);
        break;
      case 3: // DELAY RATE
        oldVal = (float) t.getDelaySyncLevel();
        paramName = "goldDelaySyncLevel";
        t.setDelaySyncLevel(clamp(t.getDelaySyncLevel() + d, 0, 12));
        apply(t, "DLYR", "DIV " + t.getDelaySyncLevel());
        pushParamChange(paramName, oldVal, (float) t.getDelaySyncLevel());
        break;
      case 4: // REVERB SEND
        oldVal = t.getReverbSend();
        paramName = "goldReverbSend";
        t.setReverbSend(clamp(t.getReverbSend() + d * 0.02f, 0f, 1.0f));
        apply(t, "REVS", pct(t.getReverbSend()));
        pushParamChange(paramName, oldVal, t.getReverbSend());
        break;
      case 5: // LFO RATE
        var lfo0 = t.getLfo(0);
        oldVal = lfo0.rateHz();
        paramName = "goldLfo0Rate";
        float r0 = clamp(lfo0.rateHz() + d * 0.1f, 0.01f, 50f);
        t.setLfo(
            0,
            new LfoModel(
                r0,
                lfo0.waveform(),
                lfo0.depth(),
                lfo0.target(),
                lfo0.isLocal(),
                lfo0.syncLevel(),
                lfo0.syncType()));
        apply(t, "LFOR", String.format("%.1fH", r0));
        pushParamChange(paramName, oldVal, r0);
        break;
      case 6: // ARP RATE
        var oldArp = t.getArp();
        oldVal = oldArp.rate();
        paramName = "goldArpRate";
        float newRate = clamp(oldArp.rate() + d * 0.05f, 0.05f, 10f);
        t.setArp(oldArp.toBuilder().rate(newRate).active(true).build());
        apply(t, "ARPR", String.format("%.2fH", newRate));
        pushParamChange(paramName, oldVal, newRate);
        break;
      case 7: // OSC MIX
        oldVal = t.getOscMix();
        paramName = "goldOscMix";
        t.setOscMix(clamp(t.getOscMix() + d * 0.02f, 0f, 1f));
        apply(t, "MIX", pct(t.getOscMix()));
        pushParamChange(paramName, oldVal, t.getOscMix());
        break;
    }
  }

  private void adjustBottomKnob(int d) {
    SwingGridPanel grid = gridSupplier.get();
    if (grid != null && grid.isShiftHeld() && grid.getActiveShiftParam() != null) {
      grid.adjustRotaryParameter(d);
      return;
    }

    SynthTrackModel t = trackSupplier.get();
    if (t == null) return;

    float oldVal = 0.0f;
    String paramName = "";

    switch (activeMode) {
      case 0: // PAN
        oldVal = t.getPan();
        paramName = "goldPan";
        t.setPan(clamp(t.getPan() + d * 0.02f, -1.0f, 1.0f));
        apply(
            t,
            "PAN",
            String.format(
                "%.0f%% %s",
                Math.abs(t.getPan() * 100), t.getPan() < 0 ? "L" : t.getPan() > 0 ? "R" : "C"));
        pushParamChange(paramName, oldVal, t.getPan());
        break;
      case 1: // LPF RES
        oldVal = t.getLpfRes();
        paramName = "goldLpfResonance";
        t.setLpfRes(clamp(t.getLpfRes() + d * 0.02f, 0f, 1f));
        apply(t, "RES", pct(t.getLpfRes()));
        pushParamChange(paramName, oldVal, t.getLpfRes());
        break;
      case 2: // ENV RELEASE
        var env0 = t.getEnv(0);
        oldVal = env0.release();
        paramName = "goldEnv0Release";
        float r0 = clamp(env0.release() + d * 0.05f, 0f, 10f);
        t.setEnv(
            0,
            new EnvelopeModel(
                env0.attack(), env0.decay(), env0.sustain(), r0, env0.target(), env0.amount()));
        apply(t, "REL", String.format("%.2fs", r0));
        pushParamChange(paramName, oldVal, r0);
        break;
      case 3: // DELAY FEEDBACK
        oldVal = (float) t.getDelayFeedbackQ31();
        paramName = "goldDelayFeedback";
        t.setDelayFeedbackQ31(clamp(t.getDelayFeedbackQ31() + d * 0x04000000, 0, 0x7fffffff));
        apply(t, "DLYF", pct((float) t.getDelayFeedbackQ31() / 0x7fffffff));
        pushParamChange(paramName, oldVal, (float) t.getDelayFeedbackQ31());
        break;
      case 4: // HPF CUTOFF
        oldVal = t.getHpfFreq();
        paramName = "goldHpfCutoff";
        t.setHpfFreq(clamp((float) (t.getHpfFreq() * Math.pow(1.05, d)), 20f, 20000f));
        apply(t, "HPF", String.valueOf((int) t.getHpfFreq()));
        pushParamChange(paramName, oldVal, t.getHpfFreq());
        break;
      case 5: // LFO DEPTH
        var lfo0 = t.getLfo(0);
        oldVal = lfo0.depth();
        paramName = "goldLfo0Depth";
        float d0 = clamp(lfo0.depth() + d * 0.02f, 0f, 1.0f);
        t.setLfo(
            0,
            new LfoModel(
                lfo0.rateHz(),
                lfo0.waveform(),
                d0,
                lfo0.target(),
                lfo0.isLocal(),
                lfo0.syncLevel(),
                lfo0.syncType()));
        apply(t, "LFOD", pct(d0));
        pushParamChange(paramName, oldVal, d0);
        break;
      case 6: // PORTAMENTO
        oldVal = t.getPortamento();
        paramName = "goldPortamento";
        t.setPortamento(clamp(t.getPortamento() + d * 0.02f, 0f, 1f));
        apply(t, "PORT", pct(t.getPortamento()));
        pushParamChange(paramName, oldVal, t.getPortamento());
        break;
      case 7: // BITCRUSHER
        oldVal = (float) t.getClippingAmount();
        paramName = "goldBitcrusher";
        t.setClippingAmount(clamp(t.getClippingAmount() + d * 10, 0, 1000));
        apply(t, "CRSH", String.valueOf(t.getClippingAmount()));
        pushParamChange(paramName, oldVal, (float) t.getClippingAmount());
        break;
    }
  }

  // ── Push-button click actions (Reset parameter) ──

  private void pressTopKnob() {
    SynthTrackModel t = trackSupplier.get();
    if (t == null) return;

    float oldVal = 0.0f;
    String paramName = "";

    // Reset default parameters
    switch (activeMode) {
      case 0: // VOLUME
        oldVal = t.getVolume();
        paramName = "goldVolume";
        t.setVolume(1.0f);
        apply(t, "LVL", "100%");
        pushParamChange(paramName, oldVal, 1.0f);
        break;
      case 1: // LPF CUTOFF
        oldVal = t.getLpfFreq();
        paramName = "goldLpfCutoff";
        t.setLpfFreq(20000f);
        apply(t, "CUT", "20k");
        pushParamChange(paramName, oldVal, 20000f);
        break;
      case 2: // ENV ATTACK
        var env0 = t.getEnv(0);
        oldVal = env0.attack();
        paramName = "goldEnv0Attack";
        t.setEnv(
            0,
            new EnvelopeModel(
                0.01f, env0.decay(), env0.sustain(), env0.release(), env0.target(), env0.amount()));
        apply(t, "ATK", "0.01s");
        pushParamChange(paramName, oldVal, 0.01f);
        break;
      case 3: // DELAY RATE
        oldVal = (float) t.getDelaySyncLevel();
        paramName = "goldDelaySyncLevel";
        t.setDelaySyncLevel(4); // default quarter note
        apply(t, "DLYR", "DIV 4");
        pushParamChange(paramName, oldVal, 4.0f);
        break;
      case 4: // REVERB SEND
        oldVal = t.getReverbSend();
        paramName = "goldReverbSend";
        t.setReverbSend(0.0f);
        apply(t, "REVS", "0%");
        pushParamChange(paramName, oldVal, 0.0f);
        break;
      case 5: // LFO RATE
        var lfo0 = t.getLfo(0);
        oldVal = lfo0.rateHz();
        paramName = "goldLfo0Rate";
        t.setLfo(
            0,
            new LfoModel(
                1.0f,
                lfo0.waveform(),
                lfo0.depth(),
                lfo0.target(),
                lfo0.isLocal(),
                lfo0.syncLevel(),
                lfo0.syncType()));
        apply(t, "LFOR", "1.0H");
        pushParamChange(paramName, oldVal, 1.0f);
        break;
      case 6: // ARP RATE
        var oldArp = t.getArp();
        oldVal = oldArp.rate();
        paramName = "goldArpRate";
        t.setArp(oldArp.toBuilder().rate(1.0f).build());
        apply(t, "ARPR", "1.0H");
        pushParamChange(paramName, oldVal, 1.0f);
        break;
      case 7: // OSC MIX
        oldVal = t.getOscMix();
        paramName = "goldOscMix";
        t.setOscMix(0.5f);
        apply(t, "MIX", "50%");
        pushParamChange(paramName, oldVal, 0.5f);
        break;
    }
  }

  private void pressBottomKnob() {
    SynthTrackModel t = trackSupplier.get();
    if (t == null) return;

    float oldVal = 0.0f;
    String paramName = "";

    // Reset default parameters
    switch (activeMode) {
      case 0: // PAN
        oldVal = t.getPan();
        paramName = "goldPan";
        t.setPan(0.0f);
        apply(t, "PAN", "C");
        pushParamChange(paramName, oldVal, 0.0f);
        break;
      case 1: // LPF RES
        oldVal = t.getLpfRes();
        paramName = "goldLpfResonance";
        t.setLpfRes(0.0f);
        apply(t, "RES", "0%");
        pushParamChange(paramName, oldVal, 0.0f);
        break;
      case 2: // ENV RELEASE
        var env0 = t.getEnv(0);
        oldVal = env0.release();
        paramName = "goldEnv0Release";
        t.setEnv(
            0,
            new EnvelopeModel(
                env0.attack(), env0.decay(), env0.sustain(), 0.2f, env0.target(), env0.amount()));
        apply(t, "REL", "0.2s");
        pushParamChange(paramName, oldVal, 0.2f);
        break;
      case 3: // DELAY FEEDBACK
        oldVal = (float) t.getDelayFeedbackQ31();
        paramName = "goldDelayFeedback";
        t.setDelayFeedbackQ31(0);
        apply(t, "DLYF", "0%");
        pushParamChange(paramName, oldVal, 0.0f);
        break;
      case 4: // HPF CUTOFF
        oldVal = t.getHpfFreq();
        paramName = "goldHpfCutoff";
        t.setHpfFreq(20f);
        apply(t, "HPF", "20");
        pushParamChange(paramName, oldVal, 20f);
        break;
      case 5: // LFO DEPTH
        var lfo0 = t.getLfo(0);
        oldVal = lfo0.depth();
        paramName = "goldLfo0Depth";
        t.setLfo(
            0,
            new LfoModel(
                lfo0.rateHz(),
                lfo0.waveform(),
                0.0f,
                lfo0.target(),
                lfo0.isLocal(),
                lfo0.syncLevel(),
                lfo0.syncType()));
        apply(t, "LFOD", "0%");
        pushParamChange(paramName, oldVal, 0.0f);
        break;
      case 6: // PORTAMENTO
        oldVal = t.getPortamento();
        paramName = "goldPortamento";
        t.setPortamento(0.0f);
        apply(t, "PORT", "0%");
        pushParamChange(paramName, oldVal, 0.0f);
        break;
      case 7: // BITCRUSHER
        oldVal = (float) t.getClippingAmount();
        paramName = "goldBitcrusher";
        t.setClippingAmount(0);
        apply(t, "CRSH", "0");
        pushParamChange(paramName, oldVal, 0.0f);
        break;
    }
  }

  /** Apply edits to engine and hardware displays */
  private void apply(SynthTrackModel t, String code, String val) {
    int idx = indexSupplier.getAsInt();
    try {
      Object eng = bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
      if (eng instanceof org.deluge.engine.FirmwareAudioEngine engine
          && idx >= 0
          && idx < engine.sounds.size()
          && engine.sounds.get(idx) instanceof org.deluge.engine.FirmwareSound fs) {
        org.deluge.engine.FirmwareFactory.applyModelToLiveSound(t, fs);
      }
    } catch (Exception ignored) {
    }

    if (SwingDelugeApp.mainInstance != null) {
      SwingDelugeApp.mainInstance.updateHardwareLedDisplayTransient(code, val);
    }
  }

  /** Force-sync selected mode and updates the button states visually */
  public void refresh() {
    SynthTrackModel t = trackSupplier.get();
    boolean enabled = t != null;
    setEnabled(enabled);
    topEncoder.setEnabled(enabled);
    bottomEncoder.setEnabled(enabled);
    for (JToggleButton btn : modButtons) {
      btn.setEnabled(enabled);
    }
    topLabel.setEnabled(enabled);
    bottomLabel.setEnabled(enabled);
  }
}
