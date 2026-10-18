package org.deluge.midi;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * C++ firmware parity tests for {@link DelugeSysExManager}, {@link DelugeFileSyncService}, and
 * {@link DelugeMidiPacker}.
 */
class SysExSevenSegFileSyncParityTest {

  private static byte[] buildSevenSegSysEx(byte[] rawSegments) {
    byte[] packed = DelugeMidiPacker.pack8to7(rawSegments);
    byte[] msg = new byte[9 + packed.length + 1];
    msg[0] = (byte) 0xF0;
    msg[1] = 0x00;
    msg[2] = 0x21;
    msg[3] = 0x7B;
    msg[4] = 0x01;
    msg[5] = 0x02; // CMD_HID
    msg[6] = 0x41; // 7-Segment subtype (hid_sysex.cpp:147)
    msg[7] = 0x00;
    msg[8] = 0x00;
    System.arraycopy(packed, 0, msg, 9, packed.length);
    msg[msg.length - 1] = (byte) 0xF7;
    return msg;
  }

  @Test
  void sevenSegmentDecodesDigitsLettersDotsAndSymbolsPerCFirmware() {
    DelugeSysExManager manager = new DelugeSysExManager();
    AtomicReference<String> decoded = new AtomicReference<>();
    manager.setDisplayListener(
        new DelugeSysExManager.DisplayListener() {
          @Override
          public void onOledFrame(byte[] frameBuffer) {}

          @Override
          public void onSevenSegment(String text) {
            decoded.set(text);
          }
        });

    // C seven_segment.cpp:61 numberSegments[10]
    byte[] digits = {0x7E, 0x30, 0x6D, 0x79, 0x33, 0x5B, 0x5F, 0x70, 0x7F, 0x7B};
    assertTrue(manager.handleIncomingSysEx(buildSevenSegSysEx(digits)));
    assertEquals("0123456789", decoded.get());

    // C seven_segment.cpp:63-123 ("Mono" = M, o, n, o)
    byte[] mono = {0x55, 0x1D, 0x15, 0x1D};
    assertTrue(manager.handleIncomingSysEx(buildSevenSegSysEx(mono)));
    assertEquals("Mono", decoded.get());

    // C seven_segment.cpp:348, 425 (bit 7 = 0x80 is decimal dot on digit)
    byte[] withDot = {0x30, (byte) (0x6D | 0x80), 0x5B, 0x7E};
    assertTrue(manager.handleIncomingSysEx(buildSevenSegSysEx(withDot)));
    assertEquals("12.50", decoded.get());

    // Internal space preserved, outer blanks trimmed, plus symbols '-', '_', '#', '!'
    byte[] internalSpace = {0x77, 0x00, 0x30, 0x00};
    assertTrue(manager.handleIncomingSysEx(buildSevenSegSysEx(internalSpace)));
    assertEquals("A 1", decoded.get());

    byte[] symbols = {0x01, 0x08, (byte) 0xA7, (byte) 0xA0};
    assertTrue(manager.handleIncomingSysEx(buildSevenSegSysEx(symbols)));
    assertEquals("-_#!", decoded.get());
  }

  @Test
  void oledDeltaOutOfRangeStartWordAndTruncatedHeadersDoNotThrow() {
    DelugeSysExManager manager = new DelugeSysExManager();
    AtomicReference<byte[]> receivedFrame = new AtomicReference<>();
    manager.setDisplayListener(
        new DelugeSysExManager.DisplayListener() {
          @Override
          public void onOledFrame(byte[] frameBuffer) {
            receivedFrame.set(frameBuffer);
          }

          @Override
          public void onSevenSegment(String text) {}
        });

    // Truncated OLED header (length 8 < 9)
    byte[] truncatedHeader = {(byte) 0xF0, 0x00, 0x21, 0x7B, 0x01, 0x02, 0x40, (byte) 0xF7};
    assertTrue(manager.handleIncomingSysEx(truncatedHeader));
    assertNull(receivedFrame.get());

    // Truncated delta header (length 10 < 11)
    byte[] truncatedDelta = {
      (byte) 0xF0, 0x00, 0x21, 0x7B, 0x01, 0x02, 0x40, 0x02, 0x00, (byte) 0xF7
    };
    assertTrue(manager.handleIncomingSysEx(truncatedDelta));
    assertNull(receivedFrame.get());

    // Out-of-bounds startWord = 120 (startOffset = 960 >= 768)
    byte[] packedDelta = DelugeMidiPacker.pack8to7(new byte[8]);
    byte[] oobDelta = new byte[10 + packedDelta.length + 1];
    oobDelta[0] = (byte) 0xF0;
    oobDelta[1] = 0x00;
    oobDelta[2] = 0x21;
    oobDelta[3] = 0x7B;
    oobDelta[4] = 0x01;
    oobDelta[5] = 0x02;
    oobDelta[6] = 0x40;
    oobDelta[7] = 0x02;
    oobDelta[8] = 120; // startWord
    oobDelta[9] = 1; // lenWords
    System.arraycopy(packedDelta, 0, oobDelta, 10, packedDelta.length);
    oobDelta[oobDelta.length - 1] = (byte) 0xF7;
    assertTrue(manager.handleIncomingSysEx(oobDelta));
    assertNull(receivedFrame.get());
  }

  @Test
  void uploadFileBlockingSendsAddrPerChunkAndReadsSizeAttribute() throws Exception {
    byte[] content = new byte[1200];
    for (int i = 0; i < content.length; i++) {
      content[i] = (byte) ((i * 37) & 0xFF);
    }

    List<Integer> seenAddrs = new ArrayList<>();
    List<Integer> seenSizes = new ArrayList<>();
    ByteArrayOutputStream reassembled = new ByteArrayOutputStream();

    DelugeSysExManager mockManager =
        new DelugeSysExManager() {
          @Override
          public void sendRequest(String jsonPayload, SysExCallback callback) {
            if (jsonPayload.contains("\"open\"")) {
              callback.onResponse("{\"^open\": {\"fid\": 7, \"size\": 0, \"err\": 0}}", null);
            } else if (jsonPayload.contains("\"close\"")) {
              callback.onResponse("{\"^close\": {\"fid\": 7, \"err\": 0}}", null);
            } else if (jsonPayload.contains("\"utime\"")) {
              callback.onResponse("{\"^utime\": {\"err\": 0}}", null);
            }
          }

          @Override
          public void sendRequest(
              String jsonPayload, byte[] binaryPayload, SysExCallback callback) {
            assertTrue(jsonPayload.contains("\"write\""));
            int addr = extractInt(jsonPayload, "addr");
            int size = extractInt(jsonPayload, "size");
            seenAddrs.add(addr);
            seenSizes.add(size);

            byte[] unpacked = DelugeMidiPacker.unpack7to8(binaryPayload);
            assertEquals(size, unpacked.length);
            reassembled.writeBytes(unpacked);

            // C smsysex.cpp:657-661 returns "size", not "bytes"
            callback.onResponse(
                String.format(
                    "{\"^write\": {\"fid\": 7, \"addr\": %d, \"size\": %d, \"err\": 0}}",
                    addr, size),
                null);
          }
        };

    DelugeFileSyncService service = new DelugeFileSyncService(mockManager);
    CompletableFuture<Void> done = new CompletableFuture<>();
    Thread.ofVirtual()
        .start(
            () -> {
              try {
                service.uploadFileBlocking("/SONGS/MULTI.XML", content);
                done.complete(null);
              } catch (Exception e) {
                done.completeExceptionally(e);
              }
            });

    done.get(10, TimeUnit.SECONDS);
    assertEquals(List.of(0, 512, 1024), seenAddrs);
    assertEquals(List.of(512, 512, 176), seenSizes);
    assertArrayEquals(content, reassembled.toByteArray());
  }

  @Test
  void uploadFileBlockingThrowsWhenFirmwareWriteReturnsSizeZero() {
    byte[] content = {1, 2, 3, 4};
    DelugeSysExManager mockManager =
        new DelugeSysExManager() {
          @Override
          public void sendRequest(String jsonPayload, SysExCallback callback) {
            if (jsonPayload.contains("\"open\"")) {
              callback.onResponse("{\"^open\": {\"fid\": 8, \"size\": 0, \"err\": 0}}", null);
            }
          }

          @Override
          public void sendRequest(
              String jsonPayload, byte[] binaryPayload, SysExCallback callback) {
            callback.onResponse(
                "{\"^write\": {\"fid\": 8, \"addr\": 0, \"size\": 0, \"err\": 0}}", null);
          }
        };

    DelugeFileSyncService service = new DelugeFileSyncService(mockManager);
    assertThrows(IOException.class, () -> service.uploadFileBlocking("/SONGS/FULL.XML", content));
  }

  @Test
  void encodeFatDateTimeHandlesZeroAndPre1980EpochSafely() {
    assertArrayEquals(new int[] {0, 0}, DelugeFileSyncService.encodeFatDateTime(0L));
    assertArrayEquals(new int[] {0, 0}, DelugeFileSyncService.encodeFatDateTime(-5000L));

    int[] pre1980 = DelugeFileSyncService.encodeFatDateTime(1000L);
    assertTrue(pre1980[0] > 0, "Pre-1980 timestamp must clamp year to 1980 instead of negative");
    assertEquals(0, (pre1980[0] >> 9) & 0x7F);
  }

  @Test
  void unpack7to8RleHandlesNonPositiveExpectedDstSize() {
    assertEquals(0, DelugeMidiPacker.unpack7to8Rle(new byte[] {1, 2}, 0).length);
    assertEquals(0, DelugeMidiPacker.unpack7to8Rle(new byte[] {1, 2}, -5).length);
  }

  private static int extractInt(String json, String key) {
    Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+)").matcher(json);
    assertTrue(m.find(), "Missing key '" + key + "' in " + json);
    return Integer.parseInt(m.group(1));
  }
}
