package org.chuck.deluge.ui.popover;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import org.chuck.deluge.project.PreferencesManager;

/**
 * Dialog to configure mappings between UI controls and ChucK engine elements.
 */
public class MappingConfigDialog extends Dialog<Void> {

    public MappingConfigDialog() {
        setTitle("Mapping Configuration");
        setHeaderText("Configure UI to Engine Mappings");

        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 150, 10, 10));

        // Reverb Mapping
        grid.add(new Label("Reverb Model:"), 0, 0);
        ComboBox<String> reverbCombo = new ComboBox<>();
        reverbCombo.getItems().addAll("JCRev", "FreeVerb");
        
        String currentReverb = PreferencesManager.get("reverb.model", "JCRev");
        reverbCombo.setValue(currentReverb);
        
        grid.add(reverbCombo, 1, 0);

        getDialogPane().setContent(grid);

        setResultConverter(dialogButton -> {
            if (dialogButton == ButtonType.OK) {
                PreferencesManager.set("reverb.model", reverbCombo.getValue());
            }
            return null;
        });
    }
}
