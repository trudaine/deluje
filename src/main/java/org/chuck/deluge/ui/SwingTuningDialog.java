package org.chuck.deluge.ui;

import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.tuning.ScalaScale;
import org.chuck.deluge.model.tuning.ScalaScaleParser;
import org.chuck.deluge.project.PreferencesManager;

/**
 * Premium, high-fidelity Swing dialog for managing song-level microtuning, custom temperaments, and
 * importing standard Scala (.scl) files. Supports real-time live-auditioning of cents and ratio
 * changes during playback.
 */
public class SwingTuningDialog extends JDialog {

  private final ProjectModel project;
  private final Runnable onApply;

  // Saved original values for Undo/Cancel
  private final int origNotes;
  private final boolean origIsEqual;
  private final double origBaseFreq;
  private final int[] origCents = new int[64];
  private final double[] origRatios = new double[64];

  // Global Controls
  private JComboBox<String> typeCombo;
  private JSpinner notesSpinner;
  private JSpinner baseFreqSpinner;
  private JPanel adjustmentsContainer;
  private JScrollPane scrollPane;

  private boolean isUpdatingUI = false;

  public SwingTuningDialog(Frame owner, ProjectModel project, Runnable onApply) {
    super(owner, "Tuning & Temperaments", true);
    this.project = project;
    this.onApply = onApply;

    // Backup original values
    this.origNotes = project.getOctaveNumMicrotonalNotes();
    this.origIsEqual = project.isEqualTemperament();
    this.origBaseFreq = project.getBaseFrequencyHz();
    System.arraycopy(project.getCentAdjustForNotesInTemperament(), 0, origCents, 0, 64);
    System.arraycopy(project.getCustomRatios(), 0, origRatios, 0, 64);

    setSize(650, 600);
    setMinimumSize(new Dimension(500, 400));
    setLocationRelativeTo(owner);
    setLayout(new BorderLayout());

    // 1. Top Panel: Global settings
    JPanel topPanel = new JPanel(new GridBagLayout());
    topPanel.setBackground(new Color(0x1e, 0x1e, 0x24));
    topPanel.setBorder(
        BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(0x3a, 0x3a, 0x42), 1),
            "Temperament Settings",
            TitledBorder.LEFT,
            TitledBorder.TOP,
            new Font("SansSerif", Font.BOLD, 12),
            new Color(0x00, 0xff, 0xcc)));

    GridBagConstraints gbc = new GridBagConstraints();
    gbc.insets = new Insets(6, 12, 6, 12);
    gbc.fill = GridBagConstraints.HORIZONTAL;

    // Type Selector
    gbc.gridx = 0;
    gbc.gridy = 0;
    JLabel typeLabel = new JLabel("Type:");
    typeLabel.setForeground(Color.WHITE);
    typeLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
    topPanel.add(typeLabel, gbc);

    gbc.gridx = 1;
    typeCombo = new JComboBox<>(new String[] {"Equal Temperament", "Custom Ratios"});
    typeCombo.setFont(new Font("SansSerif", Font.PLAIN, 12));
    typeCombo.setSelectedIndex(project.isEqualTemperament() ? 0 : 1);
    typeCombo.addActionListener(
        e -> {
          if (isUpdatingUI) return;
          project.setIsEqualTemperament(typeCombo.getSelectedIndex() == 0);
          rebuildAdjustmentsPanel();
          triggerLiveUpdate();
        });
    topPanel.add(typeCombo, gbc);

    // Notes count
    gbc.gridx = 0;
    gbc.gridy = 1;
    JLabel notesLabel = new JLabel("Notes / Octave:");
    notesLabel.setForeground(Color.WHITE);
    notesLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
    topPanel.add(notesLabel, gbc);

    gbc.gridx = 1;
    notesSpinner =
        new JSpinner(new SpinnerNumberModel(project.getOctaveNumMicrotonalNotes(), 1, 64, 1));
    notesSpinner.setFont(new Font("SansSerif", Font.PLAIN, 12));
    notesSpinner.addChangeListener(
        e -> {
          if (isUpdatingUI) return;
          project.setOctaveNumMicrotonalNotes((Integer) notesSpinner.getValue());
          rebuildAdjustmentsPanel();
          triggerLiveUpdate();
        });
    topPanel.add(notesSpinner, gbc);

    // Reference base frequency
    gbc.gridx = 0;
    gbc.gridy = 2;
    JLabel freqLabel = new JLabel("Reference Pitch (A):");
    freqLabel.setForeground(Color.WHITE);
    freqLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
    topPanel.add(freqLabel, gbc);

    gbc.gridx = 1;
    baseFreqSpinner =
        new JSpinner(new SpinnerNumberModel(project.getBaseFrequencyHz(), 10.0, 1000.0, 0.1));
    baseFreqSpinner.setFont(new Font("SansSerif", Font.PLAIN, 12));
    baseFreqSpinner.addChangeListener(
        e -> {
          if (isUpdatingUI) return;
          project.setBaseFrequencyHz((Double) baseFreqSpinner.getValue());
          triggerLiveUpdate();
        });
    topPanel.add(baseFreqSpinner, gbc);

    // Import button
    gbc.gridx = 0;
    gbc.gridy = 3;
    gbc.gridwidth = 2;
    JButton importBtn = new JButton("Import Scala (.scl) File...");
    importBtn.setFont(new Font("SansSerif", Font.BOLD, 11));
    importBtn.addActionListener(e -> chooseAndImportScalaFile());
    topPanel.add(importBtn, gbc);

    add(topPanel, BorderLayout.NORTH);

    // 2. Center Panel: Scrollable list of adjustments
    adjustmentsContainer = new JPanel();
    adjustmentsContainer.setBackground(new Color(0x12, 0x12, 0x14));
    adjustmentsContainer.setLayout(new BoxLayout(adjustmentsContainer, BoxLayout.Y_AXIS));

    scrollPane = new JScrollPane(adjustmentsContainer);
    scrollPane.setBackground(new Color(0x12, 0x12, 0x14));
    scrollPane.getViewport().setBackground(new Color(0x12, 0x12, 0x14));
    scrollPane.setBorder(
        BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(0x2a, 0x2a, 0x30), 1),
            "Note-by-Note Scaling Map",
            TitledBorder.LEFT,
            TitledBorder.TOP,
            new Font("SansSerif", Font.BOLD, 11),
            Color.GRAY));
    add(scrollPane, BorderLayout.CENTER);

    // 3. Bottom Panel: Actions
    JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 12));
    actionPanel.setBackground(new Color(0x18, 0x18, 0x1c));

    JButton applyBtn = new JButton("Apply");
    applyBtn.setFont(new Font("SansSerif", Font.PLAIN, 12));
    applyBtn.addActionListener(e -> triggerLiveUpdate());

    JButton okBtn = new JButton("OK");
    okBtn.setFont(new Font("SansSerif", Font.BOLD, 12));
    okBtn.addActionListener(
        e -> {
          triggerLiveUpdate();
          dispose();
        });

    JButton cancelBtn = new JButton("Cancel");
    cancelBtn.setFont(new Font("SansSerif", Font.PLAIN, 12));
    cancelBtn.addActionListener(e -> revertAndClose());

    actionPanel.add(cancelBtn);
    actionPanel.add(applyBtn);
    actionPanel.add(okBtn);
    add(actionPanel, BorderLayout.SOUTH);

    // Initial load
    rebuildAdjustmentsPanel();

    // Recolor and style all components dark
    DarkComboBoxRenderer.styleComponentTree(this);
  }

  private void triggerLiveUpdate() {
    onApply.run();
  }

  private void revertAndClose() {
    // Restore backup values
    project.setOctaveNumMicrotonalNotes(origNotes);
    project.setIsEqualTemperament(origIsEqual);
    project.setBaseFrequencyHz(origBaseFreq);
    System.arraycopy(origCents, 0, project.getCentAdjustForNotesInTemperament(), 0, 64);
    System.arraycopy(origRatios, 0, project.getCustomRatios(), 0, 64);

    // Rebuild active engine state
    onApply.run();
    dispose();
  }

  private void rebuildAdjustmentsPanel() {
    adjustmentsContainer.removeAll();

    int notesCount = project.getOctaveNumMicrotonalNotes();
    boolean isEqual = project.isEqualTemperament();

    for (int i = 0; i < notesCount; i++) {
      final int noteIndex = i;
      JPanel row = new JPanel(new BorderLayout(10, 5));
      row.setBackground(new Color(0x12, 0x12, 0x14));
      row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
      row.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));

      JLabel label = new JLabel(String.format("Note %02d:", i));
      label.setForeground(Color.WHITE);
      label.setFont(new Font("Monospaced", Font.BOLD, 12));
      label.setPreferredSize(new Dimension(80, 25));
      row.add(label, BorderLayout.WEST);

      if (isEqual) {
        // EQUAL TEMPERAMENT: Slider + Spinner for cents adjustment
        JPanel centsPanel = new JPanel(new BorderLayout(10, 0));
        centsPanel.setBackground(new Color(0x12, 0x12, 0x14));

        int currentCent = project.getCentAdjustForNotesInTemperament()[i];
        JSlider slider = new JSlider(-100, 100, currentCent);
        slider.setBackground(new Color(0x12, 0x12, 0x14));
        slider.setPreferredSize(new Dimension(220, 25));

        JSpinner spinner = new JSpinner(new SpinnerNumberModel(currentCent, -100, 100, 1));
        spinner.setPreferredSize(new Dimension(65, 25));

        // Sync Slider -> Spinner
        slider.addChangeListener(
            e -> {
              if (isUpdatingUI) return;
              int val = slider.getValue();
              isUpdatingUI = true;
              spinner.setValue(val);
              project.getCentAdjustForNotesInTemperament()[noteIndex] = val;
              isUpdatingUI = false;
              triggerLiveUpdate();
            });

        // Sync Spinner -> Slider
        spinner.addChangeListener(
            e -> {
              if (isUpdatingUI) return;
              int val = (Integer) spinner.getValue();
              isUpdatingUI = true;
              slider.setValue(val);
              project.getCentAdjustForNotesInTemperament()[noteIndex] = val;
              isUpdatingUI = false;
              triggerLiveUpdate();
            });

        centsPanel.add(slider, BorderLayout.CENTER);
        centsPanel.add(spinner, BorderLayout.EAST);
        row.add(centsPanel, BorderLayout.CENTER);
      } else {
        // CUSTOM RATIOS: Text field for fractional/decimal ratio entry
        JPanel ratioPanel = new JPanel(new BorderLayout(10, 0));
        ratioPanel.setBackground(new Color(0x12, 0x12, 0x14));

        if (i == 0) {
          JLabel unisonLabel = new JLabel("1.0 (Unison - Locked)");
          unisonLabel.setForeground(new Color(0x00, 0xff, 0xcc));
          unisonLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
          ratioPanel.add(unisonLabel, BorderLayout.WEST);
        } else {
          double currentRatio = project.getCustomRatios()[i];
          if (currentRatio <= 0.0) {
            // Initial fallback
            currentRatio = Math.pow(2.0, (double) i / notesCount);
            project.getCustomRatios()[i] = currentRatio;
          }

          JTextField textField = new JTextField(String.format("%.6f", currentRatio));
          textField.setFont(new Font("Monospaced", Font.PLAIN, 12));
          textField.setPreferredSize(new Dimension(150, 25));

          JLabel valLabel = new JLabel(String.format("(= %.4f)", currentRatio));
          valLabel.setForeground(Color.GRAY);
          valLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
          valLabel.setPreferredSize(new Dimension(90, 25));

          textField
              .getDocument()
              .addDocumentListener(
                  new DocumentListener() {
                    public void insertUpdate(DocumentEvent e) {
                      checkParse();
                    }

                    public void removeUpdate(DocumentEvent e) {
                      checkParse();
                    }

                    public void changedUpdate(DocumentEvent e) {
                      checkParse();
                    }

                    private void checkParse() {
                      try {
                        String text = textField.getText().trim();
                        double parsed = parseRatioString(text);
                        if (parsed > 0.0) {
                          project.getCustomRatios()[noteIndex] = parsed;
                          SwingUtilities.invokeLater(
                              () -> {
                                valLabel.setText(String.format("(= %.4f)", parsed));
                                valLabel.setForeground(new Color(0x00, 0xff, 0xcc));
                              });
                          triggerLiveUpdate();
                        }
                      } catch (Exception ex) {
                        SwingUtilities.invokeLater(
                            () -> {
                              valLabel.setText("(Invalid)");
                              valLabel.setForeground(Color.RED);
                            });
                      }
                    }
                  });

          ratioPanel.add(textField, BorderLayout.CENTER);
          ratioPanel.add(valLabel, BorderLayout.EAST);
        }
        row.add(ratioPanel, BorderLayout.CENTER);
      }

      adjustmentsContainer.add(row);
    }

    // Refresh layout
    adjustmentsContainer.revalidate();
    adjustmentsContainer.repaint();
    DarkComboBoxRenderer.styleComponentTree(adjustmentsContainer);
  }

  private double parseRatioString(String s) throws NumberFormatException {
    s = s.trim();
    if (s.isEmpty()) return 1.0;
    if (s.contains("/")) {
      String[] parts = s.split("/");
      if (parts.length == 2) {
        double num = Double.parseDouble(parts[0].trim());
        double den = Double.parseDouble(parts[1].trim());
        if (den != 0.0) return num / den;
      }
    }
    return Double.parseDouble(s);
  }

  private void chooseAndImportScalaFile() {
    File initialDir = PreferencesManager.getSongsDir();
    JFileChooser chooser = new JFileChooser(initialDir);
    chooser.setDialogTitle("Select Scala (.scl) Tuning File");
    chooser.setFileFilter(
        new javax.swing.filechooser.FileNameExtensionFilter("Scala Scale (.scl)", "scl", "SCL"));

    if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
      try {
        File file = chooser.getSelectedFile();
        ScalaScale scale =
            ScalaScaleParser.parse(
                Files.newInputStream(file.toPath()), file.getName().replace(".scl", ""));

        // Import into active ProjectModel
        project.importScalaScale(scale);

        // Sync dialog global controls
        isUpdatingUI = true;
        typeCombo.setSelectedIndex(1); // Custom Ratios
        notesSpinner.setValue(project.getOctaveNumMicrotonalNotes());
        baseFreqSpinner.setValue(project.getBaseFrequencyHz());
        isUpdatingUI = false;

        // Rebuild adjustments list
        rebuildAdjustmentsPanel();

        // Trigger engine update
        triggerLiveUpdate();

        JOptionPane.showMessageDialog(
            this,
            "Successfully imported Scala scale:\n"
                + scale.getName()
                + " - "
                + scale.getDescription()
                + "\n("
                + scale.getStepsCount()
                + " notes)",
            "Import Success",
            JOptionPane.INFORMATION_MESSAGE);
      } catch (Exception ex) {
        JOptionPane.showMessageDialog(
            this,
            "Failed to parse Scala file:\n" + ex.getMessage(),
            "Error",
            JOptionPane.ERROR_MESSAGE);
      }
    }
  }
}
