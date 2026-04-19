package org.chuck.deluge.ui;

import javafx.geometry.Insets;
import javafx.scene.layout.BorderPane;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;

/**
 * The root container for the Deluge UI. Composes the Parameter Ribbon, Transport, Matrix, and OLED
 * panels.
 */
public class DelugeMainPanel extends BorderPane {
  private final ChuckVM vm;
  private final BridgeContract bridge;

  private TransportPanel transportPanel;
  private MatrixPanel matrixPanel;
  private ParameterRibbonPanel ribbonPanel;
  private StatusRibbonPanel statusPanel;

  public DelugeMainPanel(ChuckVM vm, BridgeContract bridge) {
    this.vm = vm;
    this.bridge = bridge;

    // Dark grey background
    setStyle("-fx-background-color: #1a1a1a;");
    setPadding(new Insets(10));

    // Initialize sub-panels
    transportPanel = new TransportPanel(vm, bridge);
    matrixPanel = new MatrixPanel(vm, bridge);
    ribbonPanel = new ParameterRibbonPanel(vm, bridge);
    statusPanel = new StatusRibbonPanel(vm, bridge);

    // Top: Transport and Ribbon
    javafx.scene.layout.VBox topBox = new javafx.scene.layout.VBox(10);
    topBox.getChildren().addAll(transportPanel, ribbonPanel);
    setTop(topBox);

    // Center: The Grid Matrix
    setCenter(matrixPanel);

    // Bottom: Status Bar
    setBottom(statusPanel);
  }

  /** Called every frame by the AnimationTimer in DelugeApp. */
  public void updateFromVM() {
    int step = (int) vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
    matrixPanel.updateStep(step);
    statusPanel.update(step);
  }
}
