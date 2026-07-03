package org.deluge.midi;

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

  public interface MidiDebugListener {
    /** Called when a real-time hardware debug log message is received. */
    void onDebugMessage(String message);
  }

  // SysEx Protocol Constants
  private static final byte SYSEX_START = (byte) 0xF0;
  private static final byte SYSEX_END = (byte) 0xF7;
  private static final byte[] DELUGE_HEADER = {0x00, 0x21, 0x7B, 0x01};

  // Commands
  private static final byte CMD_JSON_REQUEST = 0x04;
  private static final byte CMD_JSON_REPLY = 0x05;
  private static final byte CMD_HID = 0x02;
  private static final byte CMD_DEBUG = 0x03;

  private final AtomicInteger seqCounter = new AtomicInteger(0);
  private final Map<Integer, SysExCallback> pendingCallbacks = new ConcurrentHashMap<>();
  private org.deluge.shadow.midi.MidiOut activeMidiOut;
  private DisplayListener displayListener;
  private MidiDebugListener debugListener;
  private volatile boolean oledStreamingEnabled = true;
  private static boolean hasWarnedNoMidiOut = false;

  private volatile int sessionId = 0;
  private volatile int midMin = 1;
  private volatile int midMax = 7;
  private volatile java.util.concurrent.CompletableFuture<Void> sessionNegotiationFuture;

  public int getSessionId() {
    return sessionId;
  }

  public int getMidMin() {
    return midMin;
  }

  public int getMidMax() {
    return midMax;
  }

  public boolean isOledStreamingEnabled() {
    return oledStreamingEnabled;
  }

  public void setOledStreamingEnabled(boolean enabled) {
    this.oledStreamingEnabled = enabled;
  }

  // Local OLED frame buffer to apply deltas onto (768 bytes, 128x64 pixels)
  private final byte[] oledFrameBuffer = new byte[768];

  public void setMidiOut(org.deluge.shadow.midi.MidiOut midiOut) {
    this.activeMidiOut = midiOut;
  }

  /** Whether an output port is wired up (so requests can actually be sent). */
  public boolean hasMidiOut() {
    return activeMidiOut != null;
  }

  public void setDisplayListener(DisplayListener listener) {
    this.displayListener = listener;
  }

  public void setMidiDebugListener(MidiDebugListener listener) {
    this.debugListener = listener;
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
      if (!hasWarnedNoMidiOut) {
        System.err.println("[SysExManager] Cannot send request: No MidiOut configured.");
        hasWarnedNoMidiOut = true;
      }
      return;
    }

    int seq =
        seqCounter.updateAndGet(
            val -> {
              if (val < midMin || val > midMax) {
                return midMin;
              }
              int next = val + 1;
              if (next > midMax) {
                return midMin;
              }
              return next;
            });
    if (callback != null) {
      pendingCallbacks.put(seq, callback);
    }

    byte[] jsonBytes = jsonPayload.getBytes(StandardCharsets.US_ASCII);
    int binLen = binaryPayload != null ? binaryPayload.length : 0;
    int totalLen = 5 + 1 + 1 + jsonBytes.length + (binLen > 0 ? (1 + binLen) : 0) + 1;
    byte[] packet = new byte[totalLen];

    packet[0] = SYSEX_START;
    System.arraycopy(DELUGE_HEADER, 0, packet, 1, 4);
    packet[1] = (byte) sessionId;
    packet[5] = CMD_JSON_REQUEST;
    packet[6] = (byte) seq;
    System.arraycopy(jsonBytes, 0, packet, 7, jsonBytes.length);

    if (binLen > 0) {
      int spacerIdx = 7 + jsonBytes.length;
      packet[spacerIdx] = 0; // spacer
      System.arraycopy(binaryPayload, 0, packet, spacerIdx + 1, binLen);
    }

    packet[packet.length - 1] = SYSEX_END;

    org.deluge.shadow.midi.MidiMsg msg = new org.deluge.shadow.midi.MidiMsg();
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
    org.deluge.shadow.midi.MidiMsg msg = new org.deluge.shadow.midi.MidiMsg();
    msg.setData(packet);
    try {
      activeMidiOut.send(msg);
    } catch (Exception e) {
      System.err.println("[SysExManager] Failed to send OLED stream request: " + e.getMessage());
    }
  }

  /**
   * Toggles the physical Deluge's real-time debug log streaming over USB MIDI.
   *
   * @param enabled true to enable streaming, false to disable
   */
  public void setMidiDebugEnabled(boolean enabled) {
    if (activeMidiOut == null) return;
    byte[] packet = {
      SYSEX_START,
      DELUGE_HEADER[0],
      DELUGE_HEADER[1],
      DELUGE_HEADER[2],
      DELUGE_HEADER[3],
      CMD_DEBUG,
      0x00, // Subcommand subtype: toggle debug stream
      (byte) (enabled ? 0x01 : 0x00), // Value: 1=enable, 0=disable
      SYSEX_END
    };
    org.deluge.shadow.midi.MidiMsg msg = new org.deluge.shadow.midi.MidiMsg();
    msg.setData(packet);
    try {
      activeMidiOut.send(msg);
    } catch (Exception e) {
      System.err.println("[SysExManager] Failed to toggle debug streaming: " + e.getMessage());
    }
  }

  /**
   * Negotiates a stateful MIDI session with the physical Deluge.
   *
   * @param clientTag The client identifier tag
   * @return A CompletableFuture that completes when the session has been successfully established
   */
  public java.util.concurrent.CompletableFuture<Void> negotiateSession(String clientTag) {
    this.sessionNegotiationFuture = new java.util.concurrent.CompletableFuture<>();
    String payload = "{\"session\":{\"tag\":\"" + clientTag + "\"}}";
    sendRequest(payload, null);
    return sessionNegotiationFuture;
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
    if (data[1] != 0x00 && data[1] != (byte) sessionId) {
      return false;
    }
    for (int i = 1; i < 4; i++) {
      if (data[i + 1] != DELUGE_HEADER[i]) {
        return false;
      }
    }

    byte cmd = data[5];

    if (cmd == CMD_JSON_REPLY || cmd == CMD_JSON_REQUEST) {
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

      if (jsonStr.contains("\"^session\"")) {
        try {
          int sid = extractIntField(jsonStr, "sid");
          int min = extractIntField(jsonStr, "midMin");
          int max = extractIntField(jsonStr, "midMax");
          this.sessionId = sid;
          this.midMin = min;
          this.midMax = max;
          if (sessionNegotiationFuture != null) {
            sessionNegotiationFuture.complete(null);
          }
        } catch (Exception e) {
          System.err.println("[SysExManager] Failed to parse session response: " + e.getMessage());
          if (sessionNegotiationFuture != null) {
            sessionNegotiationFuture.completeExceptionally(e);
          }
        }
        return true;
      }

      SysExCallback callback = pendingCallbacks.remove(seq);
      if (callback != null) {
        callback.onResponse(jsonStr, binaryData);
      }
      return true;
    } else if (cmd == CMD_HID) {
      if (!oledStreamingEnabled) {
        // Discard instantly during active file transfers to prevent head-of-line blocking
        return true;
      }
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
            byte[] unpacked;
            if (rleType == 0x01) {
              unpacked = DelugeMidiPacker.unpack7to8Rle(packed, 768);
            } else {
              unpacked = DelugeMidiPacker.unpack7to8(packed);
            }

            // Draw into local oled frame buffer
            System.arraycopy(unpacked, 0, oledFrameBuffer, 0, Math.min(unpacked.length, 768));
            displayListener.onOledFrame(oledFrameBuffer.clone());
          }
        } else if (rleType == 0x02) {
          // Delta OLED Frame (always RLE-packed in firmware, fallback to raw for tests)
          int startWord = data[8] & 0xFF;
          int lenWords = data[9] & 0xFF;
          int packedLen = (data.length - 1) - 10;
          if (packedLen > 0) {
            byte[] packed = new byte[packedLen];
            System.arraycopy(data, 10, packed, 0, packedLen);
            byte[] unpacked = DelugeMidiPacker.unpack7to8Rle(packed, 8 * lenWords);
            if (unpacked.length < 8 * lenWords) {
              unpacked = DelugeMidiPacker.unpack7to8(packed);
            }

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
    } else if (cmd == CMD_DEBUG) {
      if (debugListener == null) return true;
      int subType = data[6] & 0xFF;
      if (subType == 0x40) { // Debug log print subtype
        // Text starts at index 8 (reply_hdr is 8 bytes in C++) and runs until length - 2
        int payloadLen = (data.length - 1) - 8;
        if (payloadLen > 0) {
          String message = new String(data, 8, payloadLen, StandardCharsets.US_ASCII);
          debugListener.onDebugMessage(message);
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

  private int extractIntField(String json, String field) {
    String pattern = "\"" + field + "\":";
    int idx = json.indexOf(pattern);
    if (idx == -1) {
      throw new IllegalArgumentException("Field '" + field + "' not found in JSON: " + json);
    }
    int start = idx + pattern.length();
    int end = start;
    while (end < json.length()
        && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
      end++;
    }
    return Integer.parseInt(json.substring(start, end));
  }
}
