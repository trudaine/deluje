package org.chuck.deluge.ui.browser;

import java.io.File;
import java.util.Arrays;
import javafx.application.Platform;
import javafx.scene.control.TreeItem;

/** Background thread worker to lazily load the filesystem into the JavaFX TreeView. */
public class SampleTreeLoader {

  /**
   * Lazily loads the children of a given TreeItem folder. Runs in a background thread and adds
   * children to the UI thread safely.
   */
  public static void loadChildrenAsync(TreeItem<File> parentItem) {
    if (!parentItem.getChildren().isEmpty()) return; // Already loaded

    File parentDir = parentItem.getValue();
    if (parentDir == null || !parentDir.isDirectory()) return;

    // Show a loading indicator
    TreeItem<File> loadingItem = new TreeItem<>(new File("Loading..."));
    parentItem.getChildren().add(loadingItem);

    Thread loaderThread =
        new Thread(
            () -> {
              File[] files =
                  parentDir.listFiles(
                      (dir, name) -> {
                        File f = new File(dir, name);
                        if (f.isDirectory()) return true;
                        String lower = name.toLowerCase();
                        return lower.endsWith(".wav") || lower.endsWith(".aif");
                      });

              if (files != null) {
                Arrays.sort(
                    files,
                    (f1, f2) -> {
                      // Sort dirs first, then alphabetical
                      if (f1.isDirectory() && !f2.isDirectory()) return -1;
                      if (!f1.isDirectory() && f2.isDirectory()) return 1;
                      return f1.getName().compareToIgnoreCase(f2.getName());
                    });

                Platform.runLater(
                    () -> {
                      parentItem.getChildren().clear(); // remove "Loading..."
                      for (File f : files) {
                        TreeItem<File> item = new TreeItem<>(f);
                        if (f.isDirectory()) {
                          // Add a dummy child so it shows an expand arrow
                          item.getChildren().add(new TreeItem<>(new File("")));
                        }
                        parentItem.getChildren().add(item);
                      }
                    });
              } else {
                Platform.runLater(parentItem.getChildren()::clear);
              }
            });

    loaderThread.setDaemon(true);
    loaderThread.start();
  }
}
