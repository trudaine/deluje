package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Locale;
import org.deluge.engine.AudioInputCaptureLine;
import org.deluge.engine.AudioRecordingUtil;
import org.deluge.model.AssetLibrary;
import org.deluge.model.ClipLibrary;
import org.deluge.model.MIDIDrum;
import org.deluge.ui.controls.DelugeEncoderKnob;
import org.deluge.ui.controls.StickyTurnFilter;
import org.deluge.ui.util.HelpTextManager;
import org.junit.jupiter.api.Test;

class Dx7CaptureControlsParityTest {

  @Test
  void dx7EnvUpdateReadsLevelIndexTwoInsteadOfRateIndexTwo() {
    byte[] patch = new byte[156];
    int opOff = 0;
    // rates[0..3] at opOff + 0..3; levels[0..3] at opOff + 4..7 (env.h:26-30)
    patch[opOff + 2] = 0; // rates[2] = 0
    patch[opOff + 4 + 2] = 99; // levels[2] = 99 (max sustain level)

    Dx7Voice.Dx7Env env = new Dx7Voice.Dx7Env();
    int outlevel = Dx7Voice.Dx7Env.scaleOutlevel(99) << 5;
    env.init(patch, opOff, outlevel, 0);

    // Advance to step 2 so level sits at step-2 target, then call update().
    int expectedLevel = (((Dx7Voice.Dx7Env.scaleOutlevel(99) >> 1) << 6) + outlevel - 4256) << 16;
    env.level = expectedLevel;
    env.update(patch, opOff, outlevel, 0);

    // C env.cpp:147 — update() sets targetLevel from levels[2] (99) and advances to step 2.
    assertEquals(expectedLevel, env.targetLevel);
    assertFalse(env.rising);
  }

  @Test
  void fmCoreExp2LookupMatchesDx7TablesBitExact() {
    for (int levelIn : new int[] {0, 4 << 24, 7 << 24, 10 << 24, 14 << 24}) {
      assertEquals(Dx7Tables.exp2Lookup(levelIn - (14 << 24)), FmCore.exp2Lookup(levelIn));
    }
    assertEquals(1 << 24, FmCore.exp2Lookup(14 << 24));
  }

  @Test
  void audioRecordingUtilMixPcm16LEHandlesNullInputs() {
    byte[] pcm = new byte[] {0x10, 0x01, 0x20, 0x02};
    assertArrayEquals(pcm, AudioRecordingUtil.mixPcm16LE(pcm, null));
    assertArrayEquals(pcm, AudioRecordingUtil.mixPcm16LE(null, pcm));
    assertArrayEquals(new byte[0], AudioRecordingUtil.mixPcm16LE(null, null));
  }

  @Test
  void audioInputCaptureLineFillMonitorBlockRejectsInvalidArguments() {
    AudioInputCaptureLine line = AudioInputCaptureLine.getInstance();
    assertFalse(line.fillMonitorBlock(null, 64));
    assertFalse(line.fillMonitorBlock(new int[128], 0));
    assertFalse(line.fillMonitorBlock(new int[10], 8));
  }

  @Test
  void assetLibraryExposesSongsList() {
    Locale prev = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr"));
      AssetLibrary lib = new AssetLibrary();
      assertNotNull(lib.getSynths());
      assertNotNull(lib.getKits());
      assertNotNull(lib.getSongs());
    } finally {
      Locale.setDefault(prev);
    }
  }

  @Test
  void clipLibraryHandlesZeroTracksAndNegativeDimensionsSafely() {
    ClipLibrary empty = new ClipLibrary(0, 8);
    assertNull(empty.getClip(0, 0));

    ClipLibrary neg = new ClipLibrary(-2, -4);
    assertNull(neg.getClip(0, 0));

    ClipLibrary normal = new ClipLibrary(2, 3);
    assertNotNull(normal.getClip(1, 2));
    assertNull(normal.getClip(1, 3));
  }

  @Test
  void midiDrumThreeArgConstructorClampsChannelAndNoteAndNullMessageType() {
    MIDIDrum drumHigh = new MIDIDrum("High", 99, 200);
    assertEquals(16, drumHigh.getMidiChannel());
    assertEquals(127, drumHigh.getMidiNote());

    MIDIDrum drumLow = new MIDIDrum("Low", -5, -10);
    assertEquals(1, drumLow.getMidiChannel());
    assertEquals(0, drumLow.getMidiNote());

    drumLow.setMessageType(null);
    assertEquals(MIDIDrum.MidiMessageType.NOTE, drumLow.getMessageType());
  }

  @Test
  void helpTextManagerHandlesNullAndTurkishLocale() {
    assertNotNull(HelpTextManager.getHelp(null));
    Locale prev = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr"));
      String help = HelpTextManager.getHelp("pitch");
      assertTrue(help.contains("transposition"));
    } finally {
      Locale.setDefault(prev);
    }
  }

  @Test
  void stickyTurnFilterHandlesNegativeAndZeroTimestampsCleanly() {
    StickyTurnFilter filter = new StickyTurnFilter(500);
    assertEquals(1, filter.filter(1, -100L));
    assertEquals(0, filter.filter(-1, -50L));
    assertEquals(-1, filter.filter(-1, 500L));

    filter.reset();
    assertEquals(1, filter.filter(1, 0L));
  }

  @Test
  void delugeEncoderKnobPaintsAtTinyDimensionsWithoutException() {
    DelugeEncoderKnob knob = new DelugeEncoderKnob("CUTOFF", Color.ORANGE);
    knob.setSize(4, 4);
    BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g2 = img.createGraphics();
    try {
      assertDoesNotThrow(() -> knob.paint(g2));
    } finally {
      g2.dispose();
    }
  }
}
