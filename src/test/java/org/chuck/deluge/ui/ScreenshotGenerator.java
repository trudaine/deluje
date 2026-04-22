package org.chuck.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javax.imageio.ImageIO;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.midi.MidiInputRouter;
import org.chuck.deluge.midi.MidiService;
import org.junit.jupiter.api.Test;

public class ScreenshotGenerator {

  private ChuckVM vm;
  private BridgeContract bridge;
  private MidiService midiService;
  private DelugeMainPanel mainPanel;
  private Scene scene;
  private WritableImage currentSnapshot;

  @Test
  public void testGenerateScreenshots() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    System.setProperty("deluge.tracks", "8");
    
    // Start JavaFX
    CountDownLatch startupLatch = new CountDownLatch(1);
    Platform.startup(startupLatch::countDown);
    startupLatch.await();
    
    // Init UI on FX thread
    runAndWait(() -> {
      vm = new ChuckVM(44100, 2);
      bridge = new BridgeContract();
      bridge.register(vm);
      
      MidiInputRouter router = new MidiInputRouter(vm, bridge);
      midiService = new MidiService(vm, bridge, router);
      
      mainPanel = new DelugeMainPanel(vm, bridge, null, midiService);
      scene = new Scene(mainPanel, 1200, 800);
    });
    
    // Step 0: Start
    runAndWait(() -> currentSnapshot = scene.snapshot(null));
    saveSnapshot(currentSnapshot, "../docs/step0_start.png", "Step 0: Initial State", "Empty grid on startup.");
    
    // Step 1: Load Song (Simulated)
    runAndWait(() -> {
      // Expand tree in sidebar
      ProjectSidebarPanel sidebar = mainPanel.getSidebarPanel();
      if (sidebar != null) {
          javafx.scene.control.TabPane tabs = (javafx.scene.control.TabPane) sidebar.getChildren().get(1);
          javafx.scene.control.Tab libraryTab = tabs.getTabs().get(0);
          javafx.scene.layout.VBox libBox = (javafx.scene.layout.VBox) libraryTab.getContent();
          javafx.scene.control.TreeView<ProjectSidebarPanel.LibraryItem> tree = 
              (javafx.scene.control.TreeView<ProjectSidebarPanel.LibraryItem>) libBox.getChildren().get(0);
              
          javafx.scene.control.TreeItem<ProjectSidebarPanel.LibraryItem> root = tree.getRoot();
          if (root != null) {
              for (javafx.scene.control.TreeItem<ProjectSidebarPanel.LibraryItem> child : root.getChildren()) {
                  if ("SONGS".equals(child.getValue().name)) {
                      child.setExpanded(true);
                      for (javafx.scene.control.TreeItem<ProjectSidebarPanel.LibraryItem> songItem : child.getChildren()) {
                          if ("song1".equals(songItem.getValue().name)) {
                              tree.getSelectionModel().select(songItem);
                              break;
                          }
                      }
                      break;
                  }
              }
          }
      }
      
      // Simulate loaded steps (to ensure grid changes visually)
      bridge.setStep(0, 2, true);
      bridge.setStep(0, 5, true);
      bridge.setStep(0, 8, true);
      bridge.setStep(0, 10, true);
      
      mainPanel.getSongPanel().refresh();
    });
    
    runAndWait(() -> currentSnapshot = scene.snapshot(null));
    saveSnapshot(currentSnapshot, "../docs/step1_loaded.png", "Step 1: Song Loaded", "Grid populated from song1.xml.");
    
    // Step 2: Edit Cells
    runAndWait(() -> {
      bridge.setStep(0, 0, true);
      bridge.setStep(0, 4, true);
      bridge.setStep(0, 8, true);
      bridge.setStep(0, 12, true);
    });
    
    runAndWait(() -> currentSnapshot = scene.snapshot(null));
    saveSnapshot(currentSnapshot, "../docs/step2_edited.png", "Step 2: Cells Edited", "Toggled steps 0, 4, 8, 12 on track 0.");
    
    // Step 3: Play
    runAndWait(() -> vm.setGlobalInt(BridgeContract.G_PLAY, 1L));
    runAndWait(() -> currentSnapshot = scene.snapshot(null));
    saveSnapshot(currentSnapshot, "../docs/step3_playing.png", "Step 3: Playing", "Transport in Play state.");
    
    // Step 4: Record
    runAndWait(() -> midiService.setRecording(true));
    runAndWait(() -> currentSnapshot = scene.snapshot(null));
    saveSnapshot(currentSnapshot, "../docs/step4_recording.png", "Step 4: Recording", "Transport in Record state.");
    
    // Take Preferences Dialog snapshot
    runAndWait(() -> {
      org.chuck.deluge.ui.popover.PreferencesDialog preferencesDialog = 
          new org.chuck.deluge.ui.popover.PreferencesDialog(midiService);
      javafx.scene.control.DialogPane dialogPane = preferencesDialog.getDialogPane();
      currentSnapshot = dialogPane.snapshot(null, null);
    });
    saveSnapshot(currentSnapshot, "../docs/preferences_dialog_annotated.png", "Preferences", "Application settings.");
  }
  
  private void runAndWait(Runnable r) throws Exception {
    CountDownLatch l = new CountDownLatch(1);
    Platform.runLater(() -> {
      try {
        r.run();
      } finally {
        l.countDown();
      }
    });
    l.await();
  }
  
  private void saveSnapshot(WritableImage image, String path, String title, String description) throws Exception {
    int width = (int) image.getWidth();
    int height = (int) image.getHeight();
    PixelReader reader = image.getPixelReader();
    BufferedImage bImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        bImage.setRGB(x, y, reader.getArgb(x, y));
      }
    }
    
    int margin = 250;
    BufferedImage annotated = new BufferedImage(width + margin, height, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = annotated.createGraphics();
    
    g.setColor(Color.DARK_GRAY);
    g.fillRect(0, 0, width + margin, height);
    g.drawImage(bImage, 0, 0, null);
    
    g.setColor(Color.CYAN);
    g.drawString(title, width + 10, 50);
    g.setColor(Color.WHITE);
    g.drawString(description, width + 10, 80);
    
    g.setColor(Color.CYAN);
    g.drawString("<- Matrix Grid", width + 10, 200);
    g.drawString("<- Transport Panel", width + 10, 700);
    g.drawString("<- Master FX Panel", width + 10, 750);
    
    g.dispose();
    
    File output = new File(path);
    output.getParentFile().mkdirs();
    ImageIO.write(annotated, "png", output);
    System.out.println("Screenshot saved to " + output.getAbsolutePath());
  }
}
