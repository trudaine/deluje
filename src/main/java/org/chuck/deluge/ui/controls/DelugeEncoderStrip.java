package org.chuck.deluge.ui.controls;

import java.awt.Color;
import java.awt.FlowLayout;
import java.util.function.IntConsumer;
import javax.swing.JPanel;

/**
 * A compact strip of the Deluge scroll encoders (horizontal X and vertical Y). Turning a knob emits
 * a signed cell delta to the supplied callback, mirroring the hardware data encoders that scroll the
 * grid timeline (X) and note rows (Y). The callbacks are wired by the app to the currently active
 * grid panel's {@code scrollHorizontally} / {@code scrollVertically}.
 */
public class DelugeEncoderStrip extends JPanel {

  private static final Color GREY = new Color(0x9a, 0x9a, 0xa4);

  private final DelugeEncoderKnob xKnob;
  private final DelugeEncoderKnob yKnob;

  public DelugeEncoderStrip(IntConsumer onScrollX, IntConsumer onScrollY) {
    setOpaque(false);
    setLayout(new FlowLayout(FlowLayout.CENTER, 6, 0));

    xKnob = new DelugeEncoderKnob("◀ ▶", GREY);
    xKnob.setToolTipText("Horizontal encoder: scroll the timeline");
    xKnob.onTurn(onScrollX);

    yKnob = new DelugeEncoderKnob("▲ ▼", GREY);
    yKnob.setToolTipText("Vertical encoder: scroll note rows");
    yKnob.onTurn(onScrollY);

    add(xKnob);
    add(yKnob);
  }

  public DelugeEncoderKnob getXKnob() {
    return xKnob;
  }

  public DelugeEncoderKnob getYKnob() {
    return yKnob;
  }
}
