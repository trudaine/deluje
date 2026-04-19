package org.chuck.deluge.project;

import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.chuck.deluge.model.ProjectModel;

/** Periodically saves the project model to an autosave file. */
public class AutoSaveService {

  private final ScheduledExecutorService executor;
  private final ProjectModel projectModel;
  private final File autoSaveFile;

  public AutoSaveService(ProjectModel model) {
    this.projectModel = model;

    // Ensure the songs directory exists
    File songsDir = new File(PreferencesManager.getSamplesDir()).getParentFile();
    if (songsDir != null) {
      songsDir = new File(songsDir, "SONGS");
      if (!songsDir.exists()) songsDir.mkdirs();
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

  public void start(int intervalMinutes) {
    executor.scheduleAtFixedRate(
        this::performSave, intervalMinutes, intervalMinutes, TimeUnit.MINUTES);

    // Register shutdown hook
    Runtime.getRuntime().addShutdownHook(new Thread(this::performSave, "AutoSave-Shutdown"));
  }

  public void stop() {
    executor.shutdownNow();
  }

  public void performSave() {
    try {
      System.out.println("[AutoSave] Saving project to " + autoSaveFile.getAbsolutePath());
      ProjectSerializer.save(projectModel, autoSaveFile);
    } catch (Exception e) {
      System.err.println("[AutoSave] Failed: " + e.getMessage());
    }
  }
}
