package org.chuck.deluge.ui;

import java.awt.*;
import java.awt.event.MouseEvent;
import javax.swing.*;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.firmware.hid.FirmwareUI;
import org.chuck.deluge.firmware.hid.MatrixDriver;
import org.chuck.deluge.firmware.hid.PadLEDs;
import org.chuck.deluge.firmware.hid.RGB;

/** Custom painted sequencer step grid panel using pure Java2D. */
public class SwingMatrixPanel extends JPanel {
  private final ChuckVM vm;
  private final BridgeContract bridge;

  public enum GridViewMode {
    CLIP,
    SONG
  }

  private GridViewMode viewMode = GridViewMode.CLIP;
  private int currentStep = -1;
  private final int rows = 8;
  private final int cols = 18;
  private int baseTrack = 0;
  private int stepCount = 16; // steps per page / column count for step area

  public void setViewMode(GridViewMode mode) {
    this.viewMode = mode;
    repaint();
  }

  public SwingMatrixPanel(ChuckVM vm, BridgeContract bridge) {
    this.vm = vm;
    this.bridge = bridge;

    setBackground(new Color(0x20, 0x20, 0x20));
    setPreferredSize(new Dimension(2000, 1200));
    setLayout(null);

    setFocusable(true);
    addKeyListener(
        new java.awt.event.KeyAdapter() {
          @Override
          public void keyPressed(java.awt.event.KeyEvent e) {
            // P key reserved for Euclidean dialog (access via button below)
          }
        });

    // Euclidean rhythm generator button
    JButton euclideanBtn = new JButton("Euclidean");
    euclideanBtn.setBackground(new Color(0x33, 0x44, 0x55));
    euclideanBtn.setForeground(Color.WHITE);
    euclideanBtn.setBounds(10, 10, 120, 30);
    euclideanBtn.setToolTipText("Open Euclidean rhythm generator for the current row");
    euclideanBtn.addActionListener(
        e -> {
          Frame frame = (Frame) javax.swing.SwingUtilities.getWindowAncestor(this);
          String rowName = null;
          if (vm != null) {
            Object obj = vm.getGlobalObject("g_sample_" + baseTrack);
            rowName = (obj instanceof String) ? (String) obj : null;
          }
          if (rowName == null || rowName.isBlank()) rowName = "Row " + (baseTrack + 1);
          EuclideanRhythmDialog dlg =
              new EuclideanRhythmDialog(
                  frame, bridge, baseTrack, stepCount, rowName, SwingMatrixPanel.this::repaint);
          dlg.setVisible(true);
        });
    add(euclideanBtn);

    addMouseListener(
        new java.awt.event.MouseAdapter() {
          @Override
          public void mousePressed(java.awt.event.MouseEvent e) {
            handleMousePress(e);
          }
        });
  }

  public void setBaseTrack(int baseTrack) {
    this.baseTrack = baseTrack;
    repaint();
  }

  public void setCurrentStep(int step) {
    this.currentStep = step;
    repaint();
  }

  private void handleMousePress(MouseEvent e) {
    int cellW = 120;
    int cellH = 120;
    int gridX = 200; // Offset to the right for labels
    int gridY = 20;

    int c = (e.getX() - gridX) / cellW;
    int r = (e.getY() - gridY) / cellH;

    if (vm.getGlobalInt(BridgeContract.G_HI_FI_MODE) != 0) {
      MatrixDriver.get().padAction(c, r, 127);
      repaint();
      return;
    }

    int offset = (currentStep >= 0) ? (currentStep / stepCount) * stepCount : 0;
    if (e.getX() - gridX >= stepCount * cellW + 20) {
      c = (e.getX() - gridX - 20) / cellW;
    }
    r = (e.getY() - gridY) / cellH;

    if (c >= 0 && c < cols && r >= 0 && r < rows) {
      if (bridge != null) {
        boolean active = bridge.getStep(baseTrack + r, offset + c);
        if (javax.swing.SwingUtilities.isRightMouseButton(e)) {
          if (active) {
            JDialog dialog =
                new JDialog(
                    (Frame) javax.swing.SwingUtilities.getWindowAncestor(this),
                    "Step Properties",
                    true);
            dialog.setSize(1600, 450);
            dialog.setLocationRelativeTo(this);
            dialog.setLayout(new GridBagLayout());

            GridBagConstraints gc = new GridBagConstraints();
            gc.fill = GridBagConstraints.HORIZONTAL;
            gc.insets = new Insets(10, 15, 10, 15);
            gc.anchor = GridBagConstraints.WEST;

            Font labelFont = new Font("SansSerif", Font.BOLD, 18);
            Dimension sliderDim = new Dimension(1200, 50);
            Dimension spinDim = new Dimension(80, 40);

            // 1. Velocity
            gc.gridx = 0;
            gc.gridy = 0;
            JLabel l1 = new JLabel("Velocity:");
            l1.setFont(labelFont);
            dialog.add(l1, gc);

            gc.gridx = 1;
            JSlider velSlider = new JSlider(0, 100, 80);
            velSlider.setPreferredSize(sliderDim);
            dialog.add(velSlider, gc);

            gc.gridx = 2;
            JSpinner velSpin = new JSpinner(new SpinnerNumberModel(80, 0, 100, 1));
            velSpin.setPreferredSize(spinDim);
            velSpin.addChangeListener(ev -> velSlider.setValue((int) velSpin.getValue()));
            velSlider.addChangeListener(ev -> velSpin.setValue(velSlider.getValue()));
            dialog.add(velSpin, gc);

            // 2. Probability
            gc.gridx = 0;
            gc.gridy = 1;
            JLabel l2 = new JLabel("Probability:");
            l2.setFont(labelFont);
            dialog.add(l2, gc);

            gc.gridx = 1;
            JSlider probSlider = new JSlider(0, 100, 100);
            probSlider.setPreferredSize(sliderDim);
            dialog.add(probSlider, gc);

            gc.gridx = 2;
            JSpinner probSpin = new JSpinner(new SpinnerNumberModel(100, 0, 100, 1));
            probSpin.setPreferredSize(spinDim);
            probSpin.addChangeListener(ev -> probSlider.setValue((int) probSpin.getValue()));
            probSlider.addChangeListener(ev -> probSpin.setValue(probSlider.getValue()));
            dialog.add(probSpin, gc);

            // 3. Gate Length
            gc.gridx = 0;
            gc.gridy = 2;
            JLabel l3 = new JLabel("Gate Length:");
            l3.setFont(labelFont);
            dialog.add(l3, gc);

            gc.gridx = 1;
            JSlider gateSlider = new JSlider(1, 16, 1);
            gateSlider.setPreferredSize(sliderDim);
            dialog.add(gateSlider, gc);

            gc.gridx = 2;
            JSpinner gateSpin = new JSpinner(new SpinnerNumberModel(1, 1, 16, 1));
            gateSpin.setPreferredSize(spinDim);
            gateSpin.addChangeListener(ev -> gateSlider.setValue((int) gateSpin.getValue()));
            gateSlider.addChangeListener(ev -> gateSpin.setValue(gateSlider.getValue()));
            dialog.add(gateSpin, gc);

            // 4. Pitch Offset
            gc.gridx = 0;
            gc.gridy = 3;
            JLabel l4 = new JLabel("Pitch Offset:");
            l4.setFont(labelFont);
            dialog.add(l4, gc);

            gc.gridx = 1;
            JSlider pitchSlider = new JSlider(-24, 24, 0);
            pitchSlider.setPreferredSize(sliderDim);
            dialog.add(pitchSlider, gc);

            gc.gridx = 2;
            JSpinner pitchSpin = new JSpinner(new SpinnerNumberModel(0, -24, 24, 1));
            pitchSpin.setPreferredSize(spinDim);
            pitchSpin.addChangeListener(ev -> pitchSlider.setValue((int) pitchSpin.getValue()));
            pitchSlider.addChangeListener(ev -> pitchSpin.setValue(pitchSlider.getValue()));
            dialog.add(pitchSpin, gc);

            dialog.setVisible(true);
          }
          return;
        }
        if (c == 16) {
          bridge.setMute(baseTrack + r, !bridge.getMute(baseTrack + r));
          repaint();
          return;
        } else if (c == 17) {
          // Mock Solo action
          repaint();
          return;
        }
        bridge.setStep(baseTrack + r, offset + c, !active);
        repaint();
      }

    } else if (e.getY() >= (gridY + rows * cellH + 10)
        && e.getY() <= (gridY + rows * cellH + 130)) {
      // Piano key click
      int keyX = e.getX() - gridX;
      int whiteKeyIndex = keyX / 68;
      if (whiteKeyIndex >= 0 && whiteKeyIndex < 28) {

        System.out.println("Piano note clicked: " + whiteKeyIndex);
        // Trigger note through bridge or vm
      }
    }
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    Graphics2D g2 = (Graphics2D) g;
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

    boolean hiFi = vm.getGlobalInt(BridgeContract.G_HI_FI_MODE) != 0;
    if (hiFi) {
      FirmwareUI currentUI = MatrixDriver.get().getCurrentUI();
      if (currentUI != null) {
        currentUI.setLedStates();
      }
    }

    int cellW = 120;
    int cellH = 120;
    int gridX = 200;
    int gridY = 20;

    for (int r = 0; r < rows; r++) {
      // Draw label
      g2.setColor(Color.LIGHT_GRAY);
      String labelStr = (vm != null) ? (String) vm.getGlobalObject("g_sample_" + r) : "";
      if (labelStr == null || labelStr.isEmpty()) {
        labelStr = "PAD " + (r + 1);
      } else {
        // Shorten path to filename
        int lastSlash = labelStr.lastIndexOf('/');
        if (lastSlash != -1) {
          labelStr = labelStr.substring(lastSlash + 1);
        }
      }
      int offset = (currentStep >= 0) ? (currentStep / stepCount) * stepCount : 0;

      // ...
      g2.drawString(labelStr, 20, gridY + r * cellH + cellH / 2);

      for (int c = 0; c < cols; c++) {
        boolean active = false;
        if (c < stepCount) {
          active = bridge != null && bridge.getStep(baseTrack + r, offset + c);
        } else if (c == 16) {
          active = bridge != null && bridge.getMute(baseTrack + r);
        } else if (c == 17) {
          active = false; // Mocking Solo state
        }

        int padX = gridX + c * cellW + 4;
        if (c >= 16) {
          padX += 20; // Visual space margin separator
        }
        int padY = gridY + r * cellH + 4;
        int padW = cellW - 8;
        int padH = cellH - 8;

        // Draw separator line before column 16
        if (c == 16 && r == 0) {
          g2.setColor(Color.DARK_GRAY);
          g2.setStroke(new BasicStroke(2));
          g2.drawLine(padX - 10, gridY, padX - 10, gridY + rows * cellH);
        }

        if (hiFi && c < 18) {
          RGB led = PadLEDs.image[r][c];
          boolean ledActive = (led.r > 0 || led.g > 0 || led.b > 0);
          if (ledActive) {
            g2.setColor(new Color(led.r, led.g, led.b));
            g2.fillRoundRect(padX, padY, padW, padH, 10, 10);
            g2.setColor(Color.WHITE);
            g2.setStroke(new BasicStroke(2));
            g2.drawRoundRect(padX + 2, padY + 2, padW - 4, padH - 4, 8, 8);
            continue;
          }
        }

        if (active) {
          if (c == 16) {
            g2.setColor(new Color(0xff, 0x33, 0x33, 0xee)); // Mute red
          } else if (c == 17) {
            g2.setColor(new Color(0x33, 0xaa, 0x33, 0xee)); // Solo green
          } else {
            g2.setColor(new Color(0x00, 0xff, 0xcc, 0xee));
          }
          g2.fillRoundRect(padX, padY, padW, padH, 10, 10);
          g2.setColor(Color.WHITE);
          g2.setStroke(new BasicStroke(2));
          g2.drawRoundRect(padX + 2, padY + 2, padW - 4, padH - 4, 8, 8);

          if (c < stepCount) {
            if (viewMode == GridViewMode.CLIP) {
              g2.setColor(Color.BLACK);
              g2.setFont(new Font("Monospaced", Font.BOLD, 16));
              g2.drawString("Pi: " + (baseTrack + r), padX + 10, padY + 30);
              g2.drawString("Ve: 0.8", padX + 10, padY + 52);
              g2.drawString("Pr: 1.0", padX + 10, padY + 74);
              g2.drawString("Ga: 1", padX + 10, padY + 96);
            } else {
              g2.setColor(Color.BLACK);
              g2.setFont(new Font("SansSerif", Font.BOLD, 18));
              g2.drawString("PAD " + (c + 1), padX + 25, padY + 60);
            }
          } else if (c == 16) {

            g2.setColor(Color.WHITE);
            g2.setFont(new Font("SansSerif", Font.BOLD, 24));
            g2.drawString("MUTE", padX + 20, padY + cellH / 2 + 8);
          } else if (c == 17) {
            g2.setColor(Color.WHITE);
            g2.setFont(new Font("SansSerif", Font.BOLD, 24));
            g2.drawString("SOLO", padX + 20, padY + cellH / 2 + 8);
          }
        } else {
          g2.setColor(SwingSynthConfigDialog.BG_CONTROL);
          g2.fillRoundRect(padX, padY, padW, padH, 10, 10);
          if (c == 16) {
            g2.setColor(Color.GRAY);
            g2.setFont(new Font("SansSerif", Font.BOLD, 20));
            g2.drawString("Mute", padX + 25, padY + cellH / 2 + 8);
          } else if (c == 17) {
            g2.setColor(Color.GRAY);
            g2.setFont(new Font("SansSerif", Font.BOLD, 20));
            g2.drawString("Solo", padX + 25, padY + cellH / 2 + 8);
          }
        }

        // Playhead highlight
        if (c == (currentStep % stepCount)) {
          g2.setColor(Color.YELLOW);
          g2.setStroke(new BasicStroke(3));

          g2.drawRoundRect(padX - 1, padY - 1, padW + 2, padH + 2, 12, 12);
        }
      }
    }

    // Draw Piano Roll at the bottom
    if (viewMode == GridViewMode.CLIP) {
      int keyboardY = gridY + rows * cellH + 10;
      int keyH = 120;

      // 28 White keys (4 octaves) aligned with grid columns
      int keyW = 68;
      for (int i = 0; i < 28; i++) {
        boolean activeKey =
            (bridge != null)
                && (currentStep >= 0)
                && bridge.getStep(baseTrack + (i % 8), currentStep % stepCount);

        g2.setColor(activeKey ? new Color(0x00, 0xff, 0xcc) : Color.WHITE);
        g2.fillRect(gridX + i * keyW, keyboardY, keyW - 2, keyH);
        g2.setColor(Color.BLACK);
        g2.drawRect(gridX + i * keyW, keyboardY, keyW - 2, keyH);
      }

      // Black keys
      int[] blackKeyOffsets = {
        0, 1, 3, 4, 5,
        7, 8, 10, 11, 12,
        14, 15, 17, 18, 19,
        21, 22, 24, 25, 26
      };
      for (int offsetKey : blackKeyOffsets) {
        int bx = gridX + offsetKey * keyW + keyW - keyW / 3;
        g2.setColor(new Color(0x1a, 0x1a, 0x1a));
      }
    }
  }
}
