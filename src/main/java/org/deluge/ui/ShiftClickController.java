package org.deluge.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Point;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSlider;
import org.deluge.model.EnvelopeModel;
import org.deluge.model.SynthTrackModel;
import org.deluge.model.TrackModel;
import org.deluge.project.PreferencesManager;

/**
 * Controller extracting the shift-click synthesizer parameter overlay and basic popup sliders
 * from ClipEditorController to keep it clean and focused.
 */
class ShiftClickController {

  static void handleShiftClick(
      ClipEditorController controller, int row, int col, Point localPos, Component comp) {
    if (row < 0 || row >= 8 || col < 0 || col >= 16) return;
    String param = SwingGridPanel.SHIFT_LABELS[row][col];
    if (param == null || param.isEmpty()) return;

    int editedModelTrack = controller.getEditedModelTrack();
    if (controller.getProjectModel() == null
        || editedModelTrack >= controller.getProjectModel().getTracks().size()) return;
    TrackModel genericTrack = controller.getProjectModel().getTracks().get(editedModelTrack);

    // Check parameter applicability first
    boolean applicable = controller.isParamApplicable(param, row, col, genericTrack);
    if (!applicable) {
      JPopupMenu tooltip = new JPopupMenu();
      tooltip.setBackground(new Color(0x2a, 0x15, 0x15));
      tooltip.setBorder(BorderFactory.createLineBorder(new Color(0xaa, 0x33, 0x33)));
      JLabel alert = new JLabel("  PARAMETER NOT APPLICABLE  ");
      alert.setForeground(new Color(0xff, 0x55, 0x55));
      alert.setFont(new Font("SansSerif", Font.BOLD, 10));
      tooltip.add(alert);
      tooltip.show(comp, localPos.x, localPos.y);
      javax.swing.Timer dismiss = new javax.swing.Timer(1500, ev -> tooltip.setVisible(false));
      dismiss.setRepeats(false);
      dismiss.start();
      return;
    }

    // Extract track references
    SynthTrackModel track =
        (genericTrack instanceof SynthTrackModel) ? (SynthTrackModel) genericTrack : null;

    boolean isRotary =
        PreferencesManager.getShiftInteractionMode()
            == PreferencesManager.ShiftInteractionMode.ROTARY_ENCODER;
    if (isRotary) {
      controller.setActiveShiftParam(param, row, col);
      String code = controller.getParamShortCode(param);
      String valStr = controller.getParamFormattedValue(param, row, col);
      if (SwingDelugeApp.mainInstance != null) {
        SwingDelugeApp.mainInstance.updateHardwareLedDisplay(code, valStr);
      }
      controller.refreshCallback.run();
      return;
    }

    JPopupMenu popup = new JPopupMenu();
    popup.setBackground(new Color(0x18, 0x18, 0x1c));
    popup.setBorder(BorderFactory.createLineBorder(new Color(0x2d, 0x2d, 0x32)));

    JPanel wrapper = new JPanel(new BorderLayout(5, 5));
    wrapper.setBackground(new Color(0x18, 0x18, 0x1c));
    wrapper.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

    JLabel title = new JLabel(param.toUpperCase());
    title.setForeground(new Color(0x00, 0xff, 0xcc));
    title.setFont(new Font("SansSerif", Font.BOLD, 10));
    wrapper.add(title, BorderLayout.NORTH);

    int initVal = 50;
    String initialLabel = "";
    final int envIdx = (col == 9) ? 1 : 0;

    switch (param) {
      case "CUTOFF":
        if (row == 0 && track != null) {
          float freq = (col == 8) ? track.getLpfFreq() : track.getHpfFreq();
          initVal = (int) (100.0 * Math.log(freq / 20.0) / Math.log(1000.0));
          initialLabel = String.format("%.0f Hz", freq);
        } else if (row == 1 && track != null) {
          float res = (col == 8) ? track.getLpfRes() : track.getHpfRes();
          initVal = (int) (res * 100.0f);
          initialLabel = String.format("%d%%", initVal);
        }
        break;
      case "RESONANCE":
        if (track != null) {
          float resVal = (col == 8) ? track.getLpfRes() : track.getHpfRes();
          initVal = (int) (resVal * 100.0f);
          initialLabel = String.format("%d%%", initVal);
        }
        break;
      case "ATTACK":
        if (track != null) {
          float att = track.getEnv(envIdx).attack();
          initVal = (int) (att * 10.0f);
          initialLabel = String.format("%.2f s", att);
        }
        break;
      case "DECAY":
        if (track != null) {
          float dec = track.getEnv(envIdx).decay();
          initVal = (int) (dec * 10.0f);
          initialLabel = String.format("%.2f s", dec);
        }
        break;
      case "SUSTAIN":
        if (track != null) {
          float sus = track.getEnv(envIdx).sustain();
          initVal = (int) (sus * 100.0f);
          initialLabel = String.format("%d%%", initVal);
        }
        break;
      case "RELEASE":
        if (track != null) {
          float rel = track.getEnv(envIdx).release();
          initVal = (int) (rel * 10.0f);
          initialLabel = String.format("%.2f s", rel);
        }
        break;
      case "PAN":
        if (row == 4 && col == 6) {
          initVal = (int) (genericTrack.getPan() * 100.0f);
          initialLabel = String.format("%d%%", initVal);
        }
        break;
      case "LEVEL":
        if (row == 7 && col == 6) {
          initVal = (int) (genericTrack.getVolume() * 100.0f);
          initialLabel = String.format("%d%%", initVal);
        } else if (row == 7 && (col == 2 || col == 3) && track != null) {
          initVal = (int) (track.getOscMix() * 100.0f);
          initialLabel = String.format("%d%%", initVal);
        }
        break;
      case "GLIDE":
        initVal = (int) (track.getPortamento() * 50.0f);
        initialLabel = String.format("%.2f s", track.getPortamento());
        break;
    }

    JSlider slider = new JSlider(0, 100, Math.max(0, Math.min(100, initVal)));
    slider.setBackground(new Color(0x12, 0x12, 0x14));
    slider.setForeground(new Color(0x00, 0xff, 0xcc));
    slider.setPreferredSize(new Dimension(150, 18));
    slider.setOpaque(false);
    slider.setFocusable(false);
    slider.setUI(
        new javax.swing.plaf.basic.BasicSliderUI(slider) {
          @Override
          public void paintTrack(java.awt.Graphics g) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(
                java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            int cy = trackRect.y + (trackRect.height / 2) - 2;
            g2.setColor(new Color(0x66, 0x66, 0x6e));
            g2.fillRoundRect(trackRect.x, cy, trackRect.width, 4, 2, 2);
            int thumbPos = thumbRect.x + (thumbRect.width / 2);
            g2.setColor(new Color(0x00, 0xff, 0xcc));
            g2.fillRoundRect(trackRect.x, cy, Math.max(0, thumbPos - trackRect.x), 4, 2, 2);
            g2.dispose();
          }

          @Override
          public void paintThumb(java.awt.Graphics g) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(
                java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Color.WHITE);
            g2.fillOval(
                thumbRect.x + 2, thumbRect.y + 2, thumbRect.width - 4, thumbRect.height - 4);
            g2.setColor(new Color(0x00, 0xff, 0xcc));
            g2.drawOval(
                thumbRect.x + 2, thumbRect.y + 2, thumbRect.width - 4, thumbRect.height - 4);
            g2.dispose();
          }
        });
    wrapper.add(slider, BorderLayout.CENTER);

    JLabel valueLabel = new JLabel(initialLabel);
    valueLabel.setForeground(Color.LIGHT_GRAY);
    valueLabel.setFont(new Font("Monospaced", Font.PLAIN, 10));
    wrapper.add(valueLabel, BorderLayout.SOUTH);

    slider.addChangeListener(
        e -> {
          int val = slider.getValue();
          switch (param) {
            case "CUTOFF":
              if (row == 0) {
                float freq = (float) (20.0 * Math.pow(1000.0, val / 100.0));
                if (col == 8) track.setLpfFreq(freq);
                else track.setHpfFreq(freq);
                valueLabel.setText(String.format("%.0f Hz", freq));
              } else if (row == 1) {
                float res = val / 100.0f;
                if (col == 8) track.setLpfRes(res);
                else track.setHpfRes(res);
                valueLabel.setText(String.format("%d%%", val));
              }
              break;
            case "RESONANCE":
              float res = val / 100.0f;
              if (col == 8) track.setLpfRes(res);
              else track.setHpfRes(res);
              valueLabel.setText(String.format("%d%%", val));
              break;
            case "ATTACK":
              float aTime = val / 10.0f;
              var attEnv = track.getEnv(envIdx);
              track.setEnv(
                  envIdx,
                  new EnvelopeModel(
                      aTime,
                      attEnv.decay(),
                      attEnv.sustain(),
                      attEnv.release(),
                      attEnv.target(),
                      attEnv.amount()));
              valueLabel.setText(String.format("%.2f s", aTime));
              break;
            case "DECAY":
              float dTime = val / 10.0f;
              var decEnv = track.getEnv(envIdx);
              track.setEnv(
                  envIdx,
                  new EnvelopeModel(
                      decEnv.attack(),
                      dTime,
                      decEnv.sustain(),
                      decEnv.release(),
                      decEnv.target(),
                      decEnv.amount()));
              valueLabel.setText(String.format("%.2f s", dTime));
              break;
            case "SUSTAIN":
              float sLevel = val / 100.0f;
              var susEnv = track.getEnv(envIdx);
              track.setEnv(
                  envIdx,
                  new EnvelopeModel(
                      susEnv.attack(),
                      susEnv.decay(),
                      sLevel,
                      susEnv.release(),
                      susEnv.target(),
                      susEnv.amount()));
              valueLabel.setText(String.format("%d%%", val));
              break;
            case "RELEASE":
              float rTime = val / 10.0f;
              var relEnv = track.getEnv(envIdx);
              track.setEnv(
                  envIdx,
                  new EnvelopeModel(
                      relEnv.attack(),
                      relEnv.decay(),
                      relEnv.sustain(),
                      rTime,
                      relEnv.target(),
                      relEnv.amount()));
              valueLabel.setText(String.format("%.2f s", rTime));
              break;
            case "PAN":
              if (row == 4 && col == 6) {
                float p = val / 100.0f;
                genericTrack.setPan(p);
                valueLabel.setText(String.format("%d%%", val));
              }
              break;
            case "LEVEL":
              if (row == 7 && col == 6) {
                float vol = val / 100.0f;
                genericTrack.setVolume(vol);
                valueLabel.setText(String.format("%d%%", val));
              } else if (row == 7 && (col == 2 || col == 3) && track != null) {
                float mixVal = val / 100.0f;
                track.setOscMix(mixVal);
                valueLabel.setText(String.format("%d%%", val));
              }
              break;
            case "GLIDE":
              float port = val / 50.0f;
              track.setPortamento(port);
              valueLabel.setText(String.format("%.2f s", port));
              break;
          }
          controller.projectChangedCallback.run();
        });

    popup.add(wrapper);
    popup.show(comp, localPos.x, localPos.y);
  }
}
