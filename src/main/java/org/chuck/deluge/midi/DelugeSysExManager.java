package org.chuck.deluge.midi;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages JSON-over-SysEx and HID SysEx communication with the physical Deluge hardware. Provides
 * thread-safe request/response dispatch, 7-to-8 bit unpacking of binary payloads, and support for
 * real-time OLED and 7-segment display stream decoding.
 */
public class DelugeSysExManager {

  public interface SysExCallback {
    void onResponse(String jsonStr, byte[] binaryData);
  }

  public interface DisplayListener {
    /** Called when a new OLED display frame is received (768 bytes, 128x64 monochrome). */
    void onOledFrame(byte[] frameBuffer);

    /** Called when 7-segment display characters change. */
    void onSevenSegment(String text);
  }

  // SysEx Protocol Constants
  private static final byte SYSEX_START = (byte) 0xF0;
  private static final byte SYSEX_END = (byte) 0xF7;
  private static final byte[] DELUGE_HEADER = {0x00, 0x21, 0x7B, 0x01};

  // Commands
  private static final byte CMD_JSON_REQUEST = 0x04;
  private static final byte CMD_JSON_REPLY = 0x05;
  private static final byte CMD_HID = 0x02;

  private final AtomicInteger seqCounter = new AtomicInteger(1);
  private final Map<Integer, SysExCallback> pendingCallbacks = new ConcurrentHashMap<>();
  private org.chuck.midi.MidiOut activeMidiOut;
  private DisplayListener displayListener;

  // Local OLED frame buffer to apply deltas onto (768 bytes, 128x64 pixels)
  private final byte[] oledFrameBuffer = new byte[768];

  public void setMidiOut(org.chuck.midi.MidiOut midiOut) {
    this.activeMidiOut = midiOut;
  }

  public void setDisplayListener(DisplayListener listener) {
    this.displayListener = listener;
  }

  /**
   * Sends a JSON request to the physical Deluge.
   *
   * @param jsonPayload The JSON request string (e.g. {@code {"ping":{}}})
   * @param callback Optional callback invoked when the reply is received
   */
  public void sendRequest(String jsonPayload, SysExCallback callback) {
    sendRequest(jsonPayload, null, callback);
  }

  /**
   * Sends a JSON request to the physical Deluge with an optional binary payload.
   *
   * @param jsonPayload The JSON request string
   * @param binaryPayload Optional packed 7-bit binary payload (e.g. for write blocks)
   * @param callback Optional callback invoked when the reply is received
   */
  public void sendRequest(String jsonPayload, byte[] binaryPayload, SysExCallback callback) {
    if (activeMidiOut == null) {
      System.err.println("[SysExManager] Cannot send request: No MidiOut configured.");
      return;
    }

    int seq = seqCounter.getAndUpdate(val -> (val % 127) + 1);
    if (callback != null) {
      pendingCallbacks.put(seq, callback);
    }

    byte[] jsonBytes = jsonPayload.getBytes(StandardCharsets.US_ASCII);
    int binLen = binaryPayload != null ? binaryPayload.length : 0;
    int totalLen = 5 + 1 + 1 + jsonBytes.length + (binLen > 0 ? (1 + binLen) : 0) + 1;
    byte[] packet = new byte[totalLen];

    packet[0] = SYSEX_START;
    System.arraycopy(DELUGE_HEADER, 0, packet, 1, 4);
    packet[5] = CMD_JSON_REQUEST;
    packet[6] = (byte) seq;
    System.arraycopy(jsonBytes, 0, packet, 7, jsonBytes.length);

    if (binLen > 0) {
      int spacerIdx = 7 + jsonBytes.length;
      packet[spacerIdx] = 0; // spacer
      System.arraycopy(binaryPayload, 0, packet, spacerIdx + 1, binLen);
    }

    packet[packet.length - 1] = SYSEX_END;

    org.chuck.midi.MidiMsg msg = new org.chuck.midi.MidiMsg();
    msg.setData(packet);
    try {
      activeMidiOut.send(msg);
    } catch (Exception e) {
      System.err.println("[SysExManager] Failed to send SysEx: " + e.getMessage());
      pendingCallbacks.remove(seq);
    }
  }

  /**
   * Sends a SysEx request to the physical Deluge to start real-time streaming of the OLED display
   * frame buffer.
   */
  public void startOledStreaming() {
    if (activeMidiOut == null) return;
    byte[] packet = {
      SYSEX_START,
      DELUGE_HEADER[0],
      DELUGE_HEADER[1],
      DELUGE_HEADER[2],
      DELUGE_HEADER[3],
      CMD_HID,
      0x00, // OLED Request subtype
      0x03, // Mode 3 (Start stream + force repaint)
      SYSEX_END
    };
    org.chuck.midi.MidiMsg msg = new org.chuck.midi.MidiMsg();
    msg.setData(packet);
    try {
      activeMidiOut.send(msg);
      System.out.println(
          "[SysExManager] Sent OLED Real-Time Streaming Request to physical Deluge.");
    } catch (Exception e) {
      System.err.println("[SysExManager] Failed to send OLED stream request: " + e.getMessage());
    }
  }

  /**
   * Intercepts and processes raw incoming SysEx messages from the MIDI input thread.
   *
   * @param data Raw SysEx byte array starting with 0xF0 and ending with 0xF7
   * @return true if the message was handled as a Deluge SysEx message, false otherwise
   */
  public boolean handleIncomingSysEx(byte[] data) {
    if (data == null || data.length < 8) return false;

    // Verify header
    if (data[0] != SYSEX_START) return false;
    for (int i = 0; i < 4; i++) {
      if (data[i + 1] != DELUGE_HEADER[i]) {
        return false;
      }
    }

    byte cmd = data[5];

    if (cmd == CMD_JSON_REPLY) {
      int seq = data[6] & 0xFF;

      // Find the null (0) spacer separating JSON from optional binary payload
      int spacerIndex = -1;
      for (int i = 7; i < data.length - 1; i++) {
        if (data[i] == 0) {
          spacerIndex = i;
          break;
        }
      }

      String jsonStr;
      byte[] binaryData = new byte[0];

      if (spacerIndex >= 7) {
        jsonStr = new String(data, 7, spacerIndex - 7, StandardCharsets.US_ASCII);
        int packedLen = (data.length - 1) - (spacerIndex + 1);
        if (packedLen > 0) {
          byte[] packed = new byte[packedLen];
          System.arraycopy(data, spacerIndex + 1, packed, 0, packedLen);
          binaryData = DelugeMidiPacker.unpack7to8(packed);
        }
      } else {
        // No binary payload spacer found, the entire content is the JSON string (excluding 0xF7)
        jsonStr = new String(data, 7, data.length - 8, StandardCharsets.US_ASCII);
      }

      SysExCallback callback = pendingCallbacks.remove(seq);
      if (callback != null) {
        callback.onResponse(jsonStr, binaryData);
      }
      return true;
    } else if (cmd == CMD_HID) {
      if (displayListener == null) return true;

      int subType = data[6] & 0xFF;
      if (subType == 0x40) { // OLED Frame
        int rleType = data[7] & 0xFF;
        if (rleType == 0x00 || rleType == 0x01) {
          // Full OLED Frame (Raw or RLE-packed)
          int packedLen = (data.length - 1) - 9;
          if (packedLen > 0) {
            byte[] packed = new byte[packedLen];
            System.arraycopy(data, 9, packed, 0, packedLen);
            byte[] unpacked = DelugeMidiPacker.unpack7to8(packed);

            // Draw into local oled frame buffer
            System.arraycopy(unpacked, 0, oledFrameBuffer, 0, Math.min(unpacked.length, 768));
            displayListener.onOledFrame(oledFrameBuffer.clone());
          }
        } else if (rleType == 0x02) {
          // Delta OLED Frame
          int startWord = data[8] & 0xFF;
          int lenWords = data[9] & 0xFF;
          int packedLen = (data.length - 1) - 10;
          if (packedLen > 0) {
            byte[] packed = new byte[packedLen];
            System.arraycopy(data, 10, packed, 0, packedLen);
            byte[] unpacked = DelugeMidiPacker.unpack7to8(packed);

            // Apply delta blocks (each word block represents 8 bytes of OLED screen space)
            int startOffset = 8 * startWord;
            System.arraycopy(
                unpacked,
                0,
                oledFrameBuffer,
                startOffset,
                Math.min(unpacked.length, 768 - startOffset));
            displayListener.onOledFrame(oledFrameBuffer.clone());
          }
        }
      } else if (subType == 0x41) { // 7-Segment
        int packedLen = (data.length - 1) - 9;
        if (packedLen > 0) {
          byte[] packed = new byte[packedLen];
          System.arraycopy(data, 9, packed, 0, packedLen);
          byte[] unpacked = DelugeMidiPacker.unpack7to8(packed);

          // Decode 7-segment raw segment bytes to ascii (best effort representation)
          String segText = decodeSevenSegment(unpacked);
          displayListener.onSevenSegment(segText);
        }
      }
      return true;
    }

    return false;
  }

  /** Helper to decode raw segment-lit bytes into human-readable characters. */
  private String decodeSevenSegment(byte[] segs) {
    StringBuilder sb = new StringBuilder();
    for (byte b : segs) {
      char c = matchSegmentChar(b & 0xFF);
      if (c != ' ') sb.append(c);
    }
    return sb.toString();
  }

  /** Maps 7-segment LED binary lit states back to characters. */
  private char matchSegmentChar(int mask) {
    switch (mask & 0x7F) {
      case 0x3F -> {
        return '0';
      }
      case 0x06 -> {
        return '1';
      }
      case 0x5B -> {
        return '2';
      }
      case 0x4F -> {
        return '3';
      }
      case 0x66 -> {
        return '4';
      }
      case 0x6D -> {
        return '5';
      }
      case 0x7D -> {
        return '6';
      }
      case 0x07 -> {
        return '7';
      }
      case 0x7F -> {
        return '8';
      }
      case 0x6F -> {
        return '9';
      }
      case 0x77 -> {
        return 'A';
      }
      case 0x7C -> {
        return 'b';
      }
      case 0x39 -> {
        return 'C';
      }
      case 0x5E -> {
        return 'd';
      }
      case 0x79 -> {
        return 'E';
      }
      case 0x71 -> {
        return 'F';
      }
      default -> {
        return ' ';
      }
    }
  }
}
