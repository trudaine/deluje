package org.chuck.deluge.ui;

import java.awt.*;
import javax.swing.*;

/**
 * A safe, high-contrast Dark JComboBox list cell renderer that overrides macOS look-and-feel list
 * popup colors, completely preventing white-on-white text glitches inside dropdown lists.
 */
public class DarkComboBoxRenderer extends DefaultListCellRenderer {
  private static final Color BG_UNSELECTED = new Color(0x2d, 0x2d, 0x32);
  private static final Color FG_UNSELECTED = Color.WHITE;
  private static final Color BG_SELECTED = new Color(0xff, 0xaa, 0x00); // Amber highlight
  private static final Color FG_SELECTED = Color.BLACK;

  @Override
  public Component getListCellRendererComponent(
      JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
    setBackground(isSelected ? BG_SELECTED : BG_UNSELECTED);
    setForeground(isSelected ? FG_SELECTED : FG_UNSELECTED);
    setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
    return this;
  }

  /**
   * Recursively traverses a component hierarchy tree and automatically styles all JComboBoxes with
   * our high-contrast dark list cell renderer and readable colors parameters.
   */
  public static void styleComponentTree(Component comp) {
    if (comp == null) return;
    if (comp instanceof JComboBox) {
      JComboBox<?> combo = (JComboBox<?>) comp;
      combo.setRenderer(new DarkComboBoxRenderer());
      combo.setBackground(BG_UNSELECTED);
      combo.setForeground(FG_UNSELECTED);
      combo.setFocusable(false);
    } else if (comp instanceof Container) {
      for (Component child : ((Container) comp).getComponents()) {
        styleComponentTree(child);
      }
    }
  }
}
