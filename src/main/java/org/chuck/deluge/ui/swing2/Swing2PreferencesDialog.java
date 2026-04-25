package org.chuck.deluge.ui.swing2;

import java.awt.*;
import javax.swing.*;
import org.chuck.deluge.project.PreferencesManager;

public class Swing2PreferencesDialog extends JDialog {
  public Swing2PreferencesDialog(Frame parent) {
    super(parent, "Preferences", true);
    setSize(400, 300);
    setLocationRelativeTo(parent);
    setLayout(new GridLayout(3, 2, 10, 10));

    add(new JLabel(" Screen Resolution:"));
    String[] resOptions = {"FHD", "QHD", "4K"};
    JComboBox<String> resCombo = new JComboBox<>(resOptions);
    resCombo.setSelectedItem(PreferencesManager.get("screen.resolution", "QHD"));
    add(resCombo);

    JButton cancelBtn = new JButton("Cancel");
    cancelBtn.addActionListener(e -> dispose());
    add(cancelBtn);

    JButton okBtn = new JButton("OK");
    okBtn.addActionListener(
        e -> {
          PreferencesManager.set("screen.resolution", (String) resCombo.getSelectedItem());
          dispose();
        });
    add(okBtn);
  }
}
