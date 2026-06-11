package org.chuck.deluge.ui;

import java.awt.*;
import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.midi.MidiDeviceDefinition;
import org.chuck.deluge.midi.MidiDeviceDefinitionLoader;
import org.chuck.deluge.midi.MidiService;
import org.chuck.deluge.project.PreferencesManager;

/**
 * A beautiful, premium dark-neon JDialog for dedicated physical MIDI keyboards, controllers, and
 * mappings configuration, mirroring standard professional DAW settings popups.
 */
public class SwingMidiConfigDialog extends JDialog {

  private final ChuckVM vm;
  private final BridgeContract bridge;
  private final MidiService midiService;

  private JTable mappingTable;
  private JTextField learnParamField;
  private JButton learnBtn;
  private JLabel learnStatus;
  private JPanel tableSection;

  public SwingMidiConfigDialog(
      Frame owner, ChuckVM vm, BridgeContract bridge, MidiService midiService) {
    super(owner, "MIDI Hardware Configuration & Mapping", true);
    this.vm = vm;
    this.bridge = bridge;
    this.midiService = midiService;

    setSize(580, 640);
    setLocationRelativeTo(owner);
    setResizable(true);
    getContentPane().setBackground(new Color(0x12, 0x12, 0x14));
    setLayout(new BorderLayout(0, 12));

    // 1. Header Bar
    JPanel headerPanel = new JPanel(new BorderLayout());
    headerPanel.setBackground(new Color(0x18, 0x18, 0x1c));
    headerPanel.setBorder(BorderFactory.createEmptyBorder(10, 15, 10, 15));
    JLabel titleLabel = new JLabel("🎹 MIDI HARDWARE SETTINGS  ");
    titleLabel.setFont(new Font("SansSerif", Font.BOLD, 11));
    titleLabel.setForeground(new Color(0x00, 0xff, 0xcc));
    headerPanel.add(titleLabel, BorderLayout.WEST);

    JLabel descLabel = new JLabel("Select physical input ports and map parameter JNI controls");
    descLabel.setFont(new Font("SansSerif", Font.PLAIN, 9));
    descLabel.setForeground(Color.GRAY);
    headerPanel.add(descLabel, BorderLayout.EAST);
    add(headerPanel, BorderLayout.NORTH);

    if (midiService == null) {
      JLabel noService = new JLabel("MIDI service not available (testing mode)");
      noService.setForeground(new Color(0x88, 0x88, 0x88));
      noService.setFont(new Font("SansSerif", Font.BOLD, 12));
      noService.setHorizontalAlignment(SwingConstants.CENTER);
      add(noService, BorderLayout.CENTER);
      return;
    }

    // 2. Content Center Pane
    JPanel centerPane = new JPanel(new BorderLayout(0, 15));
    centerPane.setBackground(new Color(0x12, 0x12, 0x14));
    centerPane.setBorder(BorderFactory.createEmptyBorder(5, 15, 15, 15));

    // ── Device Selection ──
    java.util.List<MidiDeviceDefinition> devices = MidiDeviceDefinitionLoader.loadAll();
    JComboBox<MidiDeviceDefinition> deviceCombo = new JComboBox<>();
    deviceCombo.addItem(null); // None
    for (var d : devices) {
      deviceCombo.addItem(d);
    }

    MidiDeviceDefinition currentDef = midiService.getDeviceDefinition();
    if (currentDef != null) {
      for (int i = 0; i < deviceCombo.getItemCount(); i++) {
        var item = deviceCombo.getItemAt(i);
        if (item != null && item.getId().equals(currentDef.getId())) {
          deviceCombo.setSelectedIndex(i);
          break;
        }
      }
    }

    deviceCombo.addActionListener(
        e -> {
          MidiDeviceDefinition selected = (MidiDeviceDefinition) deviceCombo.getSelectedItem();
          midiService.setDeviceDefinition(selected);
          rebuildMidiTable();
        });
    styleCombo(deviceCombo);
    deviceCombo.setRenderer(
        new DefaultListCellRenderer() {
          @Override
          public Component getListCellRendererComponent(
              JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value == null) {
              setText("— None —");
            } else if (value instanceof MidiDeviceDefinition d) {
              setText(d.getName() != null ? d.getName() : d.getId());
            }
            return this;
          }
        });

    JPanel devicePanel = new JPanel(new BorderLayout(8, 0));
    devicePanel.setBackground(new Color(0x12, 0x12, 0x14));
    JLabel deviceLabel = new JLabel("Device Port:");
    styleLabel(deviceLabel, true);
    devicePanel.add(deviceLabel, BorderLayout.WEST);
    devicePanel.add(deviceCombo, BorderLayout.CENTER);
    centerPane.add(devicePanel, BorderLayout.NORTH);

    // ── CC Mappings Table Section ──
    tableSection = new JPanel(new BorderLayout(0, 5));
    tableSection.setBackground(new Color(0x12, 0x12, 0x14));

    mappingTable = new JTable();
    styleTable(mappingTable);
    rebuildTableContent();

    JScrollPane tableScroll = new JScrollPane(mappingTable);
    tableScroll.setBackground(new Color(0x12, 0x12, 0x14));
    tableScroll.getViewport().setBackground(new Color(0x12, 0x12, 0x14));
    tableScroll.setBorder(BorderFactory.createLineBorder(new Color(0x2d, 0x2d, 0x32)));
    tableSection.add(tableScroll, BorderLayout.CENTER);

    // ── Learn Controls ──
    JPanel learnSection = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 5));
    learnSection.setBackground(new Color(0x12, 0x12, 0x14));
    JLabel learnLabel = new JLabel("Learn CC:");
    styleLabel(learnLabel, true);
    learnSection.add(learnLabel);

    learnParamField = new JTextField(16);
    learnParamField.setBackground(new Color(0x18, 0x18, 0x1c));
    learnParamField.setForeground(Color.WHITE);
    learnParamField.setCaretColor(Color.WHITE);
    learnParamField.setFont(new Font("SansSerif", Font.PLAIN, 11));
    learnParamField.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(0x2d, 0x2d, 0x32), 1),
            BorderFactory.createEmptyBorder(3, 5, 3, 5)));
    learnParamField.setToolTipText("Enter local parameter name (e.g. g_master_vol)");
    learnSection.add(learnParamField);

    learnBtn = new JButton("START LEARN");
    styleOutlineButton(learnBtn, new Color(0x1e, 0x32, 0x32), new Color(0x00, 0xff, 0xcc));
    learnBtn.setFont(new Font("SansSerif", Font.BOLD, 10));

    learnStatus = new JLabel("");
    learnStatus.setForeground(new Color(0xff, 0xcc, 0x00));
    learnStatus.setFont(new Font("SansSerif", Font.PLAIN, 10));

    learnBtn.addActionListener(
        e -> {
          String param = learnParamField.getText().trim();
          if (param.isEmpty()) {
            learnStatus.setText("Enter target parameter name");
            return;
          }
          midiService.startLearn(param);
          learnStatus.setText("Waiting for controller CC sweeps on " + param + "...");
          learnBtn.setEnabled(false);

          Timer timer =
              new Timer(
                  10000,
                  ev -> {
                    learnBtn.setEnabled(true);
                    if (midiService.isLearning()) {
                      midiService.cancelLearn();
                      learnStatus.setText("Learn timed out");
                    } else {
                      learnStatus.setText("Learned successfully!");
                      rebuildTableContent();
                    }
                  });
          timer.setRepeats(false);
          timer.start();
        });

    learnSection.add(learnBtn);
    learnSection.add(learnStatus);
    tableSection.add(learnSection, BorderLayout.SOUTH);
    centerPane.add(tableSection, BorderLayout.CENTER);

    // ── Follow Mode Controls ──
    JPanel followPanel = new JPanel();
    followPanel.setLayout(new BoxLayout(followPanel, BoxLayout.Y_AXIS));
    followPanel.setBackground(new Color(0x18, 0x18, 0x1c));
    followPanel.setBorder(
        BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(0x2d, 0x2d, 0x32)),
            "MIDI Follow Mode Configuration",
            TitledBorder.LEFT,
            TitledBorder.TOP,
            new Font("SansSerif", Font.BOLD, 10),
            Color.LIGHT_GRAY));

    JCheckBox followEnable = new JCheckBox("Enable Track Follow Modes");
    followEnable.setForeground(Color.LIGHT_GRAY);
    followEnable.setBackground(new Color(0x18, 0x18, 0x1c));
    followEnable.setFont(new Font("SansSerif", Font.PLAIN, 10));
    followEnable.setSelected(PreferencesManager.get("midi.follow.enabled", "true").equals("true"));
    followEnable.addActionListener(
        e -> {
          PreferencesManager.set("midi.follow.enabled", String.valueOf(followEnable.isSelected()));
        });
    followPanel.add(followEnable);
    followPanel.add(Box.createVerticalStrut(6));

    String[] midiChannels = {
      "1", "2", "3", "4", "5", "6", "7", "8",
      "9", "10", "11", "12", "13", "14", "15", "16"
    };
    String[] trackLabels = {
      "Track 1", "Track 2", "Track 3", "Track 4", "Track 5", "Track 6", "Track 7", "Track 8",
      "Track 9", "Track 10", "Track 11", "Track 12", "Track 13", "Track 14", "Track 15", "Track 16"
    };
    char[] followLabels = {'A', 'B', 'C'};

    for (int i = 0; i < 3; i++) {
      final char fLabel = followLabels[i];
      JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
      row.setBackground(new Color(0x18, 0x18, 0x1c));
      JLabel fl = new JLabel("Channel " + fLabel + ":");
      styleLabel(fl, true);

      JComboBox<String> chCombo = new JComboBox<>(midiChannels);
      int savedCh = Integer.parseInt(PreferencesManager.get("midi.follow.ch" + fLabel, "1"));
      chCombo.setSelectedIndex(savedCh - 1);
      styleCombo(chCombo);
      chCombo.setPreferredSize(new Dimension(50, 20));
      chCombo.addActionListener(
          e -> {
            PreferencesManager.set(
                "midi.follow.ch" + fLabel, String.valueOf(chCombo.getSelectedIndex() + 1));
          });

      JComboBox<String> trCombo = new JComboBox<>(trackLabels);
      int savedTr =
          Integer.parseInt(PreferencesManager.get("midi.follow.track" + fLabel, String.valueOf(i)));
      trCombo.setSelectedIndex(Math.min(savedTr, 15));
      styleCombo(trCombo);
      trCombo.setPreferredSize(new Dimension(110, 20));
      trCombo.addActionListener(
          e -> {
            PreferencesManager.set(
                "midi.follow.track" + fLabel, String.valueOf(trCombo.getSelectedIndex()));
          });

      JLabel midiChLbl = new JLabel("MIDI Ch:");
      styleLabel(midiChLbl, false);
      JLabel trLbl = new JLabel("→ Track:");
      styleLabel(trLbl, false);

      row.add(fl);
      row.add(midiChLbl);
      row.add(chCombo);
      row.add(trLbl);
      row.add(trCombo);
      followPanel.add(row);
    }

    centerPane.add(followPanel, BorderLayout.SOUTH);
    add(centerPane, BorderLayout.CENTER);

    // 3. Footer Action Close Row
    JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 8));
    footer.setBackground(new Color(0x18, 0x18, 0x1c));
    JButton closeBtn = new JButton("CLOSE");
    styleOutlineButton(closeBtn, new Color(0x23, 0x23, 0x28), Color.WHITE);
    closeBtn.setFont(new Font("SansSerif", Font.BOLD, 10));
    closeBtn.addActionListener(e -> setVisible(false));
    footer.add(closeBtn);
    add(footer, BorderLayout.SOUTH);

    DarkComboBoxRenderer.styleComponentTree(this);
  }

  private void rebuildMidiTable() {
    rebuildTableContent();
  }

  private void rebuildTableContent() {
    java.util.Map<String, Integer> mappings = midiService.getMappings();
    String[][] tableData = new String[mappings.size()][3];
    int rowIdx = 0;
    for (var entry : mappings.entrySet()) {
      tableData[rowIdx][0] = entry.getKey();
      tableData[rowIdx][1] = "CC #" + entry.getValue();
      tableData[rowIdx][2] = "ACTIVE";
      rowIdx++;
    }
    if (tableData.length == 0) {
      tableData = new String[][] {{"— No midi mappings cabled —", "", ""}};
    }
    String[] cols = {"JNI Parameter Variable", "MIDI CC Signal", "Connection Status"};

    DefaultTableModel model =
        new DefaultTableModel(tableData, cols) {
          @Override
          public boolean isCellEditable(int row, int column) {
            return false;
          }
        };
    mappingTable.setModel(model);
  }

  private void styleLabel(JLabel label, boolean bold) {
    label.setForeground(Color.LIGHT_GRAY);
    label.setFont(new Font("SansSerif", bold ? Font.BOLD : Font.PLAIN, 10));
  }

  private void styleCombo(JComboBox<?> combo) {
    combo.setBackground(new Color(0x25, 0x25, 0x2a));
    combo.setForeground(Color.WHITE);
    combo.setFont(new Font("SansSerif", Font.PLAIN, 10));
    combo.setBorder(BorderFactory.createLineBorder(new Color(0x2d, 0x2d, 0x32), 1));
  }

  private void styleTable(JTable table) {
    table.setBackground(new Color(0x18, 0x18, 0x1c));
    table.setForeground(Color.WHITE);
    table.setFont(new Font("SansSerif", Font.PLAIN, 10));
    table.setRowHeight(20);
    table.setGridColor(new Color(0x2d, 0x2d, 0x32));
    table.getTableHeader().setBackground(new Color(0x2d, 0x2d, 0x32));
    table.getTableHeader().setForeground(Color.LIGHT_GRAY);
    table.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 10));
    table.setSelectionBackground(new Color(0x00, 0xff, 0xcc, 0x33));
    table.setSelectionForeground(Color.WHITE);
    table.setShowGrid(true);
  }

  private void styleOutlineButton(JButton btn, Color bg, Color fg) {
    btn.setContentAreaFilled(false);
    btn.setOpaque(true);
    btn.setFocusPainted(false);
    btn.setBackground(bg);
    btn.setForeground(fg);
    btn.setFont(new Font("SansSerif", Font.BOLD, 10));
    btn.setBorder(BorderFactory.createLineBorder(fg, 1));
    btn.setMargin(new Insets(3, 8, 3, 8));
  }
}
