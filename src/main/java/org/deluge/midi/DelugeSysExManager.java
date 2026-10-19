package org.deluge.midi;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Manages JSON-over-SysEx and HID SysEx communication with the physical Deluge hardware. Provides
 * thread-safe request/response dispatch, 7-to-8 bit unpacking of binary payloads, and support for
 * real-time OLED and 7-segment display stream decoding.
 */
public class DelugeSysExManager {
  private static final Logger LOGGER = Logger.getLogger(DelugeSysExManager.class.getName());

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
  private final CopyOnWriteArrayList<DisplayListener> displayListeners =
      new CopyOnWriteArrayList<>();
  private final CopyOnWriteArrayList<MidiDebugListener> debugListeners =
      new CopyOnWriteArrayList<>();
  private final DisplayListener compositeDisplayListener =
      new DisplayListener() {
        @Override
        public void onOledFrame(byte[] frameBuffer) {
          for (DisplayListener l : displayListeners) {
            l.onOledFrame(frameBuffer);
          }
        }

        @Override
        public void onSevenSegment(String text) {
          for (DisplayListener l : displayListeners) {
            l.onSevenSegment(text);
          }
        }
      };
  private final MidiDebugListener compositeDebugListener =
      message -> {
        for (MidiDebugListener l : debugListeners) {
          l.onDebugMessage(message);
        }
      };
  private volatile DisplayListener displayListener;
  private volatile MidiDebugListener debugListener;
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
    displayListeners.clear();
    if (listener != null) {
      displayListeners.add(listener);
      displayListener = compositeDisplayListener;
    } else {
      displayListener = null;
    }
  }

  public void addDisplayListener(DisplayListener listener) {
    if (listener == null) {
      return;
    }
    displayListeners.addIfAbsent(listener);
    displayListener = compositeDisplayListener;
  }

  public void removeDisplayListener(DisplayListener listener) {
    if (listener == null) {
      return;
    }
    displayListeners.remove(listener);
    if (displayListeners.isEmpty()) {
      displayListener = null;
    }
  }

  public void setMidiDebugListener(MidiDebugListener listener) {
    debugListeners.clear();
    if (listener != null) {
      debugListeners.add(listener);
      debugListener = compositeDebugListener;
    } else {
      debugListener = null;
    }
  }

  public void addMidiDebugListener(MidiDebugListener listener) {
    if (listener == null) {
      return;
    }
    debugListeners.addIfAbsent(listener);
    debugListener = compositeDebugListener;
  }

  public void removeMidiDebugListener(MidiDebugListener listener) {
    if (listener == null) {
      return;
    }
    debugListeners.remove(listener);
    if (debugListeners.isEmpty()) {
      debugListener = null;
    }
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
        LOGGER.warning("[SysExManager] Cannot send request: No MidiOut configured.");
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
    // NOTE: the session is NOT carried in the manufacturer-ID byte. The firmware reads it from the
    // msgId byte (packet[6]), and the session's midMin/midMax range (returned by ^session as
    // (sid<<3)+1 .. (sid<<3)+MAX) already bakes the session bits into `seq`. Overwriting packet[1]
    // here corrupted the `00 21 7B` manufacturer ID for any non-zero session, so every file op
    // after
    // session negotiation was silently dropped by the firmware. Leave packet[1] = 0x00.
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
      LOGGER.warning("[SysExManager] Failed to send SysEx: " + e.getMessage());
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
      LOGGER.warning("[SysExManager] Failed to send OLED stream request: " + e.getMessage());
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
      LOGGER.warning("[SysExManager] Failed to toggle debug streaming: " + e.getMessage());
    }
  }

  /**
   * Negotiates a stateful MIDI session with the physical Deluge, resending on a dropped reply.
   *
   * <p>Previously this fired a single, un-retried request: if that lone ~30-byte SysEx message was
   * dropped in transit (the same USB SysEx unreliability documented on every retryable request in
   * {@link org.deluge.midi.DelugeFileSyncService#sendWithRetry}), the returned future simply hung
   * forever with no timeout, no retry, and no error -- {@code sessionId} silently stayed 0 for the
   * rest of the connection, and every subsequent file/dir/ping request then failed with a confusing
   * "no reply" of its own, with nothing pointing back at the real cause. Observed live: a real
   * Deluge occasionally drops this first exchange, especially after many rapid reconnects.
   *
   * @param clientTag The client identifier tag
   * @return A CompletableFuture that completes when the session has been successfully established,
   *     or fails after retrying if the Deluge never replies at all
   */
  public java.util.concurrent.CompletableFuture<Void> negotiateSession(String clientTag) {
    java.util.concurrent.CompletableFuture<Void> resultFuture =
        new java.util.concurrent.CompletableFuture<>();
    this.sessionNegotiationFuture = resultFuture;
    String payload = "{\"session\":{\"tag\":\"" + clientTag + "\"}}";
    // First attempt is sent synchronously, before this method returns -- matches the prior
    // behavior callers/tests rely on (e.g. inspecting the just-sent packet right after calling
    // this). Only the retry-on-drop wait runs in the background.
    sendRequest(payload, null);

    Thread.ofVirtual()
        .name("DelugeSessionNegotiator")
        .start(
            () -> {
              final int maxAttempts = 5;
              int attempt = 1;
              while (true) {
                try {
                  resultFuture.get(1000, java.util.concurrent.TimeUnit.MILLISECONDS);
                  return; // reply arrived, future already completed by handleIncomingSysEx
                } catch (java.util.concurrent.TimeoutException te) {
                  if (resultFuture.isDone() || attempt >= maxAttempts) {
                    break;
                  }
                  attempt++;
                  sendRequest(payload, null); // dropped in transit -- resend
                } catch (Exception e) {
                  return; // already resolved (success or a real failure) by some other path
                }
              }
              if (!resultFuture.isDone()) {
                resultFuture.completeExceptionally(
                    new java.io.IOException(
                        "Session negotiation: no reply after " + maxAttempts + " attempts"));
              }
            });
    return resultFuture;
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
          LOGGER.warning("[SysExManager] Failed to parse session response: " + e.getMessage());
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
      DisplayListener currentDisplayListener = displayListener;
      if (currentDisplayListener == null) return true;

      int subType = data[6] & 0xFF;
      if (subType == 0x40) { // OLED Frame (C hid_sysex.cpp:100-211)
        if (data.length < 9) return true;
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
            currentDisplayListener.onOledFrame(oledFrameBuffer.clone());
          }
        } else if (rleType == 0x02) {
          // Delta OLED Frame (always RLE-packed in firmware, fallback to raw for tests)
          if (data.length < 11) return true;
          int startWord = data[8] & 0xFF;
          int lenWords = data[9] & 0xFF;
          int packedLen = (data.length - 1) - 10;
          if (packedLen > 0 && lenWords > 0) {
            byte[] packed = new byte[packedLen];
            System.arraycopy(data, 10, packed, 0, packedLen);
            byte[] unpacked = DelugeMidiPacker.unpack7to8Rle(packed, 8 * lenWords);
            if (unpacked.length < 8 * lenWords) {
              unpacked = DelugeMidiPacker.unpack7to8(packed);
            }

            // Apply delta blocks (each word block represents 8 bytes of OLED screen space,
            // C hid_sysex.cpp:190-205). Guard startOffset < 768 against corrupt SysEx frames.
            int startOffset = 8 * startWord;
            if (startOffset < oledFrameBuffer.length && unpacked.length > 0) {
              System.arraycopy(
                  unpacked,
                  0,
                  oledFrameBuffer,
                  startOffset,
                  Math.min(unpacked.length, oledFrameBuffer.length - startOffset));
              currentDisplayListener.onOledFrame(oledFrameBuffer.clone());
            }
          }
        }
      } else if (subType == 0x41) { // 7-Segment (C hid_sysex.cpp:141-155)
        int packedLen = (data.length - 1) - 9;
        if (packedLen > 0) {
          byte[] packed = new byte[packedLen];
          System.arraycopy(data, 9, packed, 0, packedLen);
          byte[] unpacked = DelugeMidiPacker.unpack7to8(packed);

          // Decode 7-segment raw segment bytes to ascii (best effort representation)
          String segText = decodeSevenSegment(unpacked);
          currentDisplayListener.onSevenSegment(segText);
        }
      }
      return true;
    } else if (cmd == CMD_DEBUG) {
      MidiDebugListener currentDebugListener = debugListener;
      if (currentDebugListener == null) return true;
      int subType = data[6] & 0xFF;
      if (subType == 0x40) { // Debug log print subtype
        // Text starts at index 8 (reply_hdr is 8 bytes in C++) and runs until length - 2
        int payloadLen = (data.length - 1) - 8;
        if (payloadLen > 0) {
          String message = new String(data, 8, payloadLen, StandardCharsets.US_ASCII);
          currentDebugListener.onDebugMessage(message);
        }
      }
      return true;
    }

    return false;
  }

  /**
   * Decodes raw 7-segment display bytes into human-readable characters per C
   * seven_segment.cpp:45-123, 346-357, 448-496.
   */
  private String decodeSevenSegment(byte[] segs) {
    StringBuilder sb = new StringBuilder();
    for (byte b : segs) {
      int raw = b & 0xFF;
      if (raw == 0x00) {
        sb.append(' ');
      } else if (raw == 0x80) {
        sb.append('.');
      } else if (raw == 0xA0) {
        sb.append('!'); // C seven_segment.cpp:478-479
      } else if (raw == 0xA7) {
        sb.append('#'); // C seven_segment.cpp:486-487
      } else {
        char c = matchSegmentChar(raw & 0x7F);
        sb.append(c);
        if ((raw & 0x80) != 0) {
          sb.append('.'); // C seven_segment.cpp:348, 425 (bit 7 is decimal dot)
        }
      }
    }
    return sb.toString().trim();
  }

  /**
   * Maps Deluge 7-segment LED bitmasks (bits 0..6) back to characters per C
   * seven_segment.cpp:61-123, 466-484.
   *
   * <p>Segment layout in C seven_segment.cpp:48-56: bit 6 = top, bit 5 = top-right, bit 4 =
   * bottom-right, bit 3 = bottom, bit 2 = bottom-left, bit 1 = top-left, bit 0 = middle.
   */
  private char matchSegmentChar(int mask) {
    return switch (mask & 0x7F) {
      // numberSegments[0..9] (C seven_segment.cpp:61)
      case 0x7E -> '0';
      case 0x30 -> '1';
      case 0x6D -> '2'; // also 'Z'/'z' in letterSegments[25]
      case 0x79 -> '3';
      case 0x33 -> '4';
      case 0x5B -> '5'; // also 'S'/'s' in letterSegments[18]
      case 0x5F -> '6';
      case 0x70 -> '7';
      case 0x7F -> '8';
      case 0x7B -> '9';
      // letterSegments (C seven_segment.cpp:63-123)
      case 0x77 -> 'A';
      case 0x1F -> 'b';
      case 0x4E -> 'C';
      case 0x0D -> 'c';
      case 0x3D -> 'd';
      case 0x4F -> 'E';
      case 0x47 -> 'F';
      case 0x5E -> 'G';
      case 0x37 -> 'H';
      case 0x04 -> 'i';
      case 0x38 -> 'J';
      case 0x57 -> 'K';
      case 0x0E -> 'L';
      case 0x55 -> 'M';
      case 0x15 -> 'n';
      case 0x1D -> 'o';
      case 0x67 -> 'P';
      case 0x73 -> 'q';
      case 0x05 -> 'r';
      case 0x0F -> 't';
      case 0x3E -> 'U';
      case 0x27 -> 'v';
      case 0x5C -> 'W';
      case 0x49 -> 'X';
      case 0x3B -> 'y';
      // Symbols (C seven_segment.cpp:466-484)
      case 0x01 -> '-';
      case 0x08 -> '_';
      case 0x02 -> '\'';
      case 0x62 -> '^';
      default -> ' ';
    };
  }

  private int extractIntField(String json, String field) {
    String pattern = "\"" + field + "\":";
    int idx = json.indexOf(pattern);
    if (idx == -1) {
      throw new IllegalArgumentException("Field '" + field + "' not found in JSON: " + json);
    }
    int start = idx + pattern.length();
    // The firmware's JSON serializer emits a space after the colon ("sid": 5), so skip whitespace
    // before reading the number — otherwise substring(start, start) is "" and parseInt throws.
    while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
      start++;
    }
    int end = start;
    while (end < json.length()
        && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
      end++;
    }
    return Integer.parseInt(json.substring(start, end));
  }
}
