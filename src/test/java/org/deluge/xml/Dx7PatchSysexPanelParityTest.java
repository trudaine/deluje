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

    // C dx7note.cpp:42-48, param.cpp:288 — slot 5 is OP1 (output level 99), slots 0..4 are OP6..OP2
    // (output level 0). operators()[0..5] exposes OP1..OP6 in hardware order.
    assertEquals(99, patch.operators()[0].outputLevel());
    assertEquals(0, patch.operators()[5].outputLevel());
    assertEquals(1, patch.operators()[0].coarseFreq());
    assertEquals(7, patch.operators()[0].detune());
    assertArrayEquals(new int[] {99, 99, 99, 99}, patch.operators()[0].egRate());
    assertArrayEquals(new int[] {99, 99, 99, 0}, patch.operators()[0].egLevel());
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

    // OP3 (operators()[2]) lives at unpacked slot 3 (offset 3 * 21 = 63; C param.cpp:288).
    int opOff = Dx7Patch.slotForOperator(2) * 21;
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
    // OP6 EG rate 1 (byte 0) must not be clobbered by opSwitch (0x3F = 63), and OP1 (slot 5)
    // outputLevel (byte 105 + 16 = 121) must be 99 while velSens stays 0.
    assertEquals(99, patch.raw()[0] & 0xFF);
    assertEquals(0, patch.operators()[0].velSens());
    assertEquals(99, patch.operators()[0].outputLevel());
    assertEquals(99, patch.raw()[5 * 21 + 16] & 0xFF);
    assertEquals(75, patch.operators()[1].outputLevel());
  }

  @Test
  void syncActiveOperatorDetailsSyncsAlgorithmAndTransposeSliders() throws Exception {
    SynthTrackModel model = new SynthTrackModel("DX7 Test");
    model.setSynthMode(1);
    Dx7Patch patch = buildDistinctTestPatch();
    patch.raw()[Dx7Patch.OFF_ALGORITHM] = 17;
    patch.raw()[Dx7Patch.OFF_TRANSPOSE] = 36; // +12 semitones from 24
    // OP1 is slot 5 (bit 5 = 0x20), so 0x1F disables OP1 and enables OP2..6 (slots 4..0).
    patch.raw()[Dx7Patch.OFF_OP_SWITCH] = 0x1F;
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
          assertFalse(activeCheck.isSelected(), "OP1 should be disabled when opSwitch is 0x1F");

          // Toggling OP1 on should set bit 5 (0x20) in byte 155 without throwing when bridge is
          // null.
          activeCheck.doClick();
          assertTrue(activeCheck.isSelected());
          Dx7Patch updated = Dx7Patch.fromHex(model.getDx7Patch());
          assertEquals(0x3F, updated.opSwitch());
        });
  }

  @Test
  void unpackProgramMatchesCppQuirksByteForByte() {
    // Hand-built 4104-byte cartridge exercising every C unpackProgram quirk
    // (DX7Cartridge.cpp:155-214):
    // - op bytes 0..9 use float normparm(..., 99), while byte 10 (right depth) is untouched (stays
    //   init_voice = 0).
    // - output level (bulk_op[14]), fine freq (bulk_op[16]), detune (bits 3..6 of bulk_op[12]),
    //   LFO 137..140, and transpose (bulk[117]) use clearTopBit only (no normparm).
    // - byte 116 unpacks LSB-first as <1, 4, 2> (LKS=bit 0, LFW=bits 1..4, LPMS=bits 5..6).
    byte[] syx = new byte[4104];
    syx[0] = (byte) 0xF0;
    syx[1] = (byte) 0x43;
    syx[2] = 0x00;
    syx[3] = 0x09;
    syx[4] = 0x20;
    syx[5] = 0x00;

    int v0 = 6;
    for (int op = 0; op < 6; op++) {
      int pp = v0 + op * 17;
      syx[pp + 0] = (byte) 100; // > 99 -> normparm(100, 99) = (int)(100f/255f*99f) = 38
      for (int i = 1; i < 10; i++) {
        syx[pp + i] = (byte) (10 + op + i);
      }
      syx[pp + 10] = (byte) 77; // right depth in packed slot, ignored by C loop (i < 10)
      syx[pp + 11] = (byte) 0x0E; // left_curve=2, right_curve=3
      syx[pp + 12] = (byte) ((15 << 3) | 5); // detune=15 (>14, kept raw), rs=5
      syx[pp + 13] = (byte) ((6 << 2) | 3); // kvs=6, ams=3
      syx[pp + 14] = (byte) 120; // output level > 99, kept raw
      syx[pp + 15] = (byte) ((29 << 1) | 1); // coarse=29, mode=1
      syx[pp + 16] = (byte) 115; // fine freq > 99, kept raw
    }
    syx[v0 + 102] = (byte) 110; // pitch EG R1 > 99 -> normparm(110, 99) = (int)(110f/255f*99f) = 42
    for (int i = 1; i < 8; i++) {
      syx[v0 + 102 + i] = (byte) (50 + i);
    }
    syx[v0 + 110] = (byte) 0x3D; // clearTopNBits(..., 3) -> 29
    syx[v0 + 111] = (byte) 0x0F; // fb=7, oks=1
    syx[v0 + 112] = (byte) 121; // lfs > 99, kept raw
    syx[v0 + 113] = (byte) 122; // lfd > 99, kept raw
    syx[v0 + 114] = (byte) 123; // lpmd > 99, kept raw
    syx[v0 + 115] = (byte) 124; // lamd > 99, kept raw
    // Yamaha spec byte 116 with LPMS=5 (bits 4..6 = 0b101), LFW=4 (bits 1..3 = 0b100), LKS=1
    // -> 0x59 (0b1011001). C unpackBits<1, 4, 2> reads LKS=1, LFW=12 (0b1100), LPMS=2 (0b10).
    syx[v0 + 116] = (byte) 0x59;
    syx[v0 + 117] = (byte) 125; // transpose > 48, kept raw
    byte[] name = "GOLDEN C  ".getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(name, 0, syx, v0 + 118, 10);

    syx[4102] = (byte) Dx7SyxParser.sysexChecksum(syx, 6, 4096);
    syx[4103] = (byte) 0xF7;

    List<Dx7Patch> parsed = Dx7SyxParser.parseSyx(syx, "golden-c.syx");
    byte[] got = parsed.get(0).raw();

    byte[] expected = Dx7Patch.createInitVoiceBytes();
    for (int op = 0; op < 6; op++) {
      int up = op * 21;
      expected[up + 0] = 38;
      for (int i = 1; i < 10; i++) {
        expected[up + i] = (byte) (10 + op + i);
      }
      // expected[up + 10] stays 0 from init_voice (C loop stops at i = 9).
      expected[up + 11] = 2;
      expected[up + 12] = 3;
      expected[up + 13] = 5;
      expected[up + 14] = 3;
      expected[up + 15] = 6;
      expected[up + 16] = 120;
      expected[up + 17] = 1;
      expected[up + 18] = 29;
      expected[up + 19] = 115;
      expected[up + 20] = 15;
    }
    expected[126] = 42;
    for (int i = 1; i < 8; i++) {
      expected[126 + i] = (byte) (50 + i);
    }
    expected[134] = 29;
    expected[135] = 7;
    expected[136] = 1;
    expected[137] = 121;
    expected[138] = 122;
    expected[139] = 123;
    expected[140] = 124;
    expected[141] = 1;
    expected[142] = 12;
    expected[143] = 2;
    expected[144] = 125;
    System.arraycopy(name, 0, expected, 145, 10);
    expected[155] = 0x3F;

    assertArrayEquals(expected, got);
  }

  @Test
  void packProgramZeroesOutputLevelForSwitchedOffOperators() {
    // C DX7Cartridge.cpp:71-76 — packProgram writes bulk[pp + 14] = 0 when opSwitch[op] == '0'.
    // Slot 0 (OP6) and slot 5 (OP1) disabled -> bits 0 and 5 cleared (0x1E).
    Dx7Patch patch = buildDistinctTestPatch();
    patch.raw()[Dx7Patch.OFF_OP_SWITCH] = 0x1E;
    byte[] syx = Dx7SyxParser.patchToSyxBulk(patch);
    Dx7Patch unpacked = Dx7SyxParser.parseSyx(syx, "opswitch-pack.syx").get(0);

    // Slot 0 (OP6 = operators()[5]) and slot 5 (OP1 = operators()[0]) packed with output level 0.
    assertEquals(0, unpacked.raw()[0 * 21 + 16] & 0xFF);
    assertEquals(0, unpacked.raw()[5 * 21 + 16] & 0xFF);
    assertEquals(0, unpacked.operators()[5].outputLevel());
    assertEquals(0, unpacked.operators()[0].outputLevel());
    // Slots 1..4 kept their non-zero output levels.
    for (int slot = 1; slot <= 4; slot++) {
      assertEquals(99 - slot * 3, unpacked.raw()[slot * 21 + 16] & 0xFF);
    }
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
      raw[up + 10] = 0; // right depth (left at init_voice=0 by C unpackProgram loop i < 10)
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
    raw[Dx7Patch.OFF_PMOD_SENS] = 0; // 0 so C pack <1,3,3> and unpack <1,4,2> round-trip cleanly
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
