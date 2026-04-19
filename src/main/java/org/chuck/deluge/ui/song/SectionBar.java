package org.chuck.deluge.ui.song;

import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;

/**
 * A horizontal bar of A-Z buttons for launching whole columns (Sections) in Song Mode
 * simultaneously.
 */
public class SectionBar extends HBox {
  private Consumer<Integer> sectionLaunchHandler;

  public SectionBar(int columns) {
    setAlignment(Pos.CENTER_LEFT);
    setSpacing(5);
    setPadding(new Insets(5, 5, 5, 85)); // Offset to align with the track headers
    setStyle("-fx-background-color: #222222;");

    for (int i = 0; i < columns; i++) {
      int colIndex = i;
      Button btn = new Button(String.valueOf((char) ('A' + i)));
      btn.setPrefSize(80, 30);
      btn.setStyle(
          "-fx-background-color: #555555; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 5;");

      btn.setOnAction(
          e -> {
            if (sectionLaunchHandler != null) {
              sectionLaunchHandler.accept(colIndex);
            }
          });

      getChildren().add(btn);
    }
  }

  public void setOnSectionLaunched(Consumer<Integer> handler) {
    this.sectionLaunchHandler = handler;
  }
}
