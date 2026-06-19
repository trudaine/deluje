package org.deluge.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import org.deluge.BridgeContract;
import org.deluge.model.FilterMode;
import org.deluge.model.SynthTrackModel;
import org.deluge.ui.controls.DelugeEncoderKnob;
import org.deluge.ui.controls.SegmentedToggle;

/**
 * Always-visible synth parameter rack — a compact vertical panel of the new self-drawn controls
 * (encoder knobs + segmented toggles) that edits the currently-edited synth track live. Designed to
 * dock EAST of the grid: it costs horizontal space (abundant) and no height. Knobs are relative
 * (turn = delta); changes update the model and are pushed to the running engine via {@link
 * org.deluge.firmware.engine.FirmwareFactory#applyModelToLiveSound}.
 */
public class SynthParamRack extends JPanel {

  private static final Color ACCENT = new Color(0x00, 0xbb, 0xff);
  private static final Color GOLD = new Color(0xff, 0xb3, 0x00);

  private final BridgeContract bridge;
  private final Supplier<SynthTrackModel> trackSupplier;
  private final IntSupplier indexSupplier;
  private java.util.function.Consumer<java.io.File> onReplacePreset;
  private java.util.function.Consumer<java.io.File> onLoadNewPreset;

  /**
   * Wire the preset chip's Replace / Load-as-new actions (the app provides project access +
   * refresh). Lets the synth preset be swapped from this fixed side rack — outside the scrolling
   * grid.
   */
  public void setPresetActions(
      java.util.function.Consumer<java.io.File> replace,
      java.util.function.Consumer<java.io.File> loadNew) {
    this.onReplacePreset = replace;
    this.onLoadNewPreset = loadNew;
  }

  private final JLabel title = new JLabel("SYNTH", JLabel.CENTER);
  private final SegmentedToggle filterToggle;
  private final SegmentedToggle modeToggle;

  public SynthParamRack(
      BridgeContract bridge, Supplier<SynthTrackModel> trackSupplier, IntSupplier indexSupplier) {
    this.bridge = bridge;
    this.trackSupplier = trackSupplier;
    this.indexSupplier = indexSupplier;

    setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
    setBackground(new Color(0x14, 0x14, 0x18));
    setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
    setPreferredSize(new Dimension(300, 100));

    title.setForeground(ACCENT);
    title.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 13));
    title.setAlignmentX(CENTER_ALIGNMENT);
    // The title doubles as a preset chip: click to replace this synth's preset or load a new track,
    // via the contextual LibraryPicker. Fixed in this side rack — outside the scrolling grid.
    title.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
    title.setToolTipText("Click to replace this track's synth preset or load a new synth");
    title.addMouseListener(
        new java.awt.event.MouseAdapter() {
          @Override
          public void mouseClicked(java.awt.event.MouseEvent e) {
            if (trackSupplier.get() == null) return;
            LibraryPicker.show(
                title,
                LibraryPicker.Scope.SYNTHS,
                null,
                java.util.List.of(
                    new LibraryPicker.Action(
                        "Replace track",
                        new java.awt.Color(0x00, 0x88, 0x66),
                        f -> {
                          if (onReplacePreset != null) onReplacePreset.accept(f);
                        }),
                    new LibraryPicker.Action(
                        "Load as NEW",
                        new java.awt.Color(0x33, 0x55, 0x88),
                        f -> {
                          if (onLoadNewPreset != null) onLoadNewPreset.accept(f);
                        })));
          }
        });
    add(title);
    add(javax.swing.Box.createVerticalStrut(8));

    // Two-column grid of relative encoder knobs. Drag a knob to change the value (Deluge encoder).
    JPanel knobs = new JPanel(new GridLayout(0, 2, 6, 6));
    knobs.setOpaque(false);
    knobs.add(
        knob(
            "CUTOFF",
            "Low-pass filter cutoff frequency — drag to sweep",
            ACCENT,
            this::editCutoff));
    knobs.add(knob("RES", "Low-pass filter resonance", ACCENT, this::editRes));
    knobs.add(knob("HPF", "High-pass filter cutoff frequency", ACCENT, this::editHpf));
    knobs.add(knob("OSC MIX", "Balance between oscillator 1 and 2", ACCENT, this::editOscMix));
    knobs.add(knob("LEVEL", "Track output level", GOLD, this::editLevel));
    add(knobs);

    add(javax.swing.Box.createVerticalStrut(10));
    add(rowLabel("FILTER"));
    filterToggle =
        new SegmentedToggle(new String[] {"12dB", "24dB", "SVF"}, 0, ACCENT)
            .onChange(this::setFilter);
    filterToggle.setToolTipText(
        "Filter type / slope: 12dB or 24dB ladder, or state-variable filter");
    filterToggle.setMaximumSize(new Dimension(280, 28));
    filterToggle.setAlignmentX(CENTER_ALIGNMENT);
    add(filterToggle);

    add(javax.swing.Box.createVerticalStrut(8));
    add(rowLabel("MODE"));
    modeToggle =
        new SegmentedToggle(new String[] {"SUB", "FM", "RING"}, 0, ACCENT).onChange(this::setMode);
    modeToggle.setToolTipText("Synth engine: Subtractive, FM, or Ring-mod");
    modeToggle.setMaximumSize(new Dimension(280, 28));
    modeToggle.setAlignmentX(CENTER_ALIGNMENT);
    add(modeToggle);

    add(javax.swing.Box.createVerticalGlue());
    refresh();
  }

  private DelugeEncoderKnob knob(
      String label, String tooltip, Color c, java.util.function.IntConsumer onTurn) {
    DelugeEncoderKnob k = new DelugeEncoderKnob(label, c);
    k.setPreferredSize(new Dimension(120, 64));
    k.setToolTipText(tooltip);
    k.onTurn(onTurn);
    return k;
  }

  private JLabel rowLabel(String text) {
    JLabel l = new JLabel(text, JLabel.CENTER);
    l.setForeground(new Color(0x88, 0x88, 0x92));
    l.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 9));
    l.setAlignmentX(CENTER_ALIGNMENT);
    return l;
  }

  /** Re-read the edited track: enable/disable and sync toggle states to the model. */
  public void refresh() {
    SynthTrackModel t = trackSupplier.get();
    boolean enabled = t != null;
    setControlsEnabled(enabled);
    if (!enabled) {
      title.setText("— no synth —");
      return;
    }
    title.setText("▾ " + t.getName());
    int fm = Math.min(2, t.getFilterMode().ordinal());
    filterToggle.setSelectedIndexSilently(fm);
    modeToggle.setSelectedIndexSilently(Math.max(0, Math.min(2, t.getSynthMode())));
  }

  private void setControlsEnabled(boolean on) {
    for (java.awt.Component c : getComponents()) {
      enableTree(c, on);
    }
  }

  private void enableTree(java.awt.Component c, boolean on) {
    c.setEnabled(on);
    if (c instanceof java.awt.Container cont) {
      for (java.awt.Component ch : cont.getComponents()) {
        enableTree(ch, on);
      }
    }
  }

  // ── Param edits (relative deltas), then push the model to the live engine ──

  private static float clamp(float v, float lo, float hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  void editCutoff(int d) {
    SynthTrackModel t = trackSupplier.get();
    if (t == null) return;
    t.setLpfFreq(clamp((float) (t.getLpfFreq() * Math.pow(1.05, d)), 20f, 20000f));
    apply(t, "CUT", String.valueOf((int) t.getLpfFreq()));
  }

  void editRes(int d) {
    SynthTrackModel t = trackSupplier.get();
    if (t == null) return;
    t.setLpfRes(clamp(t.getLpfRes() + d * 0.02f, 0f, 1f));
    apply(t, "RES", pct(t.getLpfRes()));
  }

  private void editHpf(int d) {
    SynthTrackModel t = trackSupplier.get();
    if (t == null) return;
    t.setHpfFreq(clamp((float) (t.getHpfFreq() * Math.pow(1.05, d)), 20f, 20000f));
    apply(t, "HPF", String.valueOf((int) t.getHpfFreq()));
  }

  private void editOscMix(int d) {
    SynthTrackModel t = trackSupplier.get();
    if (t == null) return;
    t.setOscMix(clamp(t.getOscMix() + d * 0.02f, 0f, 1f));
    apply(t, "MIX", pct(t.getOscMix()));
  }

  private void editLevel(int d) {
    SynthTrackModel t = trackSupplier.get();
    if (t == null) return;
    t.setVolume(clamp(t.getVolume() + d * 0.02f, 0f, 1.5f));
    apply(t, "LVL", pct(t.getVolume() / 1.5f));
  }

  private void setFilter(int idx) {
    SynthTrackModel t = trackSupplier.get();
    if (t == null) return;
    FilterMode fm =
        idx == 0 ? FilterMode.LADDER_12 : idx == 1 ? FilterMode.LADDER_24 : FilterMode.SVF;
    t.setFilterMode(fm);
    apply(
        t,
        "FILT",
        fm == FilterMode.LADDER_12 ? "12dB" : fm == FilterMode.LADDER_24 ? "24dB" : "SVF");
  }

  private void setMode(int idx) {
    SynthTrackModel t = trackSupplier.get();
    if (t == null) return;
    t.setSynthMode(idx);
    apply(t, "MODE", idx == 0 ? "SUB" : idx == 1 ? "FM" : "RING");
  }

  private static String pct(float v) {
    return Math.round(v * 100) + "%";
  }

  /** Push the model to the live FirmwareSound (same path the synth dialog uses) + readout. */
  private void apply(SynthTrackModel t, String code, String val) {
    int idx = indexSupplier.getAsInt();
    try {
      Object eng = bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
      if (eng instanceof org.deluge.firmware.engine.FirmwareAudioEngine engine
          && idx >= 0
          && idx < engine.sounds.size()
          && engine.sounds.get(idx) instanceof org.deluge.firmware.engine.FirmwareSound fs) {
        org.deluge.firmware.engine.FirmwareFactory.applyModelToLiveSound(t, fs);
      }
    } catch (Exception ignored) {
      // engine not running (e.g. tests) — model edit still stands
    }
    if (SwingDelugeApp.mainInstance != null) {
      SwingDelugeApp.mainInstance.updateHardwareLedDisplayTransient(code, val);
    }
  }
}
