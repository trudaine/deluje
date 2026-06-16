package org.chuck.deluge.ui;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * Centrally manages the global studio UI accent themes (Neon Cyan, Solar Orange, Matrix Green, Acid
 * Pink), notifying listeners in real-time when the theme changes.
 */
public class ThemeManager {

  public enum Theme {
    NEON_CYAN("Neon Cyan", new Color(0x00, 0xcc, 0xff), new Color(0x00, 0xff, 0xcc)),
    SOLAR_ORANGE("Solar Orange", new Color(0xff, 0x66, 0x00), new Color(0xff, 0xcc, 0x00)),
    MATRIX_GREEN("Matrix Green", new Color(0x00, 0xcc, 0x44), new Color(0x33, 0xff, 0x77)),
    ACID_PINK("Acid Pink", new Color(0xff, 0x00, 0x7f), new Color(0xcc, 0x00, 0xff));

    private final String name;
    private final Color primaryAccent;
    private final Color secondaryAccent;

    Theme(String name, Color primaryAccent, Color secondaryAccent) {
      this.name = name;
      this.primaryAccent = primaryAccent;
      this.secondaryAccent = secondaryAccent;
    }

    public String getName() {
      return name;
    }

    public Color getPrimaryAccent() {
      return primaryAccent;
    }

    public Color getSecondaryAccent() {
      return secondaryAccent;
    }

    @Override
    public String toString() {
      return name;
    }
  }

  private static Theme activeTheme = Theme.NEON_CYAN;
  private static final List<Runnable> listeners = new ArrayList<>();

  public static Theme getActiveTheme() {
    return activeTheme;
  }

  public static void setActiveTheme(Theme theme) {
    if (theme == null) return;
    activeTheme = theme;
    // Safely copy and notify on EDT
    List<Runnable> copy;
    synchronized (listeners) {
      copy = new ArrayList<>(listeners);
    }
    for (Runnable l : copy) {
      try {
        l.run();
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
  }

  public static Color getPrimaryAccent() {
    return activeTheme.getPrimaryAccent();
  }

  public static Color getSecondaryAccent() {
    return activeTheme.getSecondaryAccent();
  }

  public static void addThemeListener(Runnable r) {
    if (r == null) return;
    synchronized (listeners) {
      listeners.add(r);
    }
  }
}
