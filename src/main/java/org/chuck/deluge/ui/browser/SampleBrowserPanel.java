package org.chuck.deluge.ui.browser;

import java.io.File;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.chuck.deluge.model.KitTrackModel;
import org.chuck.deluge.project.PreferencesManager;

/** A file browser for finding and previewing audio samples. */
public class SampleBrowserPanel extends Stage {

  private final KitTrackModel targetModel;
  private final FavoritesManager favsManager;
  private final TreeView<File> treeView;
  private final Canvas waveCanvas;
  private final Label infoLabel;
  private final TextField searchField;
  private final Button assignBtn;
  private final Button favBtn;

  private File selectedFile = null;
  private WavPeakDecoder.WavInfo currentWavInfo = null;

  public SampleBrowserPanel(KitTrackModel targetModel) {
    this.targetModel = targetModel;
    this.favsManager = new FavoritesManager();

    setTitle("Sample Browser");
    initStyle(StageStyle.UTILITY);
    initModality(Modality.NONE);

    VBox root = new VBox(10);
    root.setPadding(new Insets(10));
    root.setStyle("-fx-background-color: #2b2b2b;");

    // ── Search & Root Bar ──
    HBox topBar = new HBox(10);
    topBar.setAlignment(Pos.CENTER_LEFT);

    searchField = new TextField();
    searchField.setPromptText("Search...");
    HBox.setHgrow(searchField, Priority.ALWAYS);

    Button showFavsBtn = new Button("⭐ Favs");
    showFavsBtn.setOnAction(e -> showFavoritesOnly());

    topBar.getChildren().addAll(searchField, showFavsBtn);

    // ── Tree View ──
    treeView = new TreeView<>();
    treeView.setStyle("-fx-control-inner-background: #333; -fx-background-color: #333;");
    VBox.setVgrow(treeView, Priority.ALWAYS);

    treeView.setCellFactory(
        tv ->
            new TreeCell<File>() {
              @Override
              protected void updateItem(File item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                  setText(null);
                  setGraphic(null);
                } else {
                  String text = item.getName();
                  if (item.isDirectory()) text = "📁 " + text;
                  else text = "🎵 " + text;

                  if (favsManager.isFavorite(item)) {
                    text += " ⭐";
                  }

                  setText(text);
                  setTextFill(Color.web("#e0e0e0"));
                }
              }
            });

    treeView
        .getSelectionModel()
        .selectedItemProperty()
        .addListener(
            (obs, oldVal, newVal) -> {
              if (newVal != null && newVal.getValue() != null && !newVal.getValue().isDirectory()) {
                selectFile(newVal.getValue());
              }
            });

    // Lazy Loading logic
    treeView.setOnMouseClicked(
        event -> {
          TreeItem<File> item = treeView.getSelectionModel().getSelectedItem();
          if (item != null && item.getValue() != null && item.getValue().isDirectory()) {
            SampleTreeLoader.loadChildrenAsync(item);
          }

          if (event.getClickCount() == 2 && selectedFile != null) {
            assignSample();
          }
        });

    // ── Detail Panel (Waveform + Controls) ──
    HBox detailBox = new HBox(10);
    detailBox.setAlignment(Pos.CENTER_LEFT);

    VBox waveBox = new VBox(5);
    waveCanvas = new Canvas(200, 50);
    GraphicsContext gc = waveCanvas.getGraphicsContext2D();
    gc.setFill(Color.BLACK);
    gc.fillRect(0, 0, 200, 50);

    infoLabel = new Label("No sample selected");
    infoLabel.setTextFill(Color.web("#aaa"));
    waveBox.getChildren().addAll(waveCanvas, infoLabel);

    VBox btnBox = new VBox(5);
    btnBox.setAlignment(Pos.CENTER);

    Button auditionBtn = new Button("▶ Audition");
    auditionBtn.setOnAction(
        e -> {
          // TODO: Spork AudioPreviewShred
          System.out.println("Auditioning: " + selectedFile);
        });

    favBtn = new Button("⭐ Fav");
    favBtn.setOnAction(
        e -> {
          if (selectedFile != null) {
            favsManager.toggleFavorite(selectedFile);
            treeView.refresh();
            updateFavBtn();
          }
        });

    assignBtn = new Button("✓ Assign");
    assignBtn.setStyle("-fx-background-color: #2e7d32; -fx-text-fill: white;");
    assignBtn.setOnAction(e -> assignSample());
    assignBtn.setDisable(true);

    btnBox.getChildren().addAll(auditionBtn, favBtn, assignBtn);

    detailBox.getChildren().addAll(waveBox, btnBox);

    root.getChildren().addAll(topBar, treeView, detailBox);

    // Initial Load
    File rootDir = new File(PreferencesManager.getSamplesDir());
    if (rootDir.exists()) {
      TreeItem<File> rootItem = new TreeItem<>(rootDir);
      rootItem.setExpanded(true);
      treeView.setRoot(rootItem);
      SampleTreeLoader.loadChildrenAsync(rootItem);
    } else {
      treeView.setRoot(new TreeItem<>(new File("SAMPLES dir not found: " + rootDir)));
    }

    Scene scene = new Scene(root, 400, 500);
    setScene(scene);
  }

  private void selectFile(File file) {
    this.selectedFile = file;
    assignBtn.setDisable(false);
    updateFavBtn();

    // Decode in background to not block UI
    Thread decoder =
        new Thread(
            () -> {
              try {
                WavPeakDecoder.WavInfo info = WavPeakDecoder.decode(file, 200);
                Platform.runLater(
                    () -> {
                      this.currentWavInfo = info;
                      drawWaveform();
                      infoLabel.setText(
                          String.format(
                              "Dur: %.1fms | %dkHz", info.getDurationMs(), info.sampleRate / 1000));
                    });
              } catch (Exception e) {
                Platform.runLater(
                    () -> {
                      infoLabel.setText("Failed to decode: " + e.getMessage());
                      GraphicsContext gc = waveCanvas.getGraphicsContext2D();
                      gc.setFill(Color.BLACK);
                      gc.fillRect(0, 0, 200, 50);
                    });
              }
            });
    decoder.setDaemon(true);
    decoder.start();
  }

  private void updateFavBtn() {
    if (selectedFile != null && favsManager.isFavorite(selectedFile)) {
      favBtn.setText("★ Un-fav");
    } else {
      favBtn.setText("⭐ Fav");
    }
  }

  private void showFavoritesOnly() {
    TreeItem<File> favRoot = new TreeItem<>(new File("Favorites"));
    favRoot.setExpanded(true);
    for (String path : favsManager.getFavorites()) {
      File f = new File(path);
      if (f.exists()) {
        favRoot.getChildren().add(new TreeItem<>(f));
      }
    }
    treeView.setRoot(favRoot);
  }

  private void drawWaveform() {
    GraphicsContext gc = waveCanvas.getGraphicsContext2D();
    double w = waveCanvas.getWidth();
    double h = waveCanvas.getHeight();

    gc.setFill(Color.web("#111"));
    gc.fillRect(0, 0, w, h);

    if (currentWavInfo == null || currentWavInfo.peaks == null) return;

    gc.setStroke(Color.web("#55aaFF"));
    gc.setLineWidth(1);

    float[] peaks = currentWavInfo.peaks;
    for (int i = 0; i < peaks.length && i < w; i++) {
      double p = peaks[i] * (h / 2.0);
      gc.strokeLine(i, (h / 2.0) - p, i, (h / 2.0) + p);
    }
  }

  private void assignSample() {
    if (selectedFile != null) {
      targetModel.setSamplePath(selectedFile.getAbsolutePath());
      close();
    }
  }
}
