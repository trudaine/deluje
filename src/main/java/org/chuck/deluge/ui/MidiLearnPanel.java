package org.chuck.deluge.ui;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import org.chuck.deluge.midi.MidiInputRouter;
import org.chuck.deluge.midi.MidiService;

/**
 * Modern, interactive MIDI CC Learn and Follow configuration tab panel. Allows users to easily
 * learn, map, and clear hardware MIDI CC controller assignments to primary Deluge parameters.
 */
public class MidiLearnPanel extends JPanel {

  private static record ParameterTarget(String globalName, String displayName) {}

  private final List<ParameterTarget> targets = new ArrayList<>();
  private final List<RowPanel> rowPanels = new ArrayList<>();
  private final Timer refreshTimer;

  private String activeLearningParam = null;
  private JToggleButton activeLearningButton = null;

  public MidiLearnPanel() {
    setLayout(new BorderLayout(0, 10));
    setBackground(new Color(0x1a, 0x1a, 0x1a));
    setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

    // Build Help Header Panel
    JPanel headerPanel = new JPanel(new BorderLayout(15, 0));
    headerPanel.setBackground(new Color(0x2d, 0x2d, 0x2d));
    headerPanel.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));

    JLabel helpLabel =
        new JLabel(
            "<html>💡 <b>MIDI LEARN MATRIX:</b> Click <b>'LEARN'</b> and wiggle a hardware knob to bind it. MPE slides (Y/Z) are routed automatically!</html>");
    helpLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
    helpLabel.setForeground(new Color(0xff, 0xcc, 0x00)); // Yellow text tip
    headerPanel.add(helpLabel, BorderLayout.CENTER);

    JButton resetBtn = new JButton("RESET ALL TO DELUGE DEFAULT CCs");
    resetBtn.setBackground(new Color(0x3e, 0x5e, 0x3e));
    resetBtn.setForeground(Color.WHITE);
    resetBtn.setFocusable(false);
    resetBtn.addActionListener(
        e -> {
          MidiInputRouter router = getRouter();
          if (router != null) {
            router.resetToDefaults();
            refreshMappings();
            JOptionPane.showMessageDialog(
                this,
                "Standard Deluge factory MIDI CC mappings restored!",
                "MIDI Learn Success",
                JOptionPane.INFORMATION_MESSAGE);
          }
        });
    headerPanel.add(resetBtn, BorderLayout.EAST);
    add(headerPanel, BorderLayout.NORTH);

    // Initialize the primary automatable parameters set
    targets.add(new ParameterTarget("g_sp_volume", "Volume"));
    targets.add(new ParameterTarget("g_sp_pan", "Pan"));
    targets.add(new ParameterTarget("g_sp_lpf_freq", "LPF Frequency"));
    targets.add(new ParameterTarget("g_sp_lpf_res", "LPF Resonance"));
    targets.add(new ParameterTarget("g_sp_lpf_morph", "LPF Morph"));
    targets.add(new ParameterTarget("g_sp_hpf_freq", "HPF Frequency"));
    targets.add(new ParameterTarget("g_sp_hpf_res", "HPF Resonance"));
    targets.add(new ParameterTarget("g_sp_hpf_morph", "HPF Morph"));
    targets.add(new ParameterTarget("g_sp_delay_rate", "Delay Rate"));
    targets.add(new ParameterTarget("g_sp_delay_feedback", "Delay Feedback"));
    targets.add(new ParameterTarget("g_sp_reverb_amount", "Reverb Amount"));
    targets.add(new ParameterTarget("g_sp_eq_bass", "EQ Bass"));
    targets.add(new ParameterTarget("g_sp_eq_treble", "EQ Treble"));

    // Build scrollable list
    JPanel listPanel = new JPanel();
    listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
    listPanel.setBackground(SwingSynthConfigDialog.BG_CARD);

    for (ParameterTarget target : targets) {
      RowPanel rp = new RowPanel(target);
      rowPanels.add(rp);
      listPanel.add(rp);
      listPanel.add(Box.createRigidArea(new Dimension(0, 4)));
    }

    JScrollPane scroll = new JScrollPane(listPanel);
    scroll.setBorder(
        BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(0x44, 0x44, 0x44), 1),
            "MIDI CC BINDINGS & LEARN MATRIX",
            0,
            0,
            null,
            Color.WHITE));
    scroll.setBackground(new Color(0x1a, 0x1a, 0x1a));
    scroll.getViewport().setBackground(SwingSynthConfigDialog.BG_CARD);
    add(scroll, BorderLayout.CENTER);

    // Setup active query refresh timer (every 150ms)
    refreshTimer = new Timer(150, e -> refreshMappings());
    refreshTimer.start();
  }

  private MidiInputRouter getRouter() {
    if (SwingDelugeApp.mainInstance != null) {
      MidiService service = SwingDelugeApp.mainInstance.getMidiService();
      if (service != null) {
        return service.getRouter();
      }
    }
    return null;
  }

  private void refreshMappings() {
    MidiInputRouter router = getRouter();
    if (router == null) return;

    for (RowPanel rp : rowPanels) {
      int cc = router.getCcForParam(rp.target.globalName);
      if (cc != -1) {
        rp.valueLabel.setText("CC " + cc);
        rp.valueLabel.setForeground(new Color(0x00, 0xff, 0xcc)); // Green/teal active

        // If this parameter was actively learning, stop learning now!
        if (rp.target.globalName.equals(activeLearningParam)) {
          stopLearning(rp.learnBtn, "LEARNED (CC " + cc + ")");
        }
      } else {
        if (!rp.target.globalName.equals(activeLearningParam)) {
          rp.valueLabel.setText("UNMAPPED");
          rp.valueLabel.setForeground(Color.GRAY);
          rp.learnBtn.setText("LEARN");
          rp.learnBtn.setSelected(false);
        }
      }
    }
  }

  private void startLearning(JToggleButton btn, String globalName) {
    MidiInputRouter router = getRouter();
    if (router == null) return;

    // Reset any currently active learn toggle button
    if (activeLearningButton != null && activeLearningButton != btn) {
      stopLearning(activeLearningButton, "LEARN");
    }

    activeLearningParam = globalName;
    activeLearningButton = btn;

    btn.setText("LEARNING...");
    btn.setForeground(new Color(0xff, 0x99, 0x00)); // Orange warning state
    router.startLearning(globalName);
  }

  private void stopLearning(JToggleButton btn, String text) {
    activeLearningParam = null;
    activeLearningButton = null;
    btn.setText(text);
    btn.setSelected(false);
    btn.setForeground(Color.WHITE);
  }

  private class RowPanel extends JPanel {
    final ParameterTarget target;
    final JLabel nameLabel;
    final JLabel valueLabel;
    final JToggleButton learnBtn;
    final JButton clearBtn;

    RowPanel(ParameterTarget target) {
      this.target = target;
      setLayout(new BorderLayout(10, 0));
      setBackground(new Color(0x2a, 0x2a, 0x2a));
      setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
      setMaximumSize(new Dimension(Integer.MAX_VALUE, 45));

      nameLabel = new JLabel(target.displayName);
      nameLabel.setFont(new Font("SansSerif", Font.BOLD, 14));
      nameLabel.setForeground(Color.WHITE);
      nameLabel.setPreferredSize(new Dimension(200, 25));
      add(nameLabel, BorderLayout.WEST);

      valueLabel = new JLabel("UNMAPPED");
      valueLabel.setFont(new Font("SansSerif", Font.PLAIN, 13));
      valueLabel.setForeground(Color.GRAY);
      valueLabel.setHorizontalAlignment(SwingConstants.CENTER);
      add(valueLabel, BorderLayout.CENTER);

      JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
      actions.setBackground(new Color(0x2a, 0x2a, 0x2a));

      learnBtn = new JToggleButton("LEARN");
      learnBtn.setBackground(new Color(0x3e, 0x3e, 0x3e));
      learnBtn.setForeground(Color.WHITE);
      learnBtn.setFocusable(false);
      learnBtn.addActionListener(
          e -> {
            if (learnBtn.isSelected()) {
              startLearning(learnBtn, target.globalName);
            } else {
              stopLearning(learnBtn, "LEARN");
            }
          });
      actions.add(learnBtn);

      clearBtn = new JButton("CLEAR");
      clearBtn.setBackground(new Color(0x5e, 0x2e, 0x2e));
      clearBtn.setForeground(Color.WHITE);
      clearBtn.setFocusable(false);
      clearBtn.addActionListener(
          e -> {
            MidiInputRouter router = getRouter();
            if (router != null) {
              router.clearMapping(target.globalName);
              refreshMappings();
            }
          });
      actions.add(clearBtn);

      add(actions, BorderLayout.EAST);
    }
  }

  public void stopTimer() {
    refreshTimer.stop();
  }
}
