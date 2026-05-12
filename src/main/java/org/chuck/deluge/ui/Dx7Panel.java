package org.chuck.deluge.ui;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.SynthTrackModel;
import org.chuck.audio.util.Dx7Patch;

/** DX7 tab: patch info, LFO, 6-operator table, .syx loader. Only functional when synthMode==1. */
public class Dx7Panel extends JPanel {

  public Dx7Panel(SynthTrackModel model, ChuckVM vm, BridgeContract bridge, int trackIndex, Window owner, Runnable reloadCallback) {
    super(new BorderLayout(4, 4));
    setBackground(new Color(0x22, 0x22, 0x22));

    JPanel content = new JPanel(new GridBagLayout());
    content.setBackground(new Color(0x22, 0x22, 0x22));
    GridBagConstraints c = new GridBagConstraints();
    c.fill = GridBagConstraints.HORIZONTAL;
    c.insets = new Insets(4, 8, 4, 8);
    c.anchor = GridBagConstraints.WEST;
    int row = 0;

    // ── Load .syx button ──
    c.gridx = 0; c.gridy = row; c.gridwidth = 4;
    JButton loadSyxBtn = new JButton("Load .syx (DX7 Patch File)");
    loadSyxBtn.setToolTipText("Open a Roland SysEx bulk dump (.syx) containing DX7 voice patches");
    loadSyxBtn.addActionListener(e -> {
      JFileChooser fc = new JFileChooser();
      fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("DX7 SysEx (*.syx)", "syx"));
      if (fc.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) {
        try {
          java.util.List<org.chuck.audio.util.Dx7Patch> patches =
              org.chuck.deluge.xml.Dx7SyxParser.parseSyx(fc.getSelectedFile());
          if (!patches.isEmpty()) {
            applyDx7Patch(model, vm, bridge, trackIndex, patches.get(0));
            reloadCallback.run();
          }
        } catch (Exception ex) {
          JOptionPane.showMessageDialog(owner,
              "Failed to load .syx: " + ex.getMessage(),
              "Parse Error", JOptionPane.ERROR_MESSAGE);
        }
      }
    });
    content.add(loadSyxBtn, c); row++;
    c.gridwidth = 1;

    // ── Patch Name ──
    c.gridx = 0; c.gridy = row;
    content.add(SwingSynthConfigDialog.label("Patch Name:"), c);
    c.gridx = 1; c.gridwidth = 3;
    JTextField patchNameField = new JTextField(16);
    patchNameField.setBackground(new Color(0x33, 0x33, 0x33));
    patchNameField.setForeground(Color.WHITE);
    String curPatch = model.getDx7Patch();
    if (curPatch != null && !curPatch.isEmpty()) {
      try {
        patchNameField.setText(org.chuck.audio.util.Dx7Patch.fromHex(curPatch).name());
      } catch (Exception ignored) {}
    }
    patchNameField.setEditable(false);
    patchNameField.setToolTipText("Patch name from the loaded DX7 SysEx data (read-only)");
    content.add(patchNameField, c); row++;
    c.gridwidth = 1;

    // ── Patch globals ──
    c.gridx = 0; c.gridy = row; c.gridwidth = 4;
    content.add(SwingSynthConfigDialog.sectionLabel("PATCH GLOBALS"), c); row++;
    c.gridwidth = 1;

    c.gridx = 0; c.gridy = row;
    content.add(SwingSynthConfigDialog.label("Algorithm:"), c);
    c.gridx = 1;
    JLabel algoVal = new JLabel(curPatch != null ? String.valueOf(model.getSynthAlgorithm()) : "-");
    algoVal.setForeground(Color.CYAN);
    content.add(algoVal, c);

    c.gridx = 2;
    content.add(SwingSynthConfigDialog.label("Feedback:"), c);
    c.gridx = 3;
    int fbInit = curPatch != null ? getPatchByte(curPatch, Dx7Patch.OFF_FEEDBACK) & 0x07 : 0;
    JSlider fbSlider = new JSlider(0, 7, fbInit);
    fbSlider.setBackground(new Color(0x22, 0x22, 0x22));
    JLabel fbVal = new JLabel(String.valueOf(fbInit));
    fbVal.setForeground(Color.CYAN);
    fbSlider.addChangeListener(ev -> {
      setPatchByte(model, curPatch, Dx7Patch.OFF_FEEDBACK, fbSlider.getValue());
      fbVal.setText(String.valueOf(fbSlider.getValue()));
    });
    JPanel fbPanel = new JPanel(new BorderLayout(4, 0));
    fbPanel.setBackground(new Color(0x22, 0x22, 0x22));
    fbPanel.add(fbSlider, BorderLayout.CENTER);
    fbPanel.add(fbVal, BorderLayout.EAST);
    content.add(fbPanel, c); row++;

    // Transpose
    c.gridx = 0; c.gridy = row;
    content.add(SwingSynthConfigDialog.label("Transpose:"), c);
    c.gridx = 1; c.gridwidth = 3;
    int transpInit = curPatch != null ? getPatchByte(curPatch, Dx7Patch.OFF_TRANSPOSE) : 64;
    JSlider transpSlider = new JSlider(0, 127, transpInit);
    transpSlider.setBackground(new Color(0x22, 0x22, 0x22));
    JLabel transpVal = new JLabel(String.valueOf(transpInit));
    transpVal.setForeground(Color.CYAN);
    transpSlider.addChangeListener(ev -> {
      setPatchByte(model, curPatch, Dx7Patch.OFF_TRANSPOSE, transpSlider.getValue());
      transpVal.setText(String.valueOf(transpSlider.getValue()));
    });
    JPanel transpPanel = new JPanel(new BorderLayout(4, 0));
    transpPanel.setBackground(new Color(0x22, 0x22, 0x22));
    transpPanel.add(transpSlider, BorderLayout.CENTER);
    transpPanel.add(transpVal, BorderLayout.EAST);
    content.add(transpPanel, c); row++;

    // ── DX7 LFO ──
    c.gridx = 0; c.gridy = row; c.gridwidth = 4;
    content.add(SwingSynthConfigDialog.sectionLabel("DX7 LFO"), c); row++;
    c.gridwidth = 1;

    addDx7SliderRow(content, c, "Speed:", 0, 99, curPatch, Dx7Patch.OFF_LFO_SPEED, model);
    addDx7SliderRow(content, c, "Delay:", 0, 99, curPatch, Dx7Patch.OFF_LFO_DELAY, model);
    addDx7SliderRow(content, c, "PMod Depth:", 0, 99, curPatch, Dx7Patch.OFF_PMOD_DEPTH, model);
    addDx7SliderRow(content, c, "AMod Depth:", 0, 99, curPatch, Dx7Patch.OFF_AMOD_DEPTH, model);

    String[] lfoWaves = {"TRIANGLE", "SAW DOWN", "SAW UP", "SQUARE", "SINE", "S&H"};
    addDx7ComboRow(content, c, "Waveform:", lfoWaves, curPatch, Dx7Patch.OFF_LFO_WAVEFORM, model);

    addDx7SliderRow(content, c, "Sync:", 0, 1, curPatch, Dx7Patch.OFF_LFO_SYNC, model);

    // ── Operator table ──
    c.gridx = 0; c.gridy = row; c.gridwidth = 4;
    content.add(SwingSynthConfigDialog.sectionLabel("OPERATORS (OP1-OP6)"), c); row++;
    c.gridwidth = 1;

    String[] opCols = {"OP", "ON", "Lv", "Md", "Crse", "Fine", "Det", "R1", "R2", "R3", "R4", "L1", "L2", "L3", "L4", "VS", "AM"};
    c.gridx = 0; c.gridy = row;
    for (int ci = 0; ci < opCols.length; ci++) {
      c.gridx = ci;
      content.add(SwingSynthConfigDialog.headerLabel(opCols[ci]), c);
    }
    row++;

    List<JPanel> opPanels = new ArrayList<>();
    for (int opIdx = 0; opIdx < 6; opIdx++) {
      final int op = opIdx;
      final int opOff = op * 21;
      JPanel opRow = new JPanel(new GridBagLayout());
      opRow.setBackground(new Color(0x22, 0x22, 0x22));
      opRow.setFocusCycleRoot(true);
      opRow.setFocusTraversalPolicyProvider(true);

      // OP label
      c.gridx = 0; c.gridy = 0;
      opRow.add(SwingSynthConfigDialog.label("OP" + (op + 1)), c);

      // ON/OFF toggle
      c.gridx = 1;
      String curPatchInner = model.getDx7Patch();
      boolean opOn = curPatchInner != null && ((getPatchByte(curPatchInner, Dx7Patch.OFF_OP_SWITCH) >> op) & 1) != 0;
      JCheckBox opOnBox = new JCheckBox("", opOn);
      opOnBox.setBackground(new Color(0x22, 0x22, 0x22));
      opOnBox.addActionListener(ev -> {
        byte[] raw = getCurrentRaw(model, model.getDx7Patch());
        if (raw == null) return;
        if (opOnBox.isSelected()) {
          raw[Dx7Patch.OFF_OP_SWITCH] |= (byte) (1 << op);
        } else {
          raw[Dx7Patch.OFF_OP_SWITCH] &= (byte) ~(1 << op);
        }
        model.setDx7Patch(Dx7Patch.bytesToHex(raw));
      });
      opRow.add(opOnBox, c);

      // Output level (0-99)
      addDx7OpSliderTo(opRow, c, 2, op, 16, 0, 99, model);
      // Mode (0=ratio, 1=fixed)
      addDx7OpSliderTo(opRow, c, 3, op, 17, 0, 1, model);
      // Coarse (0-31)
      addDx7OpSliderTo(opRow, c, 4, op, 18, 0, 31, model);
      // Fine (0-99)
      addDx7OpSliderTo(opRow, c, 5, op, 19, 0, 99, model);
      // Detune (0-14)
      addDx7OpSliderTo(opRow, c, 6, op, 20, 0, 14, model);
      // EG R1-R4 (0-99)
      for (int eg = 0; eg < 4; eg++) {
        addDx7OpSliderTo(opRow, c, 7 + eg, op, eg, 0, 99, model);
      }
      // EG L1-L4 (0-99)
      for (int eg = 0; eg < 4; eg++) {
        addDx7OpSliderTo(opRow, c, 11 + eg, op, 4 + eg, 0, 99, model);
      }
      // Velocity sensitivity (0-7)
      addDx7OpSliderTo(opRow, c, 15, op, 15, 0, 7, model);
      // Amp mod sensitivity (0-3)
      addDx7OpSliderTo(opRow, c, 16, op, 14, 0, 3, model);

      opRow.setFocusTraversalPolicy(new FocusTraversalPolicy() {
        @Override
        public Component getComponentAfter(Container focusCycleRoot, Component aComponent) {
          List<Component> order = getAllOrder();
          int idx = order.indexOf(aComponent);
          return order.get((idx + 1) % order.size());
        }
        @Override
        public Component getComponentBefore(Container focusCycleRoot, Component aComponent) {
          List<Component> order = getAllOrder();
          int idx = order.indexOf(aComponent);
          return order.get((idx - 1 + order.size()) % order.size());
        }
        @Override
        public Component getFirstComponent(Container focusCycleRoot) {
          List<Component> order = getAllOrder();
          return order.isEmpty() ? null : order.get(0);
        }
        @Override
        public Component getLastComponent(Container focusCycleRoot) {
          List<Component> order = getAllOrder();
          return order.isEmpty() ? null : order.get(order.size() - 1);
        }
        @Override
        public Component getDefaultComponent(Container focusCycleRoot) {
          return getFirstComponent(focusCycleRoot);
        }
        private List<Component> getAllOrder() {
          List<Component> all = new ArrayList<>();
          for (Component child : opRow.getComponents()) {
            if (child instanceof JCheckBox) all.add(child);
            else if (child instanceof JPanel) {
              for (Component sub : ((JPanel) child).getComponents()) {
                if (sub instanceof JSlider || sub instanceof JLabel) all.add(sub);
              }
            }
          }
          return all;
        }
      });

      c.gridx = 0; c.gridy = row;
      c.gridwidth = opCols.length;
      content.add(opRow, c);
      opPanels.add(opRow);
      row++;
    }

    // ── Keyboard focus cycling across operators ──
    KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventPostProcessor(e -> {
      if (e.getID() != KeyEvent.KEY_PRESSED) return false;
      if (e.getKeyCode() != KeyEvent.VK_TAB) return false;
      Component focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
      if (focusOwner == null) return false;
      int curOp = -1;
      for (int i = 0; i < opPanels.size(); i++) {
        if (SwingUtilities.isDescendingFrom(focusOwner, opPanels.get(i))) {
          curOp = i;
          break;
        }
      }
      if (curOp < 0) return false;
      int nextOp;
      if (e.isShiftDown()) {
        nextOp = (curOp - 1 + opPanels.size()) % opPanels.size();
      } else {
        nextOp = (curOp + 1) % opPanels.size();
      }
      e.consume();
      JPanel nextPanel = opPanels.get(nextOp);
      Component first = nextPanel.getFocusTraversalPolicy().getDefaultComponent(nextPanel);
      if (first != null) first.requestFocus();
      return true;
    });
    JScrollPane scroll = new JScrollPane(content);
    scroll.setPreferredSize(new Dimension(900, 600));
    scroll.getViewport().setBackground(new Color(0x22, 0x22, 0x22));
    add(scroll, BorderLayout.CENTER);
  }

  /** Helper: add a compact slider cell for a DX7 operator byte field. */
  private void addDx7OpSlider(JPanel panel, GridBagConstraints c, int col, int opOff, int fieldOff,
      int min, int max, SynthTrackModel model) {
    c.gridx = col;
    String curPatch = model.getDx7Patch();
    int val = min;
    if (curPatch != null && !curPatch.isEmpty()) {
      byte[] raw = Dx7Patch.hexToBytes(curPatch);
      int idx = opOff * 21 + fieldOff;
      if (idx >= 0 && idx < raw.length) {
        val = raw[idx] & 0xFF;
      }
    }
    JSlider slider = new JSlider(min, max, Math.max(min, Math.min(max, val)));
    slider.setBackground(new Color(0x22, 0x22, 0x22));
    slider.setPreferredSize(new Dimension(50, 22));
    slider.setPaintTicks(false);
    JLabel valLabel = new JLabel(String.valueOf(val));
    valLabel.setForeground(Color.CYAN);
    valLabel.setPreferredSize(new Dimension(28, 20));
    valLabel.setFont(valLabel.getFont().deriveFont(9f));
    slider.addChangeListener(ev -> {
      byte[] raw = getCurrentRaw(model, model.getDx7Patch());
      if (raw == null) return;
      int idx = opOff * 21 + fieldOff;
      if (idx >= 0 && idx < raw.length) {
        raw[idx] = (byte) slider.getValue();
        model.setDx7Patch(Dx7Patch.bytesToHex(raw));
      }
      valLabel.setText(String.valueOf(slider.getValue()));
    });
    JPanel cell = new JPanel(new BorderLayout(0, 0));
    cell.setBackground(new Color(0x22, 0x22, 0x22));
    cell.add(slider, BorderLayout.CENTER);
    cell.add(valLabel, BorderLayout.EAST);
    panel.add(cell, c);
  }

  /** Helper: add a compact slider cell to an existing operator row. */
  private void addDx7OpSliderTo(JPanel target, GridBagConstraints c, int col, int opOff, int fieldOff,
      int min, int max, SynthTrackModel model) {
    c.gridx = col;
    String curPatch = model.getDx7Patch();
    int val = min;
    if (curPatch != null && !curPatch.isEmpty()) {
      byte[] raw = Dx7Patch.hexToBytes(curPatch);
      int idx = opOff * 21 + fieldOff;
      if (idx >= 0 && idx < raw.length) {
        val = raw[idx] & 0xFF;
      }
    }
    JSlider slider = new JSlider(min, max, Math.max(min, Math.min(max, val)));
    slider.setBackground(new Color(0x22, 0x22, 0x22));
    slider.setPreferredSize(new Dimension(50, 22));
    slider.setPaintTicks(false);
    JLabel valLabel = new JLabel(String.valueOf(val));
    valLabel.setForeground(Color.CYAN);
    valLabel.setPreferredSize(new Dimension(28, 20));
    valLabel.setFont(valLabel.getFont().deriveFont(9f));
    slider.addChangeListener(ev -> {
      byte[] raw = getCurrentRaw(model, model.getDx7Patch());
      if (raw == null) return;
      int idx = opOff * 21 + fieldOff;
      if (idx >= 0 && idx < raw.length) {
        raw[idx] = (byte) slider.getValue();
        model.setDx7Patch(Dx7Patch.bytesToHex(raw));
      }
      valLabel.setText(String.valueOf(slider.getValue()));
    });
    JPanel cell = new JPanel(new BorderLayout(0, 0));
    cell.setBackground(new Color(0x22, 0x22, 0x22));
    cell.add(slider, BorderLayout.CENTER);
    cell.add(valLabel, BorderLayout.EAST);
    target.add(cell, c);
  }

  /** Helper: add a slider row for a DX7 global byte (patch offset). */
  private void addDx7SliderRow(JPanel panel, GridBagConstraints c,
      String labelText, int min, int max, String curPatch, int offset, SynthTrackModel model) {
    int val = min;
    if (curPatch != null && !curPatch.isEmpty()) {
      byte[] raw = Dx7Patch.hexToBytes(curPatch);
      if (offset >= 0 && offset < raw.length) {
        val = raw[offset] & 0xFF;
      }
    }
    int clamped = Math.max(min, Math.min(max, val));
    c.gridx = 0; c.gridy++; c.gridwidth = 1;
    panel.add(SwingSynthConfigDialog.label(labelText), c);
    c.gridx = 1; c.gridwidth = 3;
    JSlider slider = new JSlider(min, max, clamped);
    slider.setBackground(new Color(0x22, 0x22, 0x22));
    JLabel valLabel = new JLabel(String.valueOf(clamped));
    valLabel.setForeground(Color.CYAN);
    slider.addChangeListener(ev -> {
      setPatchByte(model, curPatch, offset, slider.getValue());
      valLabel.setText(String.valueOf(slider.getValue()));
    });
    JPanel rowPanel = new JPanel(new BorderLayout(4, 0));
    rowPanel.setBackground(new Color(0x22, 0x22, 0x22));
    rowPanel.add(slider, BorderLayout.CENTER);
    rowPanel.add(valLabel, BorderLayout.EAST);
    panel.add(rowPanel, c);
  }

  /** Helper: add a combo box row for a DX7 global byte (patch offset). */
  private void addDx7ComboRow(JPanel panel, GridBagConstraints c,
      String labelText, String[] options, String curPatch, int offset, SynthTrackModel model) {
    int val = 0;
    if (curPatch != null && !curPatch.isEmpty()) {
      byte[] raw = Dx7Patch.hexToBytes(curPatch);
      if (offset >= 0 && offset < raw.length) {
        val = raw[offset] & 0xFF;
      }
    }
    int idx = Math.max(0, Math.min(options.length - 1, val));
    c.gridx = 0; c.gridy++; c.gridwidth = 1;
    panel.add(SwingSynthConfigDialog.label(labelText), c);
    c.gridx = 1; c.gridwidth = 3;
    JComboBox<String> combo = new JComboBox<>(options);
    combo.setSelectedIndex(idx);
    combo.setBackground(new Color(0x33, 0x33, 0x33));
    combo.setForeground(Color.WHITE);
    combo.addActionListener(ev -> {
      setPatchByte(model, curPatch, offset, combo.getSelectedIndex());
    });
    panel.add(combo, c);
  }

  /** Get a byte from the DX7 patch hex string. */
  private static int getPatchByte(String hex, int offset) {
    if (hex == null || hex.length() < (offset + 1) * 2) return 0;
    byte[] raw = Dx7Patch.hexToBytes(hex);
    return raw[offset] & 0xFF;
  }

  /** Set a byte in the DX7 patch hex string and update the model. */
  private static void setPatchByte(SynthTrackModel model, String curHex, int offset, int value) {
    byte[] raw = getCurrentRaw(model, curHex);
    if (raw == null) return;
    if (offset >= 0 && offset < raw.length) {
      raw[offset] = (byte) (value & 0xFF);
      model.setDx7Patch(Dx7Patch.bytesToHex(raw));
    }
  }

  /** Get mutable raw bytes from the current DX7 patch (or from model). */
  private static byte[] getCurrentRaw(SynthTrackModel model, String fallbackHex) {
    String hex = model.getDx7Patch();
    if (hex == null || hex.isEmpty()) hex = fallbackHex;
    if (hex == null || hex.isEmpty()) return null;
    return Dx7Patch.hexToBytes(hex);
  }

  /** Apply a Dx7Patch to the model and push to the bridge. */
  private static void applyDx7Patch(SynthTrackModel model, ChuckVM vm,
      BridgeContract bridge, int trackIndex, org.chuck.audio.util.Dx7Patch patch) {
    String hex = org.chuck.deluge.xml.Dx7SyxParser.patchToHex(patch);
    model.setDx7Patch(hex);
    model.setSynthMode(1);
    model.setSynthAlgorithm(patch.algorithm());
    bridge.setSynthMode(trackIndex, 1);
    bridge.setSynthAlgo(trackIndex, patch.algorithm());
    String globalName = "g_dx7_patch_" + trackIndex;
    vm.setGlobalString(globalName, hex);
    vm.setGlobalInt("g_dx7_opSwitch_" + trackIndex, patch.opSwitch());
  }
}
