package org.chuck.deluge.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;

/** Bottom status bar containing the OLED display emulation. */
public class StatusRibbonPanel extends HBox {
  private final ChuckVM vm;
  private final BridgeContract bridge;

  private final Label oledLabel;
  private final Label shredLabel;

  public StatusRibbonPanel(ChuckVM vm, BridgeContract bridge) {
    this.vm = vm;
    this.bridge = bridge;

    setAlignment(Pos.CENTER);
    setSpacing(20);
    setPadding(new Insets(10));
    setStyle("-fx-background-color: #1a1a1a;");

    // "OLED" Display emulation
    HBox oledBox = new HBox();
    oledBox.setAlignment(Pos.CENTER);
    oledBox.setPrefWidth(200);
    oledBox.setPrefHeight(40);
    oledBox.setStyle(
        "-fx-background-color: #000000; -fx-border-color: #555; -fx-border-width: 2; -fx-border-radius: 5; -fx-background-radius: 5;");

    oledLabel = new Label("DELUGE");
    oledLabel.setTextFill(Color.web("#FF3333")); // Classic red LED color
    oledLabel.setFont(Font.font("Monospaced", FontWeight.BOLD, 20));

    oledBox.getChildren().add(oledLabel);

    shredLabel = new Label("SHREDS: 0");
    shredLabel.setTextFill(Color.web("#00ffcc"));
    shredLabel.setFont(Font.font("Monospaced", FontWeight.BOLD, 12));

    getChildren().addAll(oledBox, shredLabel);
  }

  public void update(int currentStep) {
    // Only update if playing
    if (vm.getGlobalInt(BridgeContract.G_PLAY) == 1L) {
      // 1-based display
      int bar = (currentStep / 16) + 1;
      int beat = ((currentStep % 16) / 4) + 1;
      int sixteenth = (currentStep % 4) + 1;
      oledLabel.setText(String.format("%d.%d.%d", bar, beat, sixteenth));
    } else {
      oledLabel.setText("STOP");
    }

    shredLabel.setText("SHREDS: " + vm.getActiveShredCount());
  }

  public void updateStatus(String msg) {
    oledLabel.setText(msg.toUpperCase());
  }
}
