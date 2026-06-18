package org.deluge.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Point;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.deluge.project.PreferencesManager;

/**
 * Contextual, anchored-popover library picker. Replaces the global SD-card tree and the raw {@link
 * javax.swing.JFileChooser} for the common "change <i>this</i> thing" case: it opens next to the
 * widget it affects, is pre-scoped to the relevant category (so it only shows relevant choices),
 * previews/auditions the selection, and commits through explicit, named action verbs ("Replace
 * this…", "Load as new…") supplied by the caller. See docs design proposal (anchored-popover
 * model).
 */
public class LibraryPicker extends JDialog {

  /** What the picker browses — fixes the root directory, file filter, and preview type. */
  public enum Scope {
    SAMPLES("Sample", new String[] {".wav", ".aif", ".aiff", ".flac"}, true),
    WAVETABLES("Wavetable", new String[] {".wav"}, true),
    SYNTHS("Synth preset", new String[] {".xml"}, false),
    KITS("Kit preset", new String[] {".xml"}, false),
    SONGS("Song", new String[] {".xml"}, false),
    PATTERNS("Pattern", new String[] {".ck", ".xml"}, false);

    final String label;
    final String[] exts;
    final boolean audio;

    Scope(String label, String[] exts, boolean audio) {
      this.label = label;
      this.exts = exts;
      this.audio = audio;
    }

    File root() {
      return switch (this) {
        case SAMPLES, WAVETABLES -> new File(PreferencesManager.getSamplesDir());
        case SYNTHS -> PreferencesManager.getSynthsDir();
        case KITS -> PreferencesManager.getKitsDir();
        case SONGS -> PreferencesManager.getSongsDir();
        case PATTERNS -> PreferencesManager.getPatternsDir();
      };
    }
  }

  /** A named, committing action verb shown in the picker's action bar (e.g. "Replace", "New"). */
  public record Action(String label, Color color, Consumer<File> handler) {}

  private final File root;
  private final List<File> allFiles = new ArrayList<>();
  private final DefaultListModel<String> listModel = new DefaultListModel<>();
  private final JList<String> list = new JList<>(listModel);
  private SwingWaveformPanel preview;

  private LibraryPicker(Component anchor, Scope scope, String currentPath, List<Action> actions) {
    // Modal: a modeless popover parented to a config DIALOG renders behind it (z-order) and the
    // list is never seen. Modal reliably appears on top, blocks the parent, and closes on
    // pick/cancel/Esc.
    super(SwingUtilities.getWindowAncestor(anchor), ModalityType.APPLICATION_MODAL);
    setUndecorated(true);
    this.root = scope.root();

    JPanel content = new JPanel(new BorderLayout(0, 6));
    content.setBackground(SwingSynthConfigDialog.BG_DARK);
    content.setBorder(BorderFactory.createLineBorder(SwingSynthConfigDialog.ACCENT_MINT, 1));

    // Header
    JLabel title = new JLabel("  " + scope.label + " ▾");
    title.setForeground(SwingSynthConfigDialog.ACCENT_MINT);
    title.setFont(new Font("SansSerif", Font.BOLD, 12));
    title.setBorder(BorderFactory.createEmptyBorder(6, 4, 0, 4));
    content.add(title, BorderLayout.NORTH);

    // Center: search + list (left) and preview (right)
    JTextField search = new JTextField();
    search.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    search.setForeground(Color.WHITE);
    search.setCaretColor(Color.WHITE);
    search.setToolTipText("Type to filter");

    list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    list.setBackground(SwingSynthConfigDialog.BG_CARD);
    list.setForeground(Color.LIGHT_GRAY);
    list.setFont(new Font("SansSerif", Font.PLAIN, 11));
    JScrollPane listScroll = new JScrollPane(list);
    listScroll.setPreferredSize(new Dimension(260, 320));

    JPanel left = new JPanel(new BorderLayout(0, 4));
    left.setOpaque(false);
    left.add(search, BorderLayout.NORTH);
    left.add(listScroll, BorderLayout.CENTER);

    JPanel center = new JPanel(new BorderLayout(8, 0));
    center.setOpaque(false);
    center.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
    center.add(left, BorderLayout.WEST);

    if (scope.audio) {
      preview = new SwingWaveformPanel(null);
      preview.setPreferredSize(new Dimension(300, 320));
      JButton audition = new JButton("▶ Audition");
      styleBtn(audition, SwingSynthConfigDialog.BG_CONTROL, SwingSynthConfigDialog.ACCENT_BLUE);
      audition.addActionListener(e -> auditionSelected());
      JPanel pv = new JPanel(new BorderLayout(0, 4));
      pv.setOpaque(false);
      pv.add(preview, BorderLayout.CENTER);
      pv.add(audition, BorderLayout.SOUTH);
      center.add(pv, BorderLayout.CENTER);
    }
    content.add(center, BorderLayout.CENTER);

    // Action bar: explicit verbs + Cancel
    JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
    bar.setBackground(SwingSynthConfigDialog.BG_DARK);
    for (Action a : actions) {
      JButton b = new JButton(a.label());
      styleBtn(b, a.color(), Color.WHITE);
      b.addActionListener(
          e -> {
            File sel = selectedFile();
            if (sel != null) {
              a.handler().accept(sel);
              dispose();
            }
          });
      bar.add(b);
    }
    JButton cancel = new JButton("Cancel");
    styleBtn(cancel, SwingSynthConfigDialog.BG_CONTROL, Color.LIGHT_GRAY);
    cancel.addActionListener(e -> dispose());
    bar.add(cancel);
    content.add(bar, BorderLayout.SOUTH);

    setContentPane(content);

    // Populate + wire interactions
    scanFiles(scope);
    refilter("", currentPath);
    search
        .getDocument()
        .addDocumentListener(
            new DocumentListener() {
              public void insertUpdate(DocumentEvent e) {
                refilter(search.getText(), null);
              }

              public void removeUpdate(DocumentEvent e) {
                refilter(search.getText(), null);
              }

              public void changedUpdate(DocumentEvent e) {
                refilter(search.getText(), null);
              }
            });
    list.addListSelectionListener(
        e -> {
          if (!e.getValueIsAdjusting()) updatePreview();
        });

    // Esc closes (Cancel button + action handlers also dispose).
    getRootPane()
        .registerKeyboardAction(
            e -> dispose(),
            javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
            JPanel.WHEN_IN_FOCUSED_WINDOW);

    pack();
    positionNear(anchor);
  }

  /** Opens the picker anchored just below {@code anchor} (modal — blocks until pick/cancel). */
  public static void show(Component anchor, Scope scope, String currentPath, List<Action> actions) {
    new LibraryPicker(anchor, scope, currentPath, actions).setVisible(true);
  }

  private void scanFiles(Scope scope) {
    allFiles.clear();
    if (root == null || !root.isDirectory()) return;
    try (Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(root.toPath(), 8)) {
      walk.filter(java.nio.file.Files::isRegularFile)
          .map(java.nio.file.Path::toFile)
          .filter(f -> matchesExt(f, scope.exts))
          .limit(4000)
          .forEach(allFiles::add);
    } catch (Exception ex) {
      System.err.println("[LibraryPicker] scan failed: " + ex.getMessage());
    }
    allFiles.sort((a, b) -> rel(a).compareToIgnoreCase(rel(b)));
  }

  private static boolean matchesExt(File f, String[] exts) {
    String n = f.getName().toLowerCase();
    for (String e : exts) if (n.endsWith(e)) return true;
    return false;
  }

  private String rel(File f) {
    return root.toPath().relativize(f.toPath()).toString().replace('\\', '/');
  }

  private void refilter(String query, String selectPath) {
    String q = query == null ? "" : query.toLowerCase();
    listModel.clear();
    int selectIdx = -1;
    for (File f : allFiles) {
      String r = rel(f);
      if (q.isEmpty() || r.toLowerCase().contains(q)) {
        listModel.addElement(r);
        if (selectPath != null
            && f.getAbsolutePath().replace('\\', '/').endsWith(r)
            && selectPath.replace('\\', '/').endsWith(r)) {
          selectIdx = listModel.size() - 1;
        }
      }
    }
    if (selectIdx >= 0) {
      list.setSelectedIndex(selectIdx);
      list.ensureIndexIsVisible(selectIdx);
    }
  }

  private File selectedFile() {
    int i = list.getSelectedIndex();
    if (i < 0) return null;
    return new File(root, listModel.get(i));
  }

  private void updatePreview() {
    if (preview == null) return;
    File f = selectedFile();
    if (f != null) preview.setSamplePath(f.getAbsolutePath());
  }

  private void auditionSelected() {
    File f = selectedFile();
    if (f == null) return;
    Thread.startVirtualThread(
        () -> {
          try (javax.sound.sampled.AudioInputStream s =
              javax.sound.sampled.AudioSystem.getAudioInputStream(f)) {
            javax.sound.sampled.Clip clip = javax.sound.sampled.AudioSystem.getClip();
            clip.open(s);
            clip.start();
            Thread.sleep(2000);
            clip.close();
          } catch (Exception ex) {
            System.err.println("[LibraryPicker] audition failed: " + ex.getMessage());
          }
        });
  }

  private void positionNear(Component anchor) {
    try {
      Point p = anchor.getLocationOnScreen();
      int x = p.x;
      int y = p.y + anchor.getHeight();
      Dimension scr = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
      if (x + getWidth() > scr.width) x = scr.width - getWidth() - 8;
      if (y + getHeight() > scr.height) y = Math.max(0, p.y - getHeight());
      setLocation(Math.max(0, x), Math.max(0, y));
    } catch (Exception ex) {
      setLocationRelativeTo(anchor);
    }
  }

  private static void styleBtn(JButton b, Color bg, Color fg) {
    b.setBackground(bg);
    b.setForeground(fg);
    b.setFocusPainted(false);
    b.setFont(new Font("SansSerif", Font.BOLD, 11));
    b.setBorder(BorderFactory.createEmptyBorder(5, 12, 5, 12));
  }
}
