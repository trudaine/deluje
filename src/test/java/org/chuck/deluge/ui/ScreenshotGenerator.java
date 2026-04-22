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

  @Test
  public void testGenerateScreenshots() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    System.setProperty("deluge.tracks", "8");
    
    CountDownLatch latch = new CountDownLatch(1);
    
    Platform.startup(() -> {
      try {
        ChuckVM vm = new ChuckVM(44100, 2);
        BridgeContract bridge = new BridgeContract();
        bridge.register(vm);
        
        MidiInputRouter router = new MidiInputRouter(vm, bridge);
        MidiService midiService = new MidiService(vm, bridge, router);
        
        DelugeMainPanel mainPanel = new DelugeMainPanel(vm, bridge, null, midiService);
        Scene scene = new Scene(mainPanel, 1200, 800);
        
        // Step 0: Start
        WritableImage img0 = scene.snapshot(null);
        saveSnapshot(img0, "../docs/step0_start.png", "Step 0: Initial State", "Empty grid on startup.");
        
        // Step 1: Load Song
        try (java.io.InputStream is = getClass().getResourceAsStream("/SONGS/song1.xml")) {
            if (is != null) {
                org.chuck.deluge.model.ProjectModel loadedProject =
                    org.chuck.deluge.xml.DelugeXmlParser.parseSong(is, "song1");
                mainPanel.getSongPanel().setProjectModel(loadedProject);
                mainPanel.getSongPanel().refresh();
            }
        }
        WritableImage img1 = scene.snapshot(null);
        saveSnapshot(img1, "../docs/step1_loaded.png", "Step 1: Song Loaded", "Grid populated from song1.xml.");
        
        // Step 2: Edit Cells
        bridge.setStep(0, 0, true);
        bridge.setStep(0, 4, true);
        bridge.setStep(0, 8, true);
        bridge.setStep(0, 12, true);
        WritableImage img2 = scene.snapshot(null);
        saveSnapshot(img2, "../docs/step2_edited.png", "Step 2: Cells Edited", "Toggled steps 0, 4, 8, 12 on track 0.");
        
        // Step 3: Play
        vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
        WritableImage img3 = scene.snapshot(null);
        saveSnapshot(img3, "../docs/step3_playing.png", "Step 3: Playing", "Transport in Play state.");
        
        // Step 4: Record
        midiService.setRecording(true);
        WritableImage img4 = scene.snapshot(null);
        saveSnapshot(img4, "../docs/step4_recording.png", "Step 4: Recording", "Transport in Record state.");
        
        // Take Preferences Dialog snapshot
        org.chuck.deluge.ui.popover.PreferencesDialog preferencesDialog = 
            new org.chuck.deluge.ui.popover.PreferencesDialog(midiService);
        javafx.scene.control.DialogPane dialogPane = preferencesDialog.getDialogPane();
        WritableImage dialogImage = dialogPane.snapshot(null, null);
        saveSnapshot(dialogImage, "../docs/preferences_dialog_annotated.png", "Preferences", "Application settings.");
        
        latch.countDown();
      } catch (Exception e) {
        e.printStackTrace();
      }
    });
    
    latch.await();
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
    
    // Expand canvas for annotations
    int margin = 250;
    BufferedImage annotated = new BufferedImage(width + margin, height, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = annotated.createGraphics();
    
    g.setColor(Color.DARK_GRAY);
    g.fillRect(0, 0, width + margin, height);
    g.drawImage(bImage, 0, 0, null);
    
    // Draw annotations
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
