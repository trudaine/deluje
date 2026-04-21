package org.chuck.deluge.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TreeView;
import javafx.scene.control.TreeItem;
import javafx.scene.layout.VBox;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;

/**
 * Sidebar Project Manager.
 * Contains the Project Tree (Tracks/Clips) and the Library (SD Card Emulator).
 */
public class ProjectSidebarPanel extends VBox {
  private final ChuckVM vm;
  private final BridgeContract bridge;

  public ProjectSidebarPanel(ChuckVM vm, BridgeContract bridge) {
    this.vm = vm;
    this.bridge = bridge;

    setMinWidth(250);
    setMaxWidth(300);
    setPadding(new Insets(10));
    setSpacing(10);
    setStyle("-fx-background-color: #252525; -fx-border-color: #333; -fx-border-width: 0 1 0 0;");

    Label title = new Label("PROJECT MANAGER");
    title.setStyle("-fx-text-fill: #aaa; -fx-font-weight: bold; -fx-font-size: 14;");
    getChildren().add(title);

    TabPane tabs = new TabPane();
    tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

    Tab projectTab = new Tab("PROJECT", createProjectTree());
    Tab libraryTab = new Tab("LIBRARY", createLibraryBrowser());

    tabs.getTabs().addAll(projectTab, libraryTab);
    getChildren().add(tabs);
    VBox.setVgrow(tabs, javafx.scene.layout.Priority.ALWAYS);
  }

  private VBox createProjectTree() {
    VBox box = new VBox(5);
    TreeItem<String> root = new TreeItem<>("My Song");
    root.setExpanded(true);

    for (int i = 1; i <= 8; i++) {
        TreeItem<String> track = new TreeItem<>("Track " + i);
        track.getChildren().add(new TreeItem<>("Clip 1"));
        root.getChildren().add(track);
    }

    TreeView<String> tree = new TreeView<>(root);
    tree.setStyle("-fx-background-color: #252525; -fx-control-inner-background: #252525; -fx-text-fill: white;");
    box.getChildren().add(tree);
    VBox.setVgrow(tree, javafx.scene.layout.Priority.ALWAYS);
    return box;
  }

  private VBox createLibraryBrowser() {
    VBox box = new VBox(5);
    TreeItem<String> root = new TreeItem<>("SD CARD");
    root.setExpanded(true);

    TreeItem<String> kitsItem = new TreeItem<>("KITS");
    kitsItem.getChildren().add(new TreeItem<>("TR-909"));
    kitsItem.getChildren().add(new TreeItem<>("TR-808"));
    
    root.getChildren().add(kitsItem);
    root.getChildren().add(new TreeItem<>("SYNTHS"));
    root.getChildren().add(new TreeItem<>("SAMPLES"));
    root.getChildren().add(new TreeItem<>("SONGS"));

    TreeView<String> tree = new TreeView<>(root);
    tree.setStyle("-fx-background-color: #252525; -fx-control-inner-background: #252525; -fx-text-fill: white;");
    
    tree.setOnMouseClicked(event -> {
        if (event.getClickCount() == 2) {
            TreeItem<String> item = tree.getSelectionModel().getSelectedItem();
            if (item != null && item.getParent() != null && item.getParent().getValue().equals("KITS")) {
                loadKitFromResources(item.getValue());
            }
        }
    });

    box.getChildren().add(tree);
    VBox.setVgrow(tree, javafx.scene.layout.Priority.ALWAYS);
    return box;
  }

  private void loadKitFromResources(String kitName) {
      System.out.println("Loading kit from resources: " + kitName);
      // In a real app, we'd have these XMLs in src/main/resources/kits/
      // For this demo, let's assume we have a way to get the MatrixPanel from MainPanel
      // and call applyKit. I'll add a callback for this.
      if (onKitRequest != null) {
          onKitRequest.accept(kitName);
      }
  }

  private java.util.function.Consumer<String> onKitRequest;
  public void setOnKitRequest(java.util.function.Consumer<String> callback) {
      this.onKitRequest = callback;
  }
}
