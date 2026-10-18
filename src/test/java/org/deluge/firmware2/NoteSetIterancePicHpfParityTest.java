package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import org.deluge.firmware2.MusicalKey.NoteSet;
import org.deluge.hid.pic.PIC;
import org.deluge.hid.pic.PicTransport;
import org.deluge.model.FilterMode;
import org.deluge.model.Iterance;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.deluge.ui.GridVuManager;
import org.deluge.ui.HpfPanel;
import org.deluge.ui.VUMeterPanel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Guards C++ parity fixes in {@link MusicalKey.NoteSet}, {@link Iterance}, {@link PIC}, {@link
 * HpfPanel}, and {@link GridVuManager}.
 */
public class NoteSetIterancePicHpfParityTest {

  private final PicTransport prevPicTransport = PIC.getTransport();

  @AfterEach
  void restorePicTransport() {
    PIC.setTransport(prevPicTransport);
  }

  // ── MusicalKey.NoteSet (C note_set.h / note_set.cpp) ──────────────────────

  @Test
  void noteSetMasksTo12BitsAndRejectsOutOfRangeNotes() {
    NoteSet set = new NoteSet();
    assertTrue(set.isEmpty());
    assertEquals(-1, set.highest());

    set.add(0);
    set.add(4);
    set.add(7);
    set.add(12); // C note_set.h:31 masks with 0xfff, so 12 is ignored
    set.add(-1); // negative note must not wrap modulo 32 in Java
    set.add(32); // >= 32 must not wrap modulo 32 in Java

    assertEquals(3, set.count());
    assertEquals(0x091, set.toBits());
    assertTrue(set.has(0));
    assertTrue(set.has(4));
    assertTrue(set.has(7));
    assertFalse(set.has(12));
    assertFalse(set.has(-1));
    assertFalse(set.has(32));
    assertEquals(7, set.highest());

    set.remove(4);
    assertFalse(set.has(4));
    assertEquals(2, set.count());
    assertEquals(0x081, set.toBits());
  }

  @Test
  void noteSetAddUntrustedEnforcesStrictlyIncreasingNotesUnder12() {
    NoteSet set = new NoteSet();
    set.addUntrusted(2);
    set.addUntrusted(2); // bumped to highest() + 1 == 3
    set.addUntrusted(1); // bumped to highest() + 1 == 4
    set.addUntrusted(25); // clamped to 11

    assertEquals(NoteSet.of(2, 3, 4, 11), set);
    assertEquals(5, set.scaleSize()); // includes root (0)
  }

  @Test
  void noteSetDegreesBelowFillClearUnionAndSubsetMatchCpp() {
    NoteSet triad = NoteSet.of(0, 4, 7);
    assertEquals(0, triad.degreesBelow(0));
    assertEquals(1, triad.degreesBelow(3)); // absent note gets degree of note above it
    assertEquals(-1, triad.degreeOf(3));
    assertEquals(1, triad.degreeOf(4));
    assertEquals(2, triad.degreeOf(7));

    NoteSet seventh = NoteSet.of(0, 4, 7, 11);
    assertTrue(triad.isSubsetOf(seventh));
    assertFalse(seventh.isSubsetOf(triad));
    assertEquals(11, seventh.highestNotIn(triad));
    assertEquals(-1, triad.highestNotIn(seventh));
    assertEquals(seventh, triad.union(NoteSet.of(11)));

    NoteSet full = new NoteSet();
    full.fill();
    assertEquals(12, full.count());
    assertEquals(0xfff, full.toBits());
    full.clear();
    assertTrue(full.isEmpty());
  }

  @Test
  void noteSetMajornessModulateByOffsetAndImpliedScaleMatchCpp() {
    NoteSet cMajorTriad = NoteSet.of(0, 4, 7);
    NoteSet cMinorTriad = NoteSet.of(0, 3, 7);
    assertTrue(cMajorTriad.majorness() > 0);
    assertTrue(cMinorTriad.majorness() < 0);

    // Modulating {0, 4, 7} up by 7 semitones wraps 7+7=14 -> 2, giving {0, 2, 7, 11} -> {2, 7, 11}
    NoteSet modulated = cMajorTriad.modulateByOffset(7);
    assertEquals(NoteSet.of(2, 7, 11), modulated);
    assertEquals(cMajorTriad, cMajorTriad.modulateByOffset(12));

    // C note_set.cpp:151-200: major triad implies major scale {0, 2, 4, 5, 7, 9, 11};
    // minor triad implies natural minor {0, 2, 3, 5, 7, 8, 10}.
    assertEquals(NoteSet.of(0, 2, 4, 5, 7, 9, 11), cMajorTriad.toImpliedScale());
    assertEquals(NoteSet.of(0, 2, 3, 5, 7, 8, 10), cMinorTriad.toImpliedScale());
  }

  // ── Iterance.toPresetIndex (C iterance.cpp:76-90) ─────────────────────────

  @Test
  void iteranceToPresetIndexRoundtripsOffPresetsAndCustom() {
    assertEquals(0, Iterance.OFF.toPresetIndex());

    for (int i = 1; i <= Iterance.NUM_ITERANCE_PRESETS; i++) {
      Iterance preset = Iterance.fromPresetIndex(i);
      assertEquals(i, preset.toPresetIndex(), "Preset index " + i + " failed roundtrip");
    }

    assertEquals(Iterance.CUSTOM_ITERANCE_PRESET, Iterance.CUSTOM_DEFAULT.toPresetIndex());
    Iterance customSteps = new Iterance((byte) 4, (byte) 0b0101);
    assertEquals(Iterance.CUSTOM_ITERANCE_PRESET, customSteps.toPresetIndex());
    assertNotEquals(0, customSteps.toPresetIndex());
  }

  // ── PIC (C definitions_cxx.hpp:447, pic.h:129-132, 234-268) ───────────────

  @Test
  void picSetGoldKnobIndicatorSendsAtMostFourLeds() {
    List<Integer> sent = new ArrayList<>();
    PIC.setTransport(
        new PicTransport() {
          @Override
          public void send(int b) {
            sent.add(b);
          }

          @Override
          public int read() {
            return -1;
          }

          @Override
          public void flush() {}
        });

    byte[] oversized = new byte[24];
    for (int i = 0; i < oversized.length; i++) {
      oversized[i] = (byte) (i + 10);
    }
    PIC.setGoldKnobIndicator(false, oversized);

    // 1 command byte (SET_GOLD_KNOB_0_INDICATORS = 20) + 4 LED bytes = 5 bytes total.
    assertEquals(List.of(20, 10, 11, 12, 13), sent);
  }

  @Test
  void picReadSkipsResponseNoneAndPreservesHandlerResultOnTimeout() {
    Queue<Integer> rx = new ArrayDeque<>(List.of(0, 0, 175));
    PIC.setTransport(
        new PicTransport() {
          @Override
          public void send(int b) {}

          @Override
          public int read() {
            return rx.isEmpty() ? -1 : rx.poll();
          }

          @Override
          public void flush() {}
        });

    assertEquals(PIC.Response.RESET_SETTINGS, PIC.read(5_000));

    // Handler returning 0 (keep scanning) on a real byte must return 0 when timeout expires,
    // while Response.NONE (0) must never invoke the handler.
    Queue<Integer> rx2 = new ArrayDeque<>(List.of(0, 129, 0));
    List<Integer> seenByHandler = new ArrayList<>();
    PIC.setTransport(
        new PicTransport() {
          @Override
          public void send(int b) {}

          @Override
          public int read() {
            return rx2.isEmpty() ? -1 : rx2.poll();
          }

          @Override
          public void flush() {}
        });

    int res =
        PIC.read(
            2_000,
            val -> {
              seenByHandler.add(val);
              return 0;
            });
    assertEquals(0, res);
    assertEquals(List.of(129), seenByHandler);

    // When only NONE (0) or empty (-1) arrives, timeout returns 1 (failure).
    Queue<Integer> rx3 = new ArrayDeque<>(List.of(0, 0));
    PIC.setTransport(
        new PicTransport() {
          @Override
          public void send(int b) {}

          @Override
          public int read() {
            return rx3.isEmpty() ? -1 : rx3.poll();
          }

          @Override
          public void flush() {}
        });
    assertEquals(1, PIC.read(1_000, val -> 0));
  }

  // ── HpfPanel (C gui/menu_item/filter/mode.h:65-72) ────────────────────────

  @Test
  void hpfPanelComboOnlyExposesFourCppHpfModesAndMutatesModel() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          ProjectModel project = new ProjectModel();
          SynthTrackModel synth = new SynthTrackModel("Synth");
          synth.setHpfMode(FilterMode.SVF); // legacy alias for SVF_BAND
          project.addTrack(synth);

          HpfPanel panel = new HpfPanel(synth, null, 0, project);
          JComboBox<?> combo = findComboBox(panel);

          assertEquals(4, combo.getItemCount());
          assertEquals("SVF_BAND", combo.getItemAt(0));
          assertEquals("SVF_NOTCH", combo.getItemAt(1));
          assertEquals("HPLADDER", combo.getItemAt(2));
          assertEquals("OFF", combo.getItemAt(3));
          assertEquals(0, combo.getSelectedIndex());

          combo.setSelectedIndex(2);
          assertEquals(FilterMode.LADDER_12, synth.getHpfMode());

          combo.setSelectedIndex(3);
          assertEquals(FilterMode.OFF, synth.getHpfMode());
        });
  }

  // ── GridVuManager ─────────────────────────────────────────────────────────

  @Test
  void gridVuManagerDecaysSharedRowOnlyOncePerTick() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          GridVuManager manager = new GridVuManager();
          final double[] v = {-1.0};
          final double[] t = {-1.0};
          manager.registerVoiceVu(
              3,
              new VUMeterPanel() {
                @Override
                public void setLvl(double l) {
                  v[0] = l;
                }
              });
          manager.registerTrackVu(
              3,
              new VUMeterPanel() {
                @Override
                public void setLvl(double l) {
                  t[0] = l;
                }
              });
          manager.spikeVu(3);
          // Fire the internal timer listener once via reflection to avoid timing flakiness.
          try {
            var f = GridVuManager.class.getDeclaredField("globalVuTimer");
            f.setAccessible(true);
            javax.swing.Timer timer = (javax.swing.Timer) f.get(manager);
            timer.getActionListeners()[0].actionPerformed(null);
          } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
          } finally {
            manager.shutdown();
          }
          assertEquals(0.80, v[0], 1e-9);
          assertEquals(0.80, t[0], 1e-9);
        });
  }

  private static JComboBox<?> findComboBox(Container root) {
    JComboBox<?> found = findComboBoxOrNull(root);
    if (found == null) {
      throw new AssertionError("No JComboBox found in panel");
    }
    return found;
  }

  private static JComboBox<?> findComboBoxOrNull(Container root) {
    for (Component c : root.getComponents()) {
      if (c instanceof JComboBox<?> cb) {
        return cb;
      }
      if (c instanceof Container child) {
        JComboBox<?> found = findComboBoxOrNull(child);
        if (found != null) return found;
      }
    }
    return null;
  }
}
