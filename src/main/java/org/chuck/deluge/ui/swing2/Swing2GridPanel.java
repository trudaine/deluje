package org.chuck.deluge.ui.swing2;

import java.awt.*;
import javax.swing.*;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.ClipModel;
import org.chuck.deluge.model.StepData;

public class Swing2GridPanel extends JPanel implements ClipModel.ClipListener {
  private final ChuckVM vm;
  private final BridgeContract bridge;
  private ClipModel activeClip;
  private JButton[][] pads = new JButton[11][18];
  private Color[] trackColors = {
    Color.CYAN,
    Color.MAGENTA,
    Color.YELLOW,
    Color.GREEN,
    Color.ORANGE,
    Color.PINK,
    Color.BLUE,
    Color.LIGHT_GRAY,
    Color.DARK_GRAY,
    Color.RED,
    Color.WHITE
  };
  private double[] vuLevels = new double[11];
  private int activeClipId = 0;
  private int baseTrackId = 0;

  public Swing2GridPanel(ChuckVM vm, BridgeContract bridge) {
    this.vm = vm;
    this.bridge = bridge;
    setLayout(new javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS));

    setBackground(new Color(0x1a, 0x1a, 0x1a));
  }

  public void setClipModel(ClipModel clip) {
    this.activeClip = clip;
    if (this.activeClip != null) {
      this.activeClip.addClipListener(this);
      refresh();
    }
  }

  public void setBaseTrackId(int id) {
    this.baseTrackId = id;
    refresh();
  }

  public void setActiveClipId(int id) {
    this.activeClipId = id;
  }

  public void refresh() {
    removeAll();
    java.util.List<org.chuck.deluge.model.TrackModel> tracks = new java.util.ArrayList<>();

    String res = org.chuck.deluge.project.PreferencesManager.get("screen.resolution", "FHD");

    final int padSz = "FHD".equals(res) ? 90 : ("4K".equals(res) ? 180 : 120);

    for (int t = 0; t < 11; t++) {
      JPanel rowPanel = new JPanel();
      rowPanel.setLayout(new BoxLayout(rowPanel, BoxLayout.X_AXIS));
      rowPanel.setBackground(new Color(0x22, 0x22, 0x22));
      rowPanel.setPreferredSize(new Dimension(3000, padSz));
      rowPanel.setMinimumSize(new Dimension(3000, padSz));
      rowPanel.setMaximumSize(new Dimension(3000, padSz));

      final int trk = t;
      JLabel label = new JLabel("TRACK " + t);
      label.setPreferredSize(new Dimension(150, padSz));
      label.setForeground(Color.WHITE);
      rowPanel.add(label);

      for (int c = 0; c < 18; c++) {
        JButton clipBtn;
        if (t == 9 && c < 16) {
          final int col = c;
          clipBtn =
              new JButton() {
                @Override
                protected void paintComponent(Graphics g) {
                  super.paintComponent(g);
                  g.setColor(Color.ORANGE);
                  int h = getHeight();
                  double val = (bridge != null) ? bridge.getVelocity(baseTrackId, col) : 0.5;
                  int barH = (int) (val * h);
                  g.fillRect(0, h - barH, getWidth(), barH);
                }
              };
          clipBtn.addMouseMotionListener(
              new java.awt.event.MouseAdapter() {
                @Override
                public void mouseDragged(java.awt.event.MouseEvent e) {
                  double v = 1.0 - (double) e.getY() / getHeight();
                  v = Math.max(0.0, Math.min(1.0, v));
                  if (bridge != null) bridge.setVelocity(baseTrackId, col, v);
                  repaint();
                }
              });
        } else {
          clipBtn = new JButton();
        }
        clipBtn.setPreferredSize(new Dimension(padSz, padSz));
        clipBtn.setMinimumSize(new Dimension(padSz, padSz));
        clipBtn.setMaximumSize(new Dimension(padSz, padSz));

        clipBtn.setBackground(new Color(0x33, 0x33, 0x33));

        if (t < 8 && c < 16) {
          final int col = c;
          boolean active = (activeClip != null) && activeClip.getStep(trk, col).active();
          clipBtn.setBackground(active ? trackColors[trk] : new Color(0x33, 0x33, 0x33));

          clipBtn.addActionListener(
              e -> {
                if (activeClip != null) {
                  StepData current = activeClip.getStep(trk, col);
                  StepData mutated =
                      new StepData(
                          !current.active(),
                          current.velocity(),
                          current.gate(),
                          current.probability(),
                          current.pitch());
                  activeClip.setStep(trk, col, mutated);
                }
              });
        } else {
          clipBtn.setBackground(new Color(0x33, 0x33, 0x33));
        }

        rowPanel.add(clipBtn);
        pads[t][c] = clipBtn;
      }
      add(rowPanel);
    }
    setPreferredSize(new Dimension(2270, 11 * padSz));
    revalidate();

    repaint();
  }

  @Override
  public void onStepChanged(int row, int step, StepData data) {
    if (row >= 0 && row < 8 && step >= 0 && step < 16 && pads[row][step] != null) {
      pads[row][step].setBackground(data.active() ? trackColors[row] : new Color(0x33, 0x33, 0x33));
    }
  }

  public void updatePlayhead(int step) {
    for (int r = 0; r < 8; r++) {
      for (int c = 0; c < 16; c++) {
        if (pads[r][c] != null) {
          boolean active = (activeClip != null) && activeClip.getStep(r, c).active();
          if (c == (step % 16)) {
            pads[r][c].setBackground(Color.WHITE);
          } else {
            pads[r][c].setBackground(active ? trackColors[r] : new Color(0x33, 0x33, 0x33));
          }
        }
      }
    }
  }
}
