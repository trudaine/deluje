package org.chuck.deluge.ui;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import org.chuck.core.ChuckArray;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;

/**
 * Persistent Velocity Lane. Shows a bar chart of velocities for the 16 steps
 * of the currently selected track.
 */
public class VelocityLanePanel extends Pane {
    private final ChuckVM vm;
    private final BridgeContract bridge;
    private final Canvas canvas;
    private int selectedTrack = 0;

    public VelocityLanePanel(ChuckVM vm, BridgeContract bridge) {
        this.vm = vm;
        this.bridge = bridge;
        this.canvas = new Canvas(800, 60);

        getChildren().add(canvas);
        setPrefHeight(60);
        setStyle("-fx-background-color: #222; -fx-border-color: #444; -fx-border-width: 1 0 0 0;");

        widthProperty().addListener((obs, oldVal, newVal) -> {
            canvas.setWidth(newVal.doubleValue());
            draw();
        });
    }

    public void setSelectedTrack(int trackIndex) {
        this.selectedTrack = trackIndex;
        draw();
    }

    public void draw() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();

        gc.clearRect(0, 0, w, h);

        ChuckArray velocities = (ChuckArray) vm.getGlobalObject(BridgeContract.G_VELOCITY);
        if (velocities == null) return;

        double barWidth = w / 16.0;
        gc.setFill(Color.web("#00ffcc", 0.6));

        for (int i = 0; i < 16; i++) {
            double vel = velocities.getFloat(selectedTrack * 16 + i);
            double barHeight = vel * (h - 10);
            gc.fillRect(i * barWidth + 2, h - barHeight - 5, barWidth - 4, barHeight);
        }

        // Draw grid lines
        gc.setStroke(Color.web("#333"));
        gc.setLineWidth(1);
        for (int i = 1; i < 16; i++) {
            gc.strokeLine(i * barWidth, 0, i * barWidth, h);
        }
    }
}
