package org.deluge.usb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.deluge.cleanup.DelugeRecordingCleaner;
import org.deluge.project.PresetFinder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DelugeUsbSyncStreamParityTest {

  private static byte[] buildPacket(int cmd, byte[] payload) {
    int len = payload != null ? payload.length : 0;
    byte[] packet = new byte[len + 6];
    packet[0] = (byte) 0xDE;
    packet[1] = (byte) 0x4C;
    packet[2] = (byte) cmd;
    packet[3] = (byte) (len & 0xFF);
    packet[4] = (byte) ((len >> 8) & 0xFF);
    if (len > 0) {
      System.arraycopy(payload, 0, packet, 5, len);
    }
    int checksum = cmd ^ (len & 0xFF) ^ ((len >> 8) & 0xFF);
    if (payload != null) {
      for (byte b : payload) {
        checksum ^= (b & 0xFF);
      }
    }
    packet[5 + len] = (byte) checksum;
    return packet;
  }

  private static byte[] buildParamPacket(int paramKind, int paramId, int value) {
    byte[] payload =
        new byte[] {
          (byte) paramKind,
          (byte) (paramId & 0xFF),
          (byte) ((paramId >> 8) & 0xFF),
          (byte) (value & 0xFF),
          (byte) ((value >> 8) & 0xFF),
          (byte) ((value >> 16) & 0xFF),
          (byte) ((value >> 24) & 0xFF)
        };
    return buildPacket(11, payload);
  }

  @Test
  void readPacketsFromStream_resyncsAfterShortGarbageWithoutLosingPacket() throws Exception {
    DelugeUsbSyncService service = new DelugeUsbSyncService(null);
    List<Integer> receivedValues = new ArrayList<>();
    service.addParameterListener((kind, id, val) -> receivedValues.add(val));

    ByteArrayOutputStream stream = new ByteArrayOutputStream();
    // 1 garbage byte before 0xDE 0x4C (offset 1 inside initial 5-byte header read)
    stream.write(new byte[] {0x00});
    stream.write(buildParamPacket(1, 10, 101));
    // 2 garbage bytes before 0xDE 0x4C (offset 2 inside header)
    stream.write(new byte[] {0x11, 0x22});
    stream.write(buildParamPacket(1, 10, 202));
    // 3 garbage bytes before 0xDE 0x4C (offset 3 inside header)
    stream.write(new byte[] {0x33, 0x44, 0x55});
    stream.write(buildParamPacket(1, 10, 303));
    // 4 garbage bytes before 0xDE 0x4C (0xDE lands at header[4], 0x4C is next stream byte)
    stream.write(new byte[] {0x66, 0x77, (byte) 0x88, (byte) 0x99});
    stream.write(buildParamPacket(1, 10, 404));
    // Repeated 0xDE before 0xDE 0x4C (0xDE 0xDE 0x4C glitch)
    stream.write(new byte[] {(byte) 0xDE});
    stream.write(buildParamPacket(1, 10, 505));

    service.readPacketsFromStream(new ByteArrayInputStream(stream.toByteArray()));

    assertEquals(List.of(101, 202, 303, 404, 505), receivedValues);
  }

  @Test
  void readPacketsFromStream_ignoresUndersizedPayloadsSafely() throws Exception {
    DelugeUsbSyncService service = new DelugeUsbSyncService(null);
    List<byte[]> receivedChunks = new ArrayList<>();
    List<String> receivedEntries = new ArrayList<>();
    service.addFileListener(
        new DelugeUsbSyncService.UsbFileListener() {
          @Override
          public void onDirectoryListingReceived(List<DelugeUsbSyncService.FileEntry> entries) {
            for (DelugeUsbSyncService.FileEntry entry : entries) {
              receivedEntries.add((entry.isDirectory ? "d:" : "f:") + entry.name);
            }
          }

          @Override
          public void onFileChunkReceived(int chunkIndex, boolean isEof, byte[] data) {
            receivedChunks.add(data);
          }

          @Override
          public void onError(String error) {}
        });

    ByteArrayOutputStream stream = new ByteArrayOutputStream();
    // cmd=1 (Playhead Sync) with < 9 bytes
    stream.write(buildPacket(1, new byte[] {1, 2, 3, 4}));
    // cmd=3 (File List) with 0 bytes
    stream.write(buildPacket(3, new byte[0]));
    // cmd=5 (File Chunk) with < 5 bytes
    stream.write(buildPacket(5, new byte[] {0, 0, 0}));
    // cmd=5 (File Chunk) claiming chunkSize=10 but only 6 payload bytes total
    stream.write(buildPacket(5, new byte[] {0, 0, 0, 10, 0, 0x7F}));
    // Valid cmd=3 directory listing
    byte[] validDirPayload = "\0fSONG001.XML\0".getBytes(StandardCharsets.UTF_8);
    stream.write(buildPacket(3, validDirPayload));
    // Valid cmd=5 file chunk (status=1 EOF, chunkIndex=0, chunkSize=3, data="WAV")
    stream.write(buildPacket(5, new byte[] {1, 0, 0, 3, 0, 'W', 'A', 'V'}));

    service.readPacketsFromStream(new ByteArrayInputStream(stream.toByteArray()));

    assertEquals(List.of("f:SONG001.XML"), receivedEntries);
    assertEquals(1, receivedChunks.size());
    assertArrayEquals(new byte[] {'W', 'A', 'V'}, receivedChunks.get(0));
  }

  @Test
  void recordingCleaner_preservesAudioClipFilePathAndSubfolders(@TempDir Path tempDir)
      throws Exception {
    String prevCard = System.getProperty("deluge.card");
    try {
      File libraryDir = tempDir.toFile();
      System.setProperty("deluge.card", libraryDir.getAbsolutePath());

      File songsSubDir = new File(new File(libraryDir, "SONGS"), "SETLIST");
      File kitsSubDir = new File(new File(libraryDir, "KITS"), "DRUMS");
      File synthsSubDir = new File(new File(libraryDir, "SYNTHS"), "LEADS");
      File recordDir = new File(new File(libraryDir, "SAMPLES"), "RECORD");
      File recordSubA = new File(recordDir, "SESSION_A");
      File recordSubB = new File(recordDir, "SESSION_B");
      songsSubDir.mkdirs();
      kitsSubDir.mkdirs();
      synthsSubDir.mkdirs();
      recordSubA.mkdirs();
      recordSubB.mkdirs();

      // Song in subfolder references an audioClip via filePath attribute and element
      String songXml =
          "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
              + "<song>\n"
              + "  <sessionClips>\n"
              + "    <audioClip filePath=\"SAMPLES/RECORD/CLIP_ATTR.WAV\" />\n"
              + "    <audioClip><filePath>SAMPLES/RECORD/CLIP_ELEM.WAV</filePath></audioClip>\n"
              + "  </sessionClips>\n"
              + "</song>";
      Files.writeString(new File(songsSubDir, "LIVE_SET.XML").toPath(), songXml);

      // Synth in subfolder references a recording via fileName
      String synthXml =
          "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
              + "<sound><osc1 fileName=\"SAMPLES/RECORD/SYNTH_SUB.WAV\" /></sound>";
      Files.writeString(new File(synthsSubDir, "LEAD1.XML").toPath(), synthXml);

      Files.writeString(new File(recordDir, "CLIP_ATTR.WAV").toPath(), "used attr");
      Files.writeString(new File(recordDir, "CLIP_ELEM.WAV").toPath(), "used elem");
      Files.writeString(new File(recordDir, "SYNTH_SUB.WAV").toPath(), "used sub");
      // Two unused files with identical names in different RECORD subfolders
      Files.writeString(new File(recordSubA, "TAKE001.WAV").toPath(), "take A");
      Files.writeString(new File(recordSubB, "TAKE001.WAV").toPath(), "take B");

      Set<String> extracted =
          DelugeRecordingCleaner.extractReferencedPaths(
              new File(songsSubDir, "LIVE_SET.XML"), new ArrayList<>());
      assertTrue(extracted.contains("SAMPLES/RECORD/CLIP_ATTR.WAV"));
      assertTrue(extracted.contains("SAMPLES/RECORD/CLIP_ELEM.WAV"));

      DelugeRecordingCleaner.ScanResult result = DelugeRecordingCleaner.scanLibrary();
      assertEquals(5, result.allRecordings.size());
      assertEquals(2, result.unusedRecordings.size());

      int moved = DelugeRecordingCleaner.quarantineFiles(result.unusedRecordings);
      assertEquals(2, moved);

      File quarantineDir = new File(new File(libraryDir, "SAMPLES"), "UNUSED RECORDINGS");
      File quarantinedA = new File(new File(quarantineDir, "SESSION_A"), "TAKE001.WAV");
      File quarantinedB = new File(new File(quarantineDir, "SESSION_B"), "TAKE001.WAV");
      assertTrue(quarantinedA.exists());
      assertTrue(quarantinedB.exists());
      assertEquals("take A", Files.readString(quarantinedA.toPath()));
      assertEquals("take B", Files.readString(quarantinedB.toPath()));
      assertFalse(new File(recordSubA, "TAKE001.WAV").exists());
      assertFalse(new File(recordSubB, "TAKE001.WAV").exists());
    } finally {
      if (prevCard != null) {
        System.setProperty("deluge.card", prevCard);
      } else {
        System.clearProperty("deluge.card");
      }
    }
  }

  @Test
  void presetFinder_strcmpspecialMatchesCppNumericAndLeadingZeroOrder(@TempDir Path tempDir)
      throws Exception {
    // Numeric runs compare by value ("2" < "10", "SYNT2" < "SYNT10")
    assertTrue(PresetFinder.strcmpspecial("2_Bass", "10_Lead") < 0);
    assertTrue(PresetFinder.strcmpspecial("SYNT2", "SYNT10") < 0);
    // Equal numeric value breaks ties by leading zeros ('0' < '2' -> "02" < "2")
    assertTrue(PresetFinder.strcmpspecial("02", "2") < 0);
    assertTrue(PresetFinder.strcmpspecial("002", "02") < 0);
    // Case-insensitive ASCII letters
    assertEquals(0, PresetFinder.strcmpspecial("BassLead", "basslead"));
    // '.' ranks before '_', and '_' ranks before alphanumeric chars (C name_compare.cpp:222-229)
    assertTrue(PresetFinder.strcmpspecial("A.1", "A_1") < 0);
    assertTrue(PresetFinder.strcmpspecial("A_1", "AA1") < 0);

    // findFirstPreset picks "2_Warm.json" before "10_Bright.xml"
    File dir = tempDir.toFile();
    Files.writeString(new File(dir, "10_Bright.xml").toPath(), "<sound/>");
    Files.writeString(new File(dir, "2_Warm.json").toPath(), "{}");

    File first = PresetFinder.findFirstPreset(dir);
    assertNotNull(first);
    assertEquals("2_Warm.json", first.getName());
  }
}
