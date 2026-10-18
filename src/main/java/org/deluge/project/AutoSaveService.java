package org.deluge.project;

import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.deluge.model.ProjectModel;

/** Periodically saves the project model to an autosave file. */
public class AutoSaveService {
  private static final Logger LOGGER = Logger.getLogger(AutoSaveService.class.getName());

  private final ScheduledExecutorService executor;
  private final ProjectModel projectModel;
  private final File autoSaveFile;
  private Thread shutdownHook;

  public AutoSaveService(ProjectModel model) {
    this.projectModel = model;

    // Ensure the songs directory exists
    File songsDir = PreferencesManager.getSongsDir();
    if (songsDir != null && songsDir.exists()) {
      this.autoSaveFile = new File(songsDir, "_autosave.xml");
    } else if (songsDir != null) {
      songsDir.mkdirs();
      this.autoSaveFile = new File(songsDir, "_autosave.xml");
    } else {
      this.autoSaveFile = new File("_autosave.xml");
    }

    this.executor =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "AutoSave-Thread");
              t.setDaemon(true);
              return t;
            });
  }

  public synchronized void start(int intervalMinutes) {
    int safeInterval = Math.max(1, intervalMinutes);
    executor.scheduleAtFixedRate(this::performSave, safeInterval, safeInterval, TimeUnit.MINUTES);

    // Register shutdown hook once
    if (shutdownHook == null) {
      shutdownHook = new Thread(this::performSave, "AutoSave-Shutdown");
      Runtime.getRuntime().addShutdownHook(shutdownHook);
    }
  }

  public synchronized void stop() {
    executor.shutdownNow();
    if (shutdownHook != null) {
      try {
        Runtime.getRuntime().removeShutdownHook(shutdownHook);
      } catch (IllegalStateException ignored) {
        // JVM is already shutting down
      }
      shutdownHook = null;
    }
  }

  public void performSave() {
    try {
      LOGGER.fine("[AutoSave] Saving project to " + autoSaveFile.getAbsolutePath());
      ProjectSerializer.save(projectModel, autoSaveFile);
    } catch (Exception e) {
      LOGGER.warning("[AutoSave] Failed: " + e.getMessage());
    }
  }
}
