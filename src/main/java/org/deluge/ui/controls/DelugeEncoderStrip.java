package org.deluge.ui.controls;

import java.awt.Color;
import java.awt.FlowLayout;
import java.util.function.IntConsumer;
import javax.swing.JPanel;

/**
 * A compact strip of the Deluge scroll encoders (horizontal X and vertical Y). Turning a knob emits
 * a signed cell delta to the supplied callback, mirroring the hardware data encoders that scroll
 * the grid timeline (X) and note rows (Y). The callbacks are wired by the app to the currently
 * active grid panel's {@code scrollHorizontally} / {@code scrollVertically}.
 */
public class DelugeEncoderStrip extends JPanel {

  private static final Color GREY = new Color(0x9a, 0x9a, 0xa4);
  private static final Color GOLD = new Color(0xff, 0xb3, 0x00);

  private final DelugeEncoderKnob xKnob;
  private final DelugeEncoderKnob yKnob;
  private final DelugeEncoderKnob modKnob;

  public DelugeEncoderStrip(IntConsumer onScrollX, IntConsumer onScrollY) {
    this(onScrollX, onScrollY, null);
  }

  /**
   * @param onMod optional gold mod-encoder callback (adjusts the currently selected param). When
   *     null, no gold knob is shown.
   */
  public DelugeEncoderStrip(IntConsumer onScrollX, IntConsumer onScrollY, IntConsumer onMod) {
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

    if (onMod != null) {
      modKnob = new DelugeEncoderKnob("MOD", GOLD);
      modKnob.setToolTipText("Gold mod-encoder: adjust the selected parameter (Shift-click a pad)");
      modKnob.onTurn(onMod);
      add(modKnob);
    } else {
      modKnob = null;
    }
  }

  public DelugeEncoderKnob getXKnob() {
    return xKnob;
  }

  public DelugeEncoderKnob getYKnob() {
    return yKnob;
  }

  public DelugeEncoderKnob getModKnob() {
    return modKnob;
  }
}
