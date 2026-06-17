package org.chuck.deluge.midi;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.chuck.midi.MidiMsg;
import org.junit.jupiter.api.Test;

public class DelugeSysExManagerTest {

  // A simple test subclass of MidiOut to capture sent messages
  private static class MockMidiOut extends org.chuck.midi.MidiOut {
    final AtomicReference<byte[]> lastSentData = new AtomicReference<>();

    @Override
    public void send(MidiMsg msg) {
      lastSentData.set(msg.getData());
    }
  }

  @Test
  public void testSendRequestFormatting() {
    DelugeSysExManager manager = new DelugeSysExManager();
    MockMidiOut mockOut = new MockMidiOut();
    manager.setMidiOut(mockOut);

    AtomicBoolean callbackTriggered = new AtomicBoolean(false);
    manager.sendRequest("{\"ping\":{}}", (json, bin) -> callbackTriggered.set(true));

    byte[] sent = mockOut.lastSentData.get();
    assertNotNull(sent);
    assertTrue(sent.length > 8);

    // Verify Header
    assertEquals((byte) 0xF0, sent[0]);
    assertEquals((byte) 0x00, sent[1]);
    assertEquals((byte) 0x21, sent[2]);
    assertEquals((byte) 0x7B, sent[3]);
    assertEquals((byte) 0x01, sent[4]);

    // Verify Command (0x04 = Request) and Sequence (1)
    assertEquals((byte) 0x04, sent[5]);
    assertEquals((byte) 0x01, sent[6]);

    // Verify Payload
    String payload = new String(sent, 7, sent.length - 8, StandardCharsets.US_ASCII);
    assertEquals("{\"ping\":{}}", payload);

    // Verify Footer
    assertEquals((byte) 0xF7, sent[sent.length - 1]);
  }

  @Test
  public void testHandleJsonReplyWithoutBinary() {
    DelugeSysExManager manager = new DelugeSysExManager();
    MockMidiOut mockOut = new MockMidiOut();
    manager.setMidiOut(mockOut);

    AtomicReference<String> receivedJson = new AtomicReference<>();
    AtomicReference<byte[]> receivedBin = new AtomicReference<>();

    // Send a request to register callback at sequence 1
    manager.sendRequest(
        "{\"ping\":{}}",
        (json, bin) -> {
          receivedJson.set(json);
          receivedBin.set(bin);
        });

    // Simulate incoming reply: F0 00 21 7B 01 05 [seq=1] '{"^ping":{}}' F7
    String replyJson = "{\"^ping\":{}}";
    byte[] jsonBytes = replyJson.getBytes(StandardCharsets.US_ASCII);
    byte[] incoming = new byte[7 + jsonBytes.length + 1];
    incoming[0] = (byte) 0xF0;
    incoming[1] = 0x00;
    incoming[2] = 0x21;
    incoming[3] = 0x7B;
    incoming[4] = 0x01;
    incoming[5] = 0x05; // CMD_JSON_REPLY
    incoming[6] = 0x01; // sequence 1
    System.arraycopy(jsonBytes, 0, incoming, 7, jsonBytes.length);
    incoming[incoming.length - 1] = (byte) 0xF7;

    boolean handled = manager.handleIncomingSysEx(incoming);
    assertTrue(handled);
    assertEquals(replyJson, receivedJson.get());
    assertEquals(0, receivedBin.get().length);
  }

  @Test
  public void testHandleJsonReplyWithBinary() {
    DelugeSysExManager manager = new DelugeSysExManager();
    MockMidiOut mockOut = new MockMidiOut();
    manager.setMidiOut(mockOut);

    AtomicReference<String> receivedJson = new AtomicReference<>();
    AtomicReference<byte[]> receivedBin = new AtomicReference<>();

    // Send request to register callback at sequence 1
    manager.sendRequest(
        "{\"read\":{}}",
        (json, bin) -> {
          receivedJson.set(json);
          receivedBin.set(bin);
        });

    // Binary data we want the Deluge to send us: 14 bytes
    byte[] rawBinary =
        new byte[] {
          (byte) 0xAA, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A,
          (byte) 0xBB, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1A
        };
    byte[] packedBinary = DelugeMidiPacker.pack8to7(rawBinary);

    // Simulate incoming reply: F0 00 21 7B 01 05 [seq=1] '{"^read":{}}' [0 spacer] [packed binary]
    // F7
    String replyJson = "{\"^read\":{\"size\":14}}";
    byte[] jsonBytes = replyJson.getBytes(StandardCharsets.US_ASCII);

    byte[] incoming = new byte[7 + jsonBytes.length + 1 + packedBinary.length + 1];
    incoming[0] = (byte) 0xF0;
    incoming[1] = 0x00;
    incoming[2] = 0x21;
    incoming[3] = 0x7B;
    incoming[4] = 0x01;
    incoming[5] = 0x05; // CMD_JSON_REPLY
    incoming[6] = 0x01; // sequence 1
    System.arraycopy(jsonBytes, 0, incoming, 7, jsonBytes.length);
    incoming[7 + jsonBytes.length] = 0; // spacer
    System.arraycopy(packedBinary, 0, incoming, 7 + jsonBytes.length + 1, packedBinary.length);
    incoming[incoming.length - 1] = (byte) 0xF7;

    boolean handled = manager.handleIncomingSysEx(incoming);
    assertTrue(handled);
    assertEquals(replyJson, receivedJson.get());
    assertArrayEquals(rawBinary, receivedBin.get());
  }

  @Test
  public void testOledDisplayDeltaMirror() {
    DelugeSysExManager manager = new DelugeSysExManager();
    AtomicReference<byte[]> oledFrame = new AtomicReference<>();

    manager.setDisplayListener(
        new DelugeSysExManager.DisplayListener() {
          @Override
          public void onOledFrame(byte[] frameBuffer) {
            oledFrame.set(frameBuffer);
          }

          @Override
          public void onSevenSegment(String text) {}
        });

    // Create a 16-byte delta block to write to startWord=2 (offset 16 bytes)
    byte[] rawDelta = new byte[16];
    for (int i = 0; i < 16; i++) {
      rawDelta[i] = (byte) (i + 1);
    }
    byte[] packedDelta = DelugeMidiPacker.pack8to7(rawDelta);

    // Header: F0 00 21 7B 01 02 [OLED=0x40] [Delta=0x02] [startWord=2] [lenWords=2] [packedDelta]
    // F7
    byte[] incoming = new byte[10 + packedDelta.length + 1];
    incoming[0] = (byte) 0xF0;
    incoming[1] = 0x00;
    incoming[2] = 0x21;
    incoming[3] = 0x7B;
    incoming[4] = 0x01;
    incoming[5] = 0x02; // CMD_HID
    incoming[6] = 0x40; // OLED Frame subtype
    incoming[7] = 0x02; // Delta type
    incoming[8] = 0x02; // startWord
    incoming[9] = 0x02; // lenWords
    System.arraycopy(packedDelta, 0, incoming, 10, packedDelta.length);
    incoming[incoming.length - 1] = (byte) 0xF7;

    boolean handled = manager.handleIncomingSysEx(incoming);
    assertTrue(handled);

    byte[] frame = oledFrame.get();
    assertNotNull(frame);
    assertEquals(768, frame.length);

    // Verify delta was correctly applied at offset 16
    for (int i = 0; i < 16; i++) {
      assertEquals((byte) (i + 1), frame[16 + i]);
    }
    // Verify rest is empty
    assertEquals(0, frame[0]);
    assertEquals(0, frame[32]);
  }

  @Test
  public void testToggleMidiDebugStreaming() {
    DelugeSysExManager manager = new DelugeSysExManager();
    MockMidiOut mockOut = new MockMidiOut();
    manager.setMidiOut(mockOut);

    // 1. Enable debug
    manager.setMidiDebugEnabled(true);
    byte[] sentEnable = mockOut.lastSentData.get();
    assertNotNull(sentEnable);
    assertEquals(9, sentEnable.length);
    assertEquals((byte) 0xF0, sentEnable[0]);
    assertEquals((byte) 0x00, sentEnable[1]);
    assertEquals((byte) 0x21, sentEnable[2]);
    assertEquals((byte) 0x7B, sentEnable[3]);
    assertEquals((byte) 0x01, sentEnable[4]);
    assertEquals((byte) 0x03, sentEnable[5]); // CMD_DEBUG
    assertEquals((byte) 0x00, sentEnable[6]); // Subcommand: debug control
    assertEquals((byte) 0x01, sentEnable[7]); // Value: 1 (enable)
    assertEquals((byte) 0xF7, sentEnable[8]);

    // 2. Disable debug
    manager.setMidiDebugEnabled(false);
    byte[] sentDisable = mockOut.lastSentData.get();
    assertNotNull(sentDisable);
    assertEquals((byte) 0x00, sentDisable[7]); // Value: 0 (disable)
  }

  @Test
  public void testHandleIncomingDebugMessage() {
    DelugeSysExManager manager = new DelugeSysExManager();
    AtomicReference<String> receivedLog = new AtomicReference<>();

    manager.setMidiDebugListener(receivedLog::set);

    // Simulate incoming log message: "Hello deluge!"
    // Header: F0 00 21 7B 01 03 40 00 [ASCII] F7
    String text = "Hello deluge!";
    byte[] textBytes = text.getBytes(StandardCharsets.US_ASCII);
    byte[] incoming = new byte[8 + textBytes.length + 1];
    incoming[0] = (byte) 0xF0;
    incoming[1] = 0x00;
    incoming[2] = 0x21;
    incoming[3] = 0x7B;
    incoming[4] = 0x01;
    incoming[5] = 0x03; // CMD_DEBUG
    incoming[6] = 0x40; // Subtype: log message
    incoming[7] = 0x00; // Reserved
    System.arraycopy(textBytes, 0, incoming, 8, textBytes.length);
    incoming[incoming.length - 1] = (byte) 0xF7;

    boolean handled = manager.handleIncomingSysEx(incoming);
    assertTrue(handled);
    assertEquals(text, receivedLog.get());
  }
}
