package org.deluge.xml;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;
import org.deluge.model.SynthTrackModel;
import org.deluge.shadow.audio.Dx7Patch;
import org.deluge.ui.Dx7Panel;
import org.junit.jupiter.api.Test;

class Dx7PatchSysexPanelParityTest {

  @Test
  void defaultDx7PatchMatchesCppInitVoiceLayout() {
    assertEquals(155, Dx7Patch.OFF_OP_SWITCH);
    assertEquals(142, Dx7Patch.OFF_LFO_WAVEFORM);
    assertEquals(156, Dx7Patch.VOICE_SIZE);

    Dx7Patch patch = new Dx7Patch();
    assertEquals(156, patch.raw().length);
    assertEquals(0, patch.algorithm());
    assertEquals(0, patch.feedback());
    assertEquals(24, patch.transpose());
    assertEquals(0x3F, patch.opSwitch());
    assertEquals("INIT VOICE", patch.name());

    // C dx7note.cpp:42-48: OP6 (index 5) has output level 99, OP1..5 have output level 0.
    assertEquals(0, patch.operators()[0].outputLevel());
    assertEquals(99, patch.operators()[5].outputLevel());
    assertEquals(1, patch.operators()[5].coarseFreq());
    assertEquals(7, patch.operators()[5].detune());
    assertArrayEquals(new int[] {99, 99, 99, 99}, patch.operators()[5].egRate());
    assertArrayEquals(new int[] {99, 99, 99, 0}, patch.operators()[5].egLevel());
  }

  @Test
  void dx7PatchReadsAllFieldsAndNormalizesName() {
    Dx7Patch patch = new Dx7Patch();
    byte[] raw = patch.raw();
    raw[Dx7Patch.OFF_ALGORITHM] = 19;
    raw[Dx7Patch.OFF_FEEDBACK] = 5;
    raw[Dx7Patch.OFF_TRANSPOSE] = 36;
    raw[Dx7Patch.OFF_OP_SWITCH] = 0x25;

    // Name with special Yamaha ASCII chars: 92 -> 'Y', 126 -> '>', 127 -> '<', trailing spaces.
    byte[] nameBytes = new byte[] {'E', '.', 'P', 'I', 'A', 'N', 'O', 92, 126, ' '};
    System.arraycopy(nameBytes, 0, raw, Dx7Patch.OFF_NAME, 10);

    // Operator 3 (index 2, offset 42)
    int opOff = 2 * 21;
    raw[opOff + 0] = 81;
    raw[opOff + 1] = 72;
    raw[opOff + 2] = 63;
    raw[opOff + 3] = 54;
    raw[opOff + 4] = 98;
    raw[opOff + 5] = 87;
    raw[opOff + 6] = 76;
    raw[opOff + 7] = 12;
    raw[opOff + 14] = 2; // ampModSens
    raw[opOff + 15] = 6; // velSens
    raw[opOff + 16] = 91; // outputLevel
    raw[opOff + 17] = 1; // mode
    raw[opOff + 18] = 14; // coarse
    raw[opOff + 19] = 47; // fine
    raw[opOff + 20] = 11; // detune

    assertEquals(19, patch.algorithm());
    assertEquals(5, patch.feedback());
    assertEquals(36, patch.transpose());
    assertEquals(0x25, patch.opSwitch());
    assertEquals("E.PIANOY>", patch.name());

    Dx7Patch.Operator op3 = patch.operators()[2];
    assertEquals(91, op3.outputLevel());
    assertEquals(1, op3.mode());
    assertEquals(14, op3.coarseFreq());
    assertEquals(47, op3.fineFreq());
    assertEquals(11, op3.detune());
    assertEquals(6, op3.velSens());
    assertEquals(2, op3.ampModSens());
    assertArrayEquals(new int[] {81, 72, 63, 54}, op3.egRate());
    assertArrayEquals(new int[] {98, 87, 76, 12}, op3.egLevel());
  }

  @Test
  void singleVoiceStandardYamahaVcedRoundTrips() {
    Dx7Patch original = buildDistinctTestPatch();
    byte[] syx = Dx7SyxParser.patchToSyx(original);
    assertEquals(163, syx.length);
    assertEquals((byte) 0xF0, syx[0]);
    assertEquals((byte) 0x43, syx[1]);
    assertEquals((byte) 0x00, syx[3]);
    assertEquals((byte) 0x01, syx[4]);
    assertEquals((byte) 0x1B, syx[5]);
    assertEquals((byte) 0xF7, syx[162]);

    List<Dx7Patch> parsed = Dx7SyxParser.parseSyx(syx, "test-single.syx");
    assertEquals(1, parsed.size());
    Dx7Patch restored = parsed.get(0);

    for (int i = 0; i < 155; i++) {
      assertEquals(original.raw()[i] & 0x7F, restored.raw()[i] & 0x7F, "Mismatch at byte " + i);
    }
    assertEquals(0x3F, restored.opSwitch());
    assertEquals("BRASS 1", restored.name());
  }

  @Test
  void packed32VoiceYamahaCartridgeRoundTripsAllFields() {
    Dx7Patch original = buildDistinctTestPatch();
    byte[] syx = Dx7SyxParser.patchToSyxBulk(original);
    assertEquals(4104, syx.length);
    assertEquals((byte) 0xF0, syx[0]);
    assertEquals((byte) 0x43, syx[1]);
    assertEquals((byte) 0x09, syx[3]);
    assertEquals((byte) 0x20, syx[4]);
    assertEquals((byte) 0x00, syx[5]);
    assertEquals((byte) 0xF7, syx[4103]);

    List<Dx7Patch> parsed = Dx7SyxParser.parseSyx(syx, "test-cartridge.syx");
    assertEquals(32, parsed.size());
    Dx7Patch restored = parsed.get(0);

    for (int i = 0; i < 155; i++) {
      assertEquals(original.raw()[i] & 0x7F, restored.raw()[i] & 0x7F, "Mismatch at byte " + i);
    }
    assertEquals(0x3F, restored.opSwitch());
    assertEquals(original.algorithm(), restored.algorithm());
    assertEquals(original.feedback(), restored.feedback());
    assertEquals(original.transpose(), restored.transpose());
    assertEquals("BRASS 1", restored.name());
    assertEquals("INIT VOICE", parsed.get(31).name());
  }

  @Test
  void legacy5ByteHeaderFormatStillParses() {
    Dx7Patch original = buildDistinctTestPatch();
    byte[] raw = original.raw();
    int xorChecksum = Dx7SyxParser.computeChecksum(raw, 0, 156);

    byte[] legacySingle = new byte[5 + 156 + 1 + 1];
    legacySingle[0] = (byte) 0xF0;
    legacySingle[1] = (byte) 0x43;
    legacySingle[2] = 0x00;
    legacySingle[3] = 0x00;
    legacySingle[4] = 0x00;
    System.arraycopy(raw, 0, legacySingle, 5, 156);
    legacySingle[5 + 156] = (byte) xorChecksum;
    legacySingle[6 + 156] = (byte) 0xF7;

    List<Dx7Patch> parsed = Dx7SyxParser.parseSyx(legacySingle, "legacy.syx");
    assertEquals(1, parsed.size());
    assertArrayEquals(raw, parsed.get(0).raw());
  }

  @Test
  void corruptedChecksumThrowsIllegalArgumentException() {
    byte[] syx = Dx7SyxParser.patchToSyx(buildDistinctTestPatch());
    syx[20] ^= 0x05;
    assertThrows(IllegalArgumentException.class, () -> Dx7SyxParser.parseSyx(syx, "corrupt.syx"));
  }

  @Test
  void activateButtonInitializesValidPlayableDx7Patch() throws Exception {
    SynthTrackModel model = new SynthTrackModel("DX7 Test");
    model.setSynthMode(0);

    SwingUtilities.invokeAndWait(
        () -> {
          Dx7Panel panel = new Dx7Panel(model, null, 0, null, () -> {});
          JButton activateBtn = findFirst(panel, JButton.class);
          assertNotNull(activateBtn);
          activateBtn.doClick();
        });

    assertEquals(1, model.getSynthMode());
    assertEquals(5, model.getSynthAlgorithm());
    Dx7Patch patch = Dx7Patch.fromHex(model.getDx7Patch());
    assertEquals(5, patch.algorithm());
    assertEquals(6, patch.feedback());
    assertEquals(0x3F, patch.opSwitch());
    assertEquals(24, patch.transpose());
    // OP1 EG rate 1 (byte 0) must not be clobbered by opSwitch (0x3F = 63), and OP1 outputLevel
    // (byte 16) must be 99 while velSens (byte 15) stays 0.
    assertEquals(99, patch.raw()[0] & 0xFF);
    assertEquals(0, patch.operators()[0].velSens());
    assertEquals(99, patch.operators()[0].outputLevel());
    assertEquals(75, patch.operators()[1].outputLevel());
  }

  @Test
  void syncActiveOperatorDetailsSyncsAlgorithmAndTransposeSliders() throws Exception {
    SynthTrackModel model = new SynthTrackModel("DX7 Test");
    model.setSynthMode(1);
    Dx7Patch patch = buildDistinctTestPatch();
    patch.raw()[Dx7Patch.OFF_ALGORITHM] = 17;
    patch.raw()[Dx7Patch.OFF_TRANSPOSE] = 36; // +12 semitones from 24
    patch.raw()[Dx7Patch.OFF_OP_SWITCH] = 0x3E; // OP1 disabled, OP2..6 enabled
    model.setDx7Patch(Dx7Patch.bytesToHex(patch.raw()));

    SwingUtilities.invokeAndWait(
        () -> {
          Dx7Panel panel = new Dx7Panel(model, null, 0, null, null);
          List<JSlider> sliders = findAll(panel, JSlider.class);
          // Sidebar sliders: [0]=algo (0..31), [1]=feedback (0..7), [2]=transpose (0..48)
          JSlider algoSlider =
              sliders.stream()
                  .filter(s -> s.getMinimum() == 0 && s.getMaximum() == 31 && s.getValue() == 17)
                  .findFirst()
                  .orElse(null);
          assertNotNull(algoSlider, "algoSlider should be synced to patch algorithm 17");

          JSlider transposeSlider =
              sliders.stream()
                  .filter(s -> s.getMinimum() == 0 && s.getMaximum() == 48)
                  .findFirst()
                  .orElse(null);
          assertNotNull(transposeSlider, "transposeSlider should have DX7 range 0..48");
          assertEquals(36, transposeSlider.getValue());

          JCheckBox activeCheck = findFirst(panel, JCheckBox.class);
          assertNotNull(activeCheck);
          assertFalse(activeCheck.isSelected(), "OP1 should be disabled when opSwitch is 0x3E");

          // Toggling OP1 on should update byte 155 without throwing when bridge is null.
          activeCheck.doClick();
          assertTrue(activeCheck.isSelected());
          Dx7Patch updated = Dx7Patch.fromHex(model.getDx7Patch());
          assertEquals(0x3F, updated.opSwitch());
        });
  }

  private static Dx7Patch buildDistinctTestPatch() {
    Dx7Patch patch = new Dx7Patch();
    byte[] raw = patch.raw();
    for (int op = 0; op < 6; op++) {
      int up = op * 21;
      raw[up + 0] = (byte) (90 - op);
      raw[up + 1] = (byte) (80 - op);
      raw[up + 2] = (byte) (70 - op);
      raw[up + 3] = (byte) (60 - op);
      raw[up + 4] = (byte) (99 - op);
      raw[up + 5] = (byte) (88 - op);
      raw[up + 6] = (byte) (77 - op);
      raw[up + 7] = (byte) (op * 5);
      raw[up + 8] = (byte) (39 + op); // break pt
      raw[up + 9] = (byte) (10 + op); // left depth
      raw[up + 10] = (byte) (20 + op); // right depth (byte 10!)
      raw[up + 11] = (byte) (op & 0x03); // left curve
      raw[up + 12] = (byte) ((op + 1) & 0x03); // right curve
      raw[up + 13] = (byte) (op & 0x07); // rate scale
      raw[up + 14] = (byte) (op & 0x03); // amp mod sens
      raw[up + 15] = (byte) ((op + 2) & 0x07); // vel sens
      raw[up + 16] = (byte) (99 - op * 3); // output level
      raw[up + 17] = (byte) (op & 0x01); // mode
      raw[up + 18] = (byte) (1 + op * 2); // coarse
      raw[up + 19] = (byte) (op * 11); // fine
      raw[up + 20] = (byte) (7 + op); // detune
    }
    for (int i = 0; i < 8; i++) {
      raw[126 + i] = (byte) (50 + i);
    }
    raw[Dx7Patch.OFF_ALGORITHM] = 21;
    raw[Dx7Patch.OFF_FEEDBACK] = 6;
    raw[Dx7Patch.OFF_OSC_SYNC] = 1;
    raw[Dx7Patch.OFF_LFO_SPEED] = 42;
    raw[Dx7Patch.OFF_LFO_DELAY] = 15;
    raw[Dx7Patch.OFF_PMOD_DEPTH] = 23;
    raw[Dx7Patch.OFF_AMOD_DEPTH] = 19;
    raw[Dx7Patch.OFF_LFO_SYNC] = 1;
    raw[Dx7Patch.OFF_LFO_WAVEFORM] = 4; // sine
    raw[Dx7Patch.OFF_PMOD_SENS] = 5;
    raw[Dx7Patch.OFF_TRANSPOSE] = 24;
    byte[] name = "BRASS 1   ".getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(name, 0, raw, Dx7Patch.OFF_NAME, 10);
    raw[Dx7Patch.OFF_OP_SWITCH] = 0x3F;
    return patch;
  }

  private static <T extends Component> T findFirst(Container root, Class<T> type) {
    List<T> all = findAll(root, type);
    return all.isEmpty() ? null : all.get(0);
  }

  private static <T extends Component> List<T> findAll(Container root, Class<T> type) {
    List<T> found = new ArrayList<>();
    collect(root, type, found);
    return found;
  }

  private static <T extends Component> void collect(
      Container parent, Class<T> type, List<T> found) {
    for (Component child : parent.getComponents()) {
      if (type.isInstance(child)) {
        found.add(type.cast(child));
      }
      if (child instanceof Container container) {
        collect(container, type, found);
      }
    }
  }
}
