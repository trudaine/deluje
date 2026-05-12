package org.chuck.deluge.ui;

import java.awt.*;
import javax.swing.*;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.SynthTrackModel;

/** COMPRESSOR tab: attack, release, ratio, blend, sidechain HPF. */
public class CompressorPanel extends JPanel {

  public CompressorPanel(SynthTrackModel model, BridgeContract bridge, int trackIndex, ProjectModel projectModel) {
    super(new GridBagLayout());
    setBackground(new Color(0x22, 0x22, 0x22));
    GridBagConstraints c = new GridBagConstraints();
    c.fill = GridBagConstraints.HORIZONTAL;
    c.insets = new Insets(6, 10, 6, 10);
    c.anchor = GridBagConstraints.WEST;
    int row = 0;

    // ── Track Compressor ──
    c.gridx = 0; c.gridy = row; c.gridwidth = 3;
    add(SwingSynthConfigDialog.sectionLabel("TRACK COMPRESSOR"), c); row++;

    row = SwingSynthConfigDialog.addSlider(this, c, row, "Attack:",
        "Compressor attack (0-100). Higher = slower response to transients.",
        0, 100, (int)(bridge.getCompAttack(trackIndex) * 100),
        val -> bridge.setCompAttack(trackIndex, val / 100f), "", "compAttack",
        projectModel, trackIndex);

    row = SwingSynthConfigDialog.addSlider(this, c, row, "Release:",
        "Compressor release (0-100). Higher = slower return to unity gain.",
        0, 100, (int)(bridge.getCompRelease(trackIndex) * 100),
        val -> bridge.setCompRelease(trackIndex, val / 100f), "", "compRelease",
        projectModel, trackIndex);

    row = SwingSynthConfigDialog.addSlider(this, c, row, "Ratio:",
        "Compression ratio. 0-100 mapped to 2:1 – 256:1. Higher = stronger compression.",
        0, 100, (int)(bridge.getCompRatio(trackIndex)),
        val -> bridge.setCompRatio(trackIndex, val), "", "compRatio",
        projectModel, trackIndex);

    row = SwingSynthConfigDialog.addSlider(this, c, row, "Blend:",
        "Dry/wet blend. 0% = dry, 100% = fully compressed (parallel compression).",
        0, 100, (int)(bridge.getCompBlend(trackIndex) * 100),
        val -> bridge.setCompBlend(trackIndex, val / 100f), "%", "compBlend",
        projectModel, trackIndex);

    // ── Sidechain section ──
    c.gridx = 0; c.gridy = row; c.gridwidth = 3;
    add(SwingSynthConfigDialog.sectionLabel("SIDECHAIN HPF"), c); row++;

    row = SwingSynthConfigDialog.addSlider(this, c, row, "HPF:",
        "Sidechain high-pass filter (0-100%). Filters low frequencies from the sidechain input so bass doesn't trigger compression.",
        0, 100, (int)(bridge.getCompSidechainHpf(trackIndex) * 100),
        val -> bridge.setCompSidechainHpf(trackIndex, val / 100f), "%", "compSidechainHpf",
        projectModel, trackIndex);
  }
}
