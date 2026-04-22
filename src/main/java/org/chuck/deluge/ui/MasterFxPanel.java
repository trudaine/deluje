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
  private final java.util.Map<String, javafx.scene.control.Slider> paramSliders = new java.util.HashMap<>();

  public MasterFxPanel(ChuckVM vm, org.chuck.deluge.midi.MidiService midiService) {
    this.vm = vm;
    this.midiService = midiService;

    setAlignment(Pos.CENTER_LEFT);
    setSpacing(20);
    setPadding(new Insets(10, 10, 10, 519));
    setStyle("-fx-background-color: #222; -fx-border-color: #333; -fx-border-width: 1 0 0 0;");

    Label title = new Label("MASTER FX (Waiting for Engine...)");
    title.setStyle("-fx-text-fill: #aaa; -fx-font-weight: bold;");
    getChildren().add(title);
    
    startTimer();
  }

  private void startTimer() {
    javafx.animation.AnimationTimer timer = new javafx.animation.AnimationTimer() {
      @Override
      public void handle(long now) {
        for (java.util.Map.Entry<String, javafx.scene.control.Slider> entry : paramSliders.entrySet()) {
          String param = entry.getKey();
          javafx.scene.control.Slider slider = entry.getValue();
          double currentVal = vm.getGlobalFloat(param);
          if (Math.abs(slider.getValue() - currentVal) > 0.01) {
            slider.setValue(currentVal);
          }
        }
      }
    };
    timer.start();
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
          paramSliders.clear();

          Label title = new Label("MASTER FX");
          title.getStyleClass().add("master-fx-label");
          title.setStyle("-fx-font-size: 12px;");
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
    lbl.getStyleClass().add("master-fx-label");

    String paramName = "reverb." + m.getName();
    
    Slider slider = new Slider(0.0, 1.0, 0.5); // Default 0-1 range
    slider.setPrefWidth(100);
    slider.setShowTickMarks(true);
    
    paramSliders.put(paramName, slider);

    javafx.scene.control.ContextMenu contextMenu = new javafx.scene.control.ContextMenu();
    javafx.scene.control.MenuItem learnItem = new javafx.scene.control.MenuItem("MIDI Learn");
    javafx.scene.control.MenuItem clearItem = new javafx.scene.control.MenuItem("Clear MIDI Mapping");
    contextMenu.getItems().addAll(learnItem, clearItem);
    slider.setContextMenu(contextMenu);

    learnItem.setOnAction(e -> {
        midiService.startLearn(paramName);
    });

    clearItem.setOnAction(e -> {
        midiService.unlearn(paramName);
    });
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
    lbl.getStyleClass().add("master-fx-label");

    Slider slider = new Slider(min, max, def);
    slider.setPrefWidth(100);
    paramSliders.put(paramName, slider);
    slider.setShowTickMarks(true);
    slider.setShowTickLabels(false);

    javafx.scene.control.ContextMenu contextMenu = new javafx.scene.control.ContextMenu();
    javafx.scene.control.MenuItem learnItem = new javafx.scene.control.MenuItem("MIDI Learn");
    javafx.scene.control.MenuItem clearItem = new javafx.scene.control.MenuItem("Clear MIDI Mapping");
    contextMenu.getItems().addAll(learnItem, clearItem);
    slider.setContextMenu(contextMenu);

    learnItem.setOnAction(e -> {
        midiService.startLearn(paramName);
    });

    clearItem.setOnAction(e -> {
        midiService.unlearn(paramName);
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
