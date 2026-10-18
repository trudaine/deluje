package org.deluge.ui;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import javax.swing.*;
import org.deluge.model.SynthTrackModel;
import org.deluge.project.PreferencesManager;

/**
 * A sleek, high-fidelity Preset Browser Panel for the Synth Editor. Displays a searchable list of
 * discovered .XML presets in the Deluge library, categorized by subdirectories, with instant
 * single-click hot-swap auditioning.
 */
public class SynthPresetBrowserPanel extends JPanel {
  private static final Logger LOGGER = Logger.getLogger(SynthPresetBrowserPanel.class.getName());

  public static record PresetEntry(File file, String name, String category) {
    @Override
    public String toString() {
      return name;
    }
  }

  private final SynthTrackModel model;
  private final java.lang.Runnable onPresetLoaded;

  private final JTextField searchField;
  private final JComboBox<String> categoryCombo;
  private final JList<PresetEntry> presetList;
  private final DefaultListModel<PresetEntry> listModel;

  private final List<PresetEntry> allPresets = new ArrayList<>();
  private final List<String> categories = new ArrayList<>();
  private boolean updatingCategories = false;

  public SynthPresetBrowserPanel(SynthTrackModel model, java.lang.Runnable onPresetLoaded) {
    this.model = model;
    this.onPresetLoaded = onPresetLoaded;

    setLayout(new BorderLayout(8, 8));
    setBackground(new Color(0x15, 0x15, 0x18));
    setBorder(BorderFactory.createEmptyBorder(10, 8, 10, 8));
    setPreferredSize(new Dimension(220, 0));

    // ── 1. Search Bar ──
    searchField = new JTextField();
    searchField.setFont(new Font("SansSerif", Font.PLAIN, 11));
    searchField.setForeground(Color.CYAN);
    searchField.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    searchField.setCaretColor(Color.CYAN);
    searchField.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(0x3d, 0x3d, 0x42), 1, true),
            BorderFactory.createEmptyBorder(4, 8, 4, 8)));
    searchField.setToolTipText("Type to filter presets in real-time...");

    // Filter on any text edit (typing, paste, or programmatic setText)
    searchField
        .getDocument()
        .addDocumentListener(
            new javax.swing.event.DocumentListener() {
              @Override
              public void insertUpdate(javax.swing.event.DocumentEvent e) {
                filterPresets();
              }

              @Override
              public void removeUpdate(javax.swing.event.DocumentEvent e) {
                filterPresets();
              }

              @Override
              public void changedUpdate(javax.swing.event.DocumentEvent e) {
                filterPresets();
              }
            });

    // ── 2. Category Selector ──
    categoryCombo = new JComboBox<>();
    categoryCombo.setFont(new Font("SansSerif", Font.BOLD, 10));
    categoryCombo.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    categoryCombo.setForeground(Color.WHITE);
    categoryCombo.addActionListener(
        e -> {
          if (!updatingCategories) {
            filterPresets();
          }
        });

    JPanel filterPanel = new JPanel(new GridLayout(2, 1, 4, 4));
    filterPanel.setBackground(new Color(0x15, 0x15, 0x18));
    filterPanel.add(searchField);
    filterPanel.add(categoryCombo);
    add(filterPanel, BorderLayout.NORTH);

    // ── 3. Preset JList ──
    listModel = new DefaultListModel<>();
    presetList = new JList<>(listModel);
    presetList.setBackground(new Color(0x12, 0x12, 0x14));
    presetList.setForeground(Color.WHITE);
    presetList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    presetList.setCellRenderer(new PresetCellRenderer());

    // Single-click to instantly audition the preset
    presetList.addListSelectionListener(
        e -> {
          if (!e.getValueIsAdjusting()) {
            loadSelectedPreset();
          }
        });

    // Double-click or Enter key to trigger a definitive reload
    presetList.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent e) {
            if (e.getClickCount() == 2) {
              loadSelectedPreset();
            }
          }
        });

    JScrollPane scroll = new JScrollPane(presetList);
    scroll.setBorder(BorderFactory.createLineBorder(new Color(0x2d, 0x2d, 0x32), 1));
    scroll.setBackground(new Color(0x12, 0x12, 0x14));
    scroll.getViewport().setBackground(new Color(0x12, 0x12, 0x14));
    SwingRandomizerDialog.styleScrollBar(scroll.getVerticalScrollBar());
    add(scroll, BorderLayout.CENTER);

    // Scan presets directory on startup
    scanPresets();
  }

  /** Scans the SYNTHS directory for preset files in the background/on boot. */
  public void scanPresets() {
    allPresets.clear();
    categories.clear();
    Set<String> uniqueCategories = new HashSet<>();

    File synthsDir = PreferencesManager.getSynthsDir();
    if (synthsDir != null && synthsDir.exists() && synthsDir.isDirectory()) {
      scanFolderRecursive(synthsDir, synthsDir, uniqueCategories);
    }

    // Sort categories and presets deterministically
    List<String> sortedCats = new ArrayList<>(uniqueCategories);
    sortedCats.sort(String::compareToIgnoreCase);
    allPresets.sort(
        (a, b) -> {
          int cmp = a.name().compareToIgnoreCase(b.name());
          return cmp != 0 ? cmp : a.category().compareToIgnoreCase(b.category());
        });

    updatingCategories = true;
    try {
      categoryCombo.removeAllItems();
      categoryCombo.addItem("📁 ALL CATEGORIES");
      for (String cat : sortedCats) {
        categoryCombo.addItem("🏷️ " + cat.toUpperCase());
      }
    } finally {
      updatingCategories = false;
    }

    filterPresets();
  }

  private void scanFolderRecursive(File root, File current, Set<String> uniqueCategories) {
    File[] files = current.listFiles();
    if (files == null) return;
    java.util.Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));

    for (File f : files) {
      if (f.isDirectory()) {
        scanFolderRecursive(root, f, uniqueCategories);
      } else if (f.isFile() && f.getName().toLowerCase().endsWith(".xml")) {
        String name = f.getName().substring(0, f.getName().length() - 4); // strip .xml

        // Determine category based on subfolder name relative to SYNTHS root
        String category = "UNCATEGORIZED";
        if (!current.equals(root)) {
          category = current.getName();
        }
        uniqueCategories.add(category);

        allPresets.add(new PresetEntry(f, name, category));
      }
    }
  }

  /** Filters the list of presets based on the search query and category selection. */
  private void filterPresets() {
    listModel.clear();
    String query = searchField.getText().trim().toLowerCase();

    int catSelIdx = categoryCombo.getSelectedIndex();
    String selectedCat = "all";
    if (catSelIdx > 0 && categoryCombo.getSelectedItem() != null) {
      selectedCat =
          categoryCombo.getSelectedItem().toString().replace("🏷️ ", "").trim().toLowerCase();
    }

    for (PresetEntry entry : allPresets) {
      boolean matchesQuery = entry.name().toLowerCase().contains(query);
      boolean matchesCategory =
          "all".equals(selectedCat) || entry.category().toLowerCase().equals(selectedCat);

      if (matchesQuery && matchesCategory) {
        listModel.addElement(entry);
      }
    }
  }

  private void loadSelectedPreset() {
    PresetEntry entry = presetList.getSelectedValue();
    if (entry == null) return;

    try {
      // Parse the preset XML file
      SynthTrackModel parsed = org.deluge.xml.DelugeXmlParser.parseSynth(entry.file());

      // Hot-swap parameters of the existing model!
      model.copyParametersFrom(parsed);

      // Trigger callback to refresh the UI sliders, combos, and visual graphs
      if (onPresetLoaded != null) {
        onPresetLoaded.run();
      }
    } catch (Exception ex) {
      LOGGER.warning("[PresetBrowser] Failed to load preset: " + ex.getMessage());
    }
  }

  JTextField getSearchFieldForTest() {
    return searchField;
  }

  JComboBox<String> getCategoryComboForTest() {
    return categoryCombo;
  }

  JList<PresetEntry> getPresetListForTest() {
    return presetList;
  }

  /** Custom JList cell renderer for a premium dark synth aesthetic. */
  private static class PresetCellRenderer extends DefaultListCellRenderer {
    private static final Color BG_NORMAL = new Color(0x12, 0x12, 0x14);
    private static final Color BG_HOVER = new Color(0x22, 0x22, 0x26);
    private static final Color BG_SELECTED = new Color(0x00, 0xff, 0xcc, 35);
    private static final Color ACCENT_CYAN = new Color(0x00, 0xff, 0xcc);

    @Override
    public Component getListCellRendererComponent(
        JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {

      PresetEntry entry = (PresetEntry) value;
      JPanel panel =
          new JPanel(new BorderLayout(6, 0)) {
            @Override
            protected void paintComponent(Graphics g) {
              super.paintComponent(g);
              if (isSelected) {
                // Draw a glowing left border for the selected item
                g.setColor(ACCENT_CYAN);
                g.fillRect(0, 0, 3, getHeight());
              }
            }
          };
      panel.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
      panel.setOpaque(true);

      JLabel nameLabel = new JLabel(entry.name());
      nameLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
      nameLabel.setForeground(isSelected ? ACCENT_CYAN : Color.WHITE);

      JLabel catLabel = new JLabel(entry.category().toUpperCase());
      catLabel.setFont(new Font("Monospaced", Font.BOLD, 8));
      catLabel.setForeground(Color.GRAY);

      panel.add(nameLabel, BorderLayout.CENTER);
      panel.add(catLabel, BorderLayout.EAST);

      if (isSelected) {
        panel.setBackground(BG_SELECTED);
      } else {
        panel.setBackground(BG_NORMAL);
      }

      // Handle hover highlight (mimicked via cellHasFocus or list selection state)
      if (!isSelected && cellHasFocus) {
        panel.setBackground(BG_HOVER);
      }

      return panel;
    }
  }
}
