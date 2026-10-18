package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.Clip;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import org.deluge.BridgeContract;
import org.deluge.midi.MidiInputRouter;
import org.deluge.midi.MidiService;
import org.deluge.model.KitTrackModel;
import org.deluge.model.MIDIDrum;
import org.deluge.model.SoundDrum;
import org.deluge.project.PreferencesManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Portable unit tests for {@link PreferencesDialog}, {@link LibrarySidebarTab}, {@link
 * HardwareSidebarTab}, {@link LibraryPicker}, and {@link FileMenuController}.
 */
class PreferencesSidebarLibraryPickerTest {

  @TempDir Path tempDir;

  @Test
  void testPreferencesDialogFriendlyPortMatchAndCleanupAndFollowBounds() throws Exception {
    if (GraphicsEnvironment.isHeadless()) {
      return;
    }

    String prevChA = PreferencesManager.get("midi.follow.chA", "1");
    String prevChB = PreferencesManager.get("midi.follow.chB", "1");
    String prevTrA = PreferencesManager.get("midi.follow.trackA", "0");
    String prevTrB = PreferencesManager.get("midi.follow.trackB", "1");
    try {
      PreferencesManager.set("midi.follow.chA", "99");
      PreferencesManager.set("midi.follow.chB", "0");
      PreferencesManager.set("midi.follow.trackA", "-5");
      PreferencesManager.set("midi.follow.trackB", "not-a-number");

      BridgeContract bridge = new BridgeContract();
      MidiService midiService = new MidiService(bridge, new MidiInputRouter(bridge));

      SwingUtilities.invokeAndWait(
          () -> {
            PreferencesDialog dlg = new PreferencesDialog(null, midiService, () -> {}, () -> {});
            try {
              dlg.setMidiPortsForTest(
                  new String[] {"Deluge Port 1"},
                  new String[] {"Synthstrom Audible - Deluge Port 1"});

              assertTrue(
                  dlg.isMidiPortListUnchanged(new String[] {"Deluge Port 1"}),
                  "Raw port matching friendly combo label must not trigger spurious rebuild");
              assertFalse(
                  dlg.isMidiPortListUnchanged(new String[] {"Other Port"}),
                  "Different raw port must be detected as changed");

              dlg.getLearnParamFieldForTest().setText("g_master_vol");
              dlg.getLearnButtonForTest().doClick();
              assertTrue(midiService.isLearning(), "MIDI learn should start on button click");

              dlg.dispose();
              assertFalse(
                  midiService.isLearning(),
                  "Disposing PreferencesDialog must cancel active MIDI learn");
            } finally {
              if (dlg.isDisplayable()) {
                dlg.dispose();
              }
            }
          });
    } finally {
      PreferencesManager.set("midi.follow.chA", prevChA);
      PreferencesManager.set("midi.follow.chB", prevChB);
      PreferencesManager.set("midi.follow.trackA", prevTrA);
      PreferencesManager.set("midi.follow.trackB", prevTrB);
    }
  }

  @Test
  void testLibrarySidebarExtensionStrippingAndNonSoundDrumKitSync() {
    assertEquals(
        "arp_demo",
        LibrarySidebarTab.stripPresetExtension("arp_demo.ck"),
        ".ck extension is 3 chars and must not strip the last character of the base name");
    assertEquals("LEAD01", LibrarySidebarTab.stripPresetExtension("LEAD01.XML"));
    assertEquals("NoExt", LibrarySidebarTab.stripPresetExtension("NoExt"));

    BridgeContract bridge = new BridgeContract();
    KitTrackModel kit = new KitTrackModel("MixedKit");

    SoundDrum kick = new SoundDrum("Kick");
    kick.setSamplePath("SAMPLES/DRUMS/Kick.wav");
    kit.addDrum(kick);

    MIDIDrum midiDrum = new MIDIDrum("ExtMIDI", 1, 36);
    kit.addDrum(midiDrum);

    // Add 16 more SoundDrums so total drums (18) > BridgeContract.TRACKS (16)
    for (int i = 0; i < 16; i++) {
      SoundDrum extra = new SoundDrum("Extra" + i);
      extra.setSamplePath("SAMPLES/DRUMS/Extra" + i + ".wav");
      kit.addDrum(extra);
    }

    assertDoesNotThrow(
        () -> LibrarySidebarTab.syncKitSamplePathsToBridge(bridge, kit, 0, true, true),
        "Kit with MIDIDrum and >16 drums must not throw ClassCastException or bounds exception");

    assertEquals("SAMPLES/DRUMS/Kick.wav", bridge.getGlobalString("g_sample_0"));
    assertEquals("", bridge.getGlobalString("g_sample_1"));
    assertEquals("SAMPLES/DRUMS/Extra13.wav", bridge.getGlobalString("g_sample_15"));
  }

  @Test
  void testHardwareSidebarScanDelugePortsDoesNotReturnLogWhenNoPortReplies() throws Exception {
    if (GraphicsEnvironment.isHeadless()) {
      return;
    }

    BridgeContract bridge = new BridgeContract();
    MidiService midiService = new MidiService(bridge, new MidiInputRouter(bridge));

    SwingUtilities.invokeAndWait(
        () -> {
          SwingProjectSidebarPanel sidebar = new SwingProjectSidebarPanel(bridge, midiService);
          HardwareSidebarTab hwTab = new HardwareSidebarTab(sidebar);
          StringBuilder log = new StringBuilder();
          String working = hwTab.scanDelugePorts(log);

          assertTrue(log.length() > 0, "scanDelugePorts must append diagnostics to StringBuilder");
          if (working != null) {
            assertFalse(
                working.contains("\n") || working.contains("Output ports:"),
                "scanDelugePorts must return a port name, never the diagnostic log");
          }
        });
  }

  @Test
  void testLibraryPickerPathMatchingBoundaryAndDisposeClosesAuditionClip() throws Exception {
    if (GraphicsEnvironment.isHeadless()) {
      return;
    }

    assertFalse(
        LibraryPicker.pathMatchesRel("SAMPLES/DRUMS/sub_kick.wav", "kick.wav"),
        "Suffix without '/' boundary must not match");
    assertTrue(LibraryPicker.pathMatchesRel("SAMPLES/DRUMS/kick.wav", "kick.wav"));
    assertTrue(LibraryPicker.pathMatchesRel("kick.wav", "kick.wav"));

    String prevLibDir = PreferencesManager.get("library_dir", "");
    String prevSysCard = System.getProperty("deluge.card");
    try {
      File samplesRoot = tempDir.resolve("SAMPLES").toFile();
      assertTrue(samplesRoot.mkdirs());
      Files.write(samplesRoot.toPath().resolve("kick.wav"), new byte[] {0, 1, 2, 3});
      Files.write(samplesRoot.toPath().resolve("sub_kick.wav"), new byte[] {0, 1, 2, 3});
      System.setProperty("deluge.card", tempDir.toFile().getAbsolutePath());
      PreferencesManager.setLibraryDir(tempDir.toFile().getAbsolutePath());

      AtomicBoolean clipStopped = new AtomicBoolean(false);
      AtomicBoolean clipClosed = new AtomicBoolean(false);
      Clip mockClip = createMockClip(clipStopped, clipClosed);

      SwingUtilities.invokeAndWait(
          () -> {
            JButton anchor = new JButton("Pick");
            LibraryPicker picker =
                LibraryPicker.createForTest(
                    anchor,
                    LibraryPicker.Scope.SAMPLES,
                    "SAMPLES/sub_kick.wav",
                    List.of(new LibraryPicker.Action("Replace", java.awt.Color.GRAY, f -> {})));
            try {
              assertEquals(
                  "sub_kick.wav",
                  picker.getListForTest().getSelectedValue(),
                  "Picker must select exact relative file match, not false suffix match");
              picker.setActiveAuditionClipForTest(mockClip);
              picker.dispose();
            } finally {
              if (picker.isDisplayable()) {
                picker.dispose();
              }
            }
          });

      assertTrue(clipStopped.get(), "dispose() must stop an active audition clip");
      assertTrue(clipClosed.get(), "dispose() must close an active audition clip");
    } finally {
      if (prevSysCard == null) {
        System.clearProperty("deluge.card");
      } else {
        System.setProperty("deluge.card", prevSysCard);
      }
      PreferencesManager.setLibraryDir(prevLibDir);
    }
  }

  @Test
  void testFileMenuControllerProgressChunkWithPipeInTrackName() {
    JLabel statusLabel = new JLabel();
    JProgressBar progressBar = new JProgressBar(0, 100);

    FileMenuController.applyProgressChunk("Rendering Lead | Sub...|75", statusLabel, progressBar);
    assertEquals("Rendering Lead | Sub...", statusLabel.getText());
    assertEquals(75, progressBar.getValue());
  }

  private static Clip createMockClip(AtomicBoolean stopped, AtomicBoolean closed) {
    InvocationHandler handler =
        (Object proxy, Method method, Object[] args) -> {
          String name = method.getName();
          if ("stop".equals(name)) {
            stopped.set(true);
            return null;
          }
          if ("close".equals(name)) {
            closed.set(true);
            return null;
          }
          if ("isOpen".equals(name) || "isRunning".equals(name) || "isActive".equals(name)) {
            return !closed.get();
          }
          Class<?> rt = method.getReturnType();
          if (rt == boolean.class) return false;
          if (rt == int.class) return 0;
          if (rt == long.class) return 0L;
          if (rt == float.class) return 0.0f;
          return null;
        };
    return (Clip)
        Proxy.newProxyInstance(Clip.class.getClassLoader(), new Class<?>[] {Clip.class}, handler);
  }
}
