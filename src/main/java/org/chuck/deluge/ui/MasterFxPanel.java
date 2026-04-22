package org.chuck.deluge.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;

/** UI Panel for controlling global effects (Reverb, Delay). */
public class MasterFxPanel extends HBox {
  private final ChuckVM vm;
  private final org.chuck.deluge.midi.MidiService midiService;

  public MasterFxPanel(ChuckVM vm, org.chuck.deluge.midi.MidiService midiService) {
    this.vm = vm;
    this.midiService = midiService;

    setAlignment(Pos.CENTER_LEFT);
    setSpacing(20);
    setPadding(new Insets(10));
    setStyle("-fx-background-color: #222; -fx-border-color: #333; -fx-border-width: 1 0 0 0;");

    Label title = new Label("MASTER FX (Waiting for Engine...)");
    title.setStyle("-fx-text-fill: #aaa; -fx-font-weight: bold;");
    getChildren().add(title);
  }

  private boolean controlsInitialized = false;

  public void updateControls() {
    updateControls(false);
  }

  public void updateControls(boolean force) {
    if (!force && controlsInitialized) return;

    String reverbModel = org.chuck.deluge.project.PreferencesManager.get("reverb.model", "JCRev");
    Class<?> revClass;
    if ("FreeVerb".equals(reverbModel)) {
      revClass = org.chuck.audio.fx.FreeVerb.class;
    } else {
      revClass = org.chuck.audio.fx.JCRev.class;
    }

    javafx.application.Platform.runLater(
        () -> {
          getChildren().clear();

          Label title = new Label("MASTER FX");
          title.setStyle("-fx-text-fill: #aaa; -fx-font-weight: bold;");
          getChildren().add(title);

          // Reverb Group
          VBox revGroup = new VBox(5);
          revGroup.setStyle(
              "-fx-border-color: #444; -fx-border-width: 1; -fx-border-radius: 5; -fx-padding: 5;");
          Label revTitle = new Label(revClass.getSimpleName().toUpperCase());
          revTitle.setStyle("-fx-text-fill: #888; -fx-font-size: 10px; -fx-font-weight: bold;");
          HBox revSliders = new HBox(10);
          revGroup.getChildren().addAll(revTitle, revSliders);

          // Introspect Reverb!
          java.lang.reflect.Method[] methods = revClass.getMethods();
          System.out.println(
              "DEBUG: Introspecting "
                  + revClass.getName()
                  + ", found "
                  + methods.length
                  + " methods.");
          for (java.lang.reflect.Method m : methods) {
            // Look for methods taking a single float!
            if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == float.class) {
              String name = m.getName();
              System.out.println("DEBUG: Found candidate method: " + name);
              if (!name.equals("wait") && !name.equals("equals") && !name.equals("tick")) {
                revSliders.getChildren().add(createDynamicSlider(revClass, m));
              }
            }
          }
          getChildren().add(revGroup);

          // Delay Group
          VBox delayGroup = new VBox(5);
          delayGroup.setStyle(
              "-fx-border-color: #444; -fx-border-width: 1; -fx-border-radius: 5; -fx-padding: 5;");
          Label delayTitle = new Label("DELAY");
          delayTitle.setStyle("-fx-text-fill: #888; -fx-font-size: 10px; -fx-font-weight: bold;");
          HBox delaySliders = new HBox(10);
          delayGroup.getChildren().addAll(delayTitle, delaySliders);

          delaySliders
              .getChildren()
              .add(createSlider("Delay Time", BridgeContract.G_DELAY_TIME, 0.1, 2.0, 0.5));
          delaySliders
              .getChildren()
              .add(createSlider("Delay FB", BridgeContract.G_DELAY_FB, 0.0, 0.9, 0.3));

          getChildren().add(delayGroup);

          controlsInitialized = true;
        });
  }

  private VBox createDynamicSlider(Class<?> revClass, java.lang.reflect.Method m) {
    VBox box = new VBox(5);
    box.setAlignment(Pos.CENTER);

    Label lbl = new Label(m.getName().toUpperCase());
    lbl.setStyle("-fx-text-fill: #ccc; -fx-font-size: 10px;");

    Slider slider = new Slider(0.0, 1.0, 0.5); // Default 0-1 range
    slider.setPrefWidth(100);
    slider.setShowTickMarks(true);

    javafx.scene.control.ContextMenu contextMenu = new javafx.scene.control.ContextMenu();
    javafx.scene.control.MenuItem learnItem = new javafx.scene.control.MenuItem("MIDI Learn");
    contextMenu.getItems().add(learnItem);
    slider.setContextMenu(contextMenu);

    learnItem.setOnAction(e -> {
        midiService.startLearn("reverb." + m.getName());
    });

    String paramName = "reverb." + m.getName();
    vm.setGlobalFloat(paramName, 0.5f);

    slider
        .valueProperty()
        .addListener(
            (obs, oldVal, newVal) -> {
              vm.setGlobalFloat(paramName, newVal.floatValue());
            });

    box.getChildren().addAll(lbl, slider);
    return box;
  }

  private VBox createSlider(String label, String paramName, double min, double max, double def) {
    VBox box = new VBox(5);
    box.setAlignment(Pos.CENTER);

    Label lbl = new Label(label);
    lbl.setStyle("-fx-text-fill: #ccc; -fx-font-size: 10px;");

    Slider slider = new Slider(min, max, def);
    slider.setPrefWidth(100);
    slider.setShowTickMarks(true);
    slider.setShowTickLabels(false);

    javafx.scene.control.ContextMenu contextMenu = new javafx.scene.control.ContextMenu();
    javafx.scene.control.MenuItem learnItem = new javafx.scene.control.MenuItem("MIDI Learn");
    contextMenu.getItems().add(learnItem);
    slider.setContextMenu(contextMenu);

    learnItem.setOnAction(e -> {
        midiService.startLearn(paramName);
    });

    // Initialize VM value
    vm.setGlobalFloat(paramName, def);

    slider
        .valueProperty()
        .addListener(
            (obs, oldVal, newVal) -> {
              vm.setGlobalFloat(paramName, newVal.floatValue());
            });

    box.getChildren().addAll(lbl, slider);
    return box;
  }
}
