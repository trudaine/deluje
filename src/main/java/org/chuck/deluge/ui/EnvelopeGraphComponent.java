package org.chuck.deluge.ui;

import java.awt.*;
import java.awt.geom.GeneralPath;
import javax.swing.*;
import org.chuck.deluge.model.EnvelopeModel;
import org.chuck.deluge.model.SynthTrackModel;

/**
 * Custom Swing component that draws a high-fidelity graphical representation of a synthesizer ADSR
 * envelope. Replicates the layout and sigmoid-curve styling of the community firmware's OLED
 * screen.
 */
public class EnvelopeGraphComponent extends JComponent {
  private final SynthTrackModel model;
  private final int envIdx;

  public EnvelopeGraphComponent(SynthTrackModel model, int envIdx) {
    this.model = model;
    this.envIdx = envIdx;
    setPreferredSize(new Dimension(360, 180));
    setMinimumSize(new Dimension(240, 120));
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    Graphics2D g2 = (Graphics2D) g;
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

    int w = getWidth();
    int h = getHeight();

    // Sleek dark background matching synth theme
    g2.setColor(new Color(0x14, 0x14, 0x18));
    g2.fillRect(0, 0, w, h);

    // Subtle dotted grid lines in background
    g2.setColor(new Color(0x2d, 0x2d, 0x32));
    Stroke oldStroke = g2.getStroke();
    g2.setStroke(
        new BasicStroke(
            1.0f,
            BasicStroke.CAP_BUTT,
            BasicStroke.JOIN_MITER,
            10.0f,
            new float[] {2.0f, 4.0f},
            0.0f));

    // Vertical grid lines
    for (int x = 40; x < w; x += 40) {
      g2.drawLine(x, 0, x, h);
    }
    // Horizontal grid lines
    for (int y = 30; y < h; y += 30) {
      g2.drawLine(0, y, w, y);
    }
    g2.setStroke(oldStroke);

    EnvelopeModel env = model.getEnv(envIdx);
    if (env == null) {
      return;
    }

    // Capture and cap model values to safe display ranges
    float attack = Math.max(0.001f, Math.min(2.0f, env.attack()));
    float decay = Math.max(0.0f, Math.min(5.0f, env.decay()));
    float sustain = Math.max(0.0f, Math.min(1.0f, env.sustain()));
    float release = Math.max(0.0f, Math.min(5.0f, env.release()));

    // Normalized display ratios (0.0 to 1.0)
    float attackNorm = attack / 2.0f;
    float decayNorm = decay / 5.0f;
    float sustainNorm = sustain;
    float releaseNorm = release / 5.0f;

    // Layout bounds
    int paddingX = 20;
    int paddingY = 20;
    int drawW = w - 2 * paddingX;
    int drawH = h - 2 * paddingY;

    float maxSegW = drawW / 4.0f;
    float startX = paddingX;
    float baseY = paddingY + drawH;
    float startY = paddingY;

    // Calculate X positions (replicates OLED's fixed sustainX design)
    float attackW = attackNorm * maxSegW;
    float decayW = decayNorm * maxSegW;

    float attackX = startX + attackW;
    float decayX = attackX + decayW;
    float sustainX = startX + maxSegW * 3.0f; // Fixed sustain end anchor

    // Guarantee decayX does not overflow sustainX
    if (decayX > sustainX) {
      decayX = sustainX;
    }

    // Release extends dynamically to the end of the canvas
    float releaseW = releaseNorm * (startX + drawW - sustainX);
    float releaseX = sustainX + releaseW;

    // Calculate Y positions
    float sustainY = baseY - sustainNorm * drawH;

    // Construct the continuous ADSR path
    GeneralPath path = new GeneralPath();
    path.moveTo(startX, baseY);
    path.lineTo(attackX, startY);
    path.lineTo(decayX, sustainY);
    path.lineTo(sustainX, sustainY);
    path.lineTo(releaseX, baseY);
    path.lineTo(startX + drawW, baseY);

    // 1. Draw transparent glowing fill area under the curve
    g2.setPaint(
        new GradientPaint(
            0, startY, new Color(0x00, 0xcc, 0xff, 60), 0, baseY, new Color(0x00, 0xcc, 0xff, 0)));
    GeneralPath fillPath = (GeneralPath) path.clone();
    fillPath.lineTo(startX + drawW, baseY);
    fillPath.closePath();
    g2.fill(fillPath);

    // 2. Draw thick neon cyan main curve line
    g2.setColor(new Color(0x00, 0xcc, 0xff));
    g2.setStroke(new BasicStroke(3.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    g2.draw(path);
    g2.setStroke(oldStroke);

    // 3. Draw glowing vertex highlight circles (OLED style transition anchors)
    g2.setColor(new Color(0x00, 0xff, 0xcc));
    drawVertex(g2, attackX, startY);
    drawVertex(g2, decayX, sustainY);
    drawVertex(g2, sustainX, sustainY);
    drawVertex(g2, releaseX, baseY);

    // 4. Draw stage text labels (A, D, S, R) centered below the stages
    g2.setFont(new Font("SansSerif", Font.BOLD, 10));
    g2.setColor(Color.GRAY);
    g2.drawString("A", startX + (attackX - startX) / 2 - 3, baseY + 14);
    g2.drawString("D", attackX + (decayX - attackX) / 2 - 3, baseY + 14);
    g2.drawString("S", decayX + (sustainX - decayX) / 2 - 3, baseY + 14);
    g2.drawString("R", sustainX + (releaseX - sustainX) / 2 - 3, baseY + 14);
  }

  private void drawVertex(Graphics2D g2, float cx, float cy) {
    int r = 4;
    g2.fillOval((int) cx - r, (int) cy - r, r * 2, r * 2);
    g2.setColor(new Color(0x14, 0x14, 0x18));
    g2.drawOval((int) cx - r, (int) cy - r, r * 2, r * 2);
    g2.setColor(new Color(0x00, 0xff, 0xcc));
  }
}
