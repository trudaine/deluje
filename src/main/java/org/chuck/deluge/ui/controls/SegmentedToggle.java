package org.chuck.deluge.ui.controls;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.IntConsumer;
import javax.swing.JComponent;

/**
 * A self-drawn segmented toggle (like an iOS segmented control) — a modern, compact replacement for
 * a small enum combo box or radio group. Good for discrete synth params such as LPF slope
 * (12dB/24dB), oscillator waveform, or sync mode. The selected segment is filled in the accent
 * colour; clicking a segment selects it and notifies the listener.
 */
public class SegmentedToggle extends JComponent {

  private final String[] options;
  private final Color accent;
  private int selected;
  private IntConsumer onChange = i -> {};

  public SegmentedToggle(String[] options, int initial, Color accent) {
    this.options = options != null && options.length > 0 ? options : new String[] {"—"};
    this.accent = accent != null ? accent : new Color(0xff, 0xb3, 0x00);
    this.selected = clamp(initial);
    setOpaque(false);
    setFocusable(false);
    setPreferredSize(new Dimension(Math.max(120, this.options.length * 56), 28));

    addMouseListener(
        new MouseAdapter() {
          @Override
          public void mousePressed(MouseEvent e) {
            int n = SegmentedToggle.this.options.length;
            if (n == 0 || getWidth() <= 0) {
              return;
            }
            int idx = e.getX() * n / getWidth();
            setSelectedIndex(idx);
          }
        });
  }

  private int clamp(int i) {
    if (i < 0) {
      return 0;
    }
    if (i >= options.length) {
      return options.length - 1;
    }
    return i;
  }

  public int getSelectedIndex() {
    return selected;
  }

  /** Select a segment and notify the listener if it changed. */
  public void setSelectedIndex(int i) {
    int c = clamp(i);
    if (c != selected) {
      selected = c;
      repaint();
      onChange.accept(selected);
    }
  }

  /** Select a segment without notifying (for syncing UI to model). */
  public void setSelectedIndexSilently(int i) {
    selected = clamp(i);
    repaint();
  }

  public String getSelectedOption() {
    return options[selected];
  }

  public SegmentedToggle onChange(IntConsumer c) {
    this.onChange = c != null ? c : i -> {};
    return this;
  }

  @Override
  protected void paintComponent(Graphics g) {
    Graphics2D g2 = (Graphics2D) g.create();
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

    int w = getWidth();
    int h = getHeight();
    int n = options.length;
    int arc = 8;

    g2.setColor(new Color(0x16, 0x16, 0x1a));
    g2.fillRoundRect(0, 0, w - 1, h - 1, arc, arc);

    g2.setFont(new Font("SansSerif", Font.BOLD, 11));
    FontMetrics fm = g2.getFontMetrics();

    for (int i = 0; i < n; i++) {
      int x0 = i * w / n;
      int x1 = (i + 1) * w / n;
      int sw = x1 - x0;
      boolean sel = i == selected;

      if (sel) {
        g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 210));
        g2.fillRoundRect(x0 + 2, 2, sw - 4, h - 4, arc - 2, arc - 2);
      }
      if (i > 0) {
        g2.setColor(new Color(0x33, 0x33, 0x3a));
        g2.setStroke(new BasicStroke(1f));
        g2.drawLine(x0, 4, x0, h - 4);
      }

      String label = options[i];
      int tw = fm.stringWidth(label);
      g2.setColor(sel ? Color.BLACK : new Color(0xbb, 0xbb, 0xc4));
      g2.drawString(label, x0 + (sw - tw) / 2, (h + fm.getAscent()) / 2 - 2);
    }

    g2.setColor(new Color(0x33, 0x33, 0x3a));
    g2.setStroke(new BasicStroke(1.5f));
    g2.drawRoundRect(0, 0, w - 1, h - 1, arc, arc);

    g2.dispose();
  }
}
