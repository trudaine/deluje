package org.chuck.deluge.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;

/**
 * UI Panel for controlling global effects (Reverb, Delay).
 */
public class MasterFxPanel extends HBox {
    private final ChuckVM vm;

    public MasterFxPanel(ChuckVM vm) {
        this.vm = vm;

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
        if (controlsInitialized) return;
        Object revObj = vm.getGlobalObject("g_reverb");
        if (revObj == null) return;

        javafx.application.Platform.runLater(() -> {
            getChildren().clear();

            Label title = new Label("MASTER FX");
            title.setStyle("-fx-text-fill: #aaa; -fx-font-weight: bold;");
            getChildren().add(title);

            // Introspect Reverb!
            java.lang.reflect.Method[] methods = revObj.getClass().getMethods();
            for (java.lang.reflect.Method m : methods) {
                // Look for methods taking a single float!
                if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == float.class) {
                    String name = m.getName();
                    if (!name.equals("wait") && !name.equals("equals")) {
                         getChildren().add(createDynamicSlider(revObj, m));
                    }
                }
            }

            // Keep hardcoded sliders for Delay for now!
            getChildren().add(createSlider("Delay Time", BridgeContract.G_DELAY_TIME, 0.1, 2.0, 0.5));
            getChildren().add(createSlider("Delay FB", BridgeContract.G_DELAY_FB, 0.0, 0.9, 0.3));
            
            controlsInitialized = true;
        });
    }

    private VBox createDynamicSlider(Object target, java.lang.reflect.Method m) {
        VBox box = new VBox(5);
        box.setAlignment(Pos.CENTER);

        Label lbl = new Label(m.getName().toUpperCase());
        lbl.setStyle("-fx-text-fill: #ccc; -fx-font-size: 10px;");

        Slider slider = new Slider(0.0, 1.0, 0.5); // Default 0-1 range
        slider.setPrefWidth(100);
        slider.setShowTickMarks(true);

        slider.valueProperty().addListener((obs, oldVal, newVal) -> {
            try {
                m.invoke(target, newVal.floatValue());
            } catch (Exception e) {
                e.printStackTrace();
            }
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
        
        // Initialize VM value
        vm.setGlobalFloat(paramName, def);
        
        slider.valueProperty().addListener((obs, oldVal, newVal) -> {
            vm.setGlobalFloat(paramName, newVal.floatValue());
        });
        
        box.getChildren().addAll(lbl, slider);
        return box;
    }
}
