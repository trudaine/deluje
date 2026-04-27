package org.chuck.deluge.ui.netbeans;

import java.awt.*;
import javax.swing.*;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.TrackModel;
import org.chuck.deluge.model.KitTrackModel;
import org.chuck.deluge.model.SynthTrackModel;

/** 
 * NetBeans-compatible Sequencer Grid Panel.
 * Maps UI rows to the dynamic voice architecture of DelugeEngineDSL.
 */
public class NetBeansGridPanel extends javax.swing.JPanel {
    private MainViewModel viewModel;
    private final int ROWS = 10;

    public NetBeansGridPanel() {
        initComponents();
        Dimension fixedSize = new Dimension(900, 500);
        setPreferredSize(fixedSize);
        setMinimumSize(fixedSize);
        setMaximumSize(fixedSize);
    }

    public void setViewModel(MainViewModel viewModel) {
        this.viewModel = viewModel;
        if (viewModel != null) {
            viewModel.addPropertyChangeListener(evt -> {
                String prop = evt.getPropertyName();
                if ("currentStep".equals(prop) || 
                    "projectModel".equals(prop) || 
                    "focusedTrack".equals(prop) ||
                    "shiftDown".equals(prop)) {
                    repaint();
                }
            });
            
            this.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mousePressed(java.awt.event.MouseEvent e) {
                    handleMousePress(e);
                }
            });
        }
    }

    private int getAbsoluteEngineSlot(int row) {
        if (viewModel == null || viewModel.getProjectModel() == null) return -1;
        
        int focusedIdx = viewModel.getFocusedTrack();
        java.util.List<TrackModel> tracks = viewModel.getProjectModel().getTracks();
        
        int engineSlotIdx = 0;
        for (int i = 0; i < Math.min(focusedIdx, tracks.size()); i++) {
            TrackModel t = tracks.get(i);
            if (t instanceof KitTrackModel kit) {
                engineSlotIdx += kit.getSounds().size();
            } else {
                engineSlotIdx++;
            }
        }
        return engineSlotIdx + row;
    }

    private void handleMousePress(java.awt.event.MouseEvent e) {
        if (viewModel == null || viewModel.getBridge() == null) return;
        
        int startX = 140; 
        int padW = (getWidth() - startX) / 16;
        int padH = getHeight() / ROWS;
        
        int r = e.getY() / padH;
        int c = (e.getX() - startX) / padW;

        if (e.getX() < startX) {
            viewModel.previewRow(r);
            return;
        }

        if (c >= 0 && c < 16 && r >= 0 && r < ROWS) {
            int engineRow = getAbsoluteEngineSlot(r);
            if (engineRow < 0 || engineRow >= 64) return;

            boolean current = viewModel.getBridge().getStep(engineRow, c);
            viewModel.getBridge().setStep(engineRow, c, !current);
            
            if (!current) {
                viewModel.previewRow(r);
            }
            repaint();
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (viewModel == null) return;

        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int startX = 140;
        int padW = (getWidth() - startX) / 16;
        int padH = getHeight() / ROWS;

        int currentStep = viewModel.getCurrentStep();
        ProjectModel project = viewModel.getProjectModel();
        int focusedIdx = viewModel.getFocusedTrack();
        
        TrackModel focusedTrack = null;
        if (project != null && focusedIdx >= 0 && focusedIdx < project.getTracks().size()) {
            focusedTrack = project.getTracks().get(focusedIdx);
        }

        for (int r = 0; r < ROWS; r++) {
            // 1. Label Area
            g2.setColor(new Color(45, 45, 45));
            g2.fillRect(5, r * padH + 2, 130, padH - 4);
            g2.setColor(Color.WHITE);
            g2.setFont(new Font("SansSerif", Font.BOLD, 10));
            
            String label = "";
            if (focusedTrack instanceof KitTrackModel kit) {
                if (r < kit.getSounds().size()) {
                    label = kit.getSounds().get(r).getName();
                } else {
                    label = "UNUSED";
                }
            } else if (focusedTrack instanceof SynthTrackModel synth) {
                label = "PITCH " + (60 + r); 
            }
            
            if (label.length() > 15) label = label.substring(0, 12) + "...";
            g2.drawString(label.toUpperCase(), 15, r * padH + padH / 2 + 5);

            // 2. Pads
            for (int c = 0; c < 16; c++) {
                int x = startX + c * padW + 2;
                int y = r * padH + 2;
                int pw = padW - 4;
                int ph = padH - 4;

                int engineRow = getAbsoluteEngineSlot(r);
                boolean active = (engineRow >= 0 && engineRow < 64) && 
                                 viewModel.getBridge() != null && 
                                 viewModel.getBridge().getStep(engineRow, c);
                
                if (active) g2.setColor(new Color(0x00, 0xff, 0xcc));
                else g2.setColor(new Color(33, 33, 33));

                if (c == (currentStep % 16)) {
                    if (active) g2.setColor(Color.WHITE);
                    else g2.setColor(new Color(80, 80, 40));
                }

                g2.fillRoundRect(x, y, pw, ph, 8, 8);
                g2.setColor(new Color(60, 60, 60));
                g2.drawRoundRect(x, y, pw, ph, 8, 8);
            }
        }
    }

    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        setBackground(new java.awt.Color(20, 20, 20));

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(this);
        this.setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 900, Short.MAX_VALUE)
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 500, Short.MAX_VALUE)
        );
    }// </editor-fold>//GEN-END:initComponents

    // Variables declaration - do not modify
    // End of variables declaration
}
