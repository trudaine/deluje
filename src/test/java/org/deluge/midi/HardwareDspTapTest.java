package org.deluge.midi;

import java.util.ArrayList;
import java.util.List;
import org.deluge.shadow.midi.MidiIn;
import org.deluge.shadow.midi.MidiMsg;
import org.deluge.shadow.midi.MidiOut;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Live hardware harness for the DSP golden-buffer tap (requires a Deluge on USB running the
 * trudaine {@code feat/dsp-buffer-dump} firmware). NOT a CI test — self-skips unless a Deluge MIDI
 * port is present. Run explicitly:
 *
 * <pre>mvn test -Dtest=HardwareDspTapTest -Pslow-tests -Dgpg.skip=true</pre>
 *
 * <p>Validates, in order: (1) connectivity via Ping/Pong, (2) the DSP-tap command pipe (arm + read
 * chunk 0 decodes, capturedCount == 4096), (3) a full capture — optionally after a MIDI note — with
 * peak/non-zero diagnostics. Deluge SysEx framing: {@code F0 00 21 7B 01 <cmd> <sub> ... F7}.
 */
@Tag("hardware")
class HardwareDspTapTest {

  static final byte[] MFR = {0x00, 0x21, 0x7B, 0x01}; // Deluge manufacturer id + 0x01
  static final int CMD_DEBUG = 0x03;

  static int findDeluge(String[] ports) {
    for (int i = 0; i < ports.length; i++) {
      String p = ports[i].toLowerCase();
      if (p.contains("deluge") && (p.contains("midi 1") || !p.contains("midi "))) return i;
    }
    for (int i = 0; i < ports.length; i++) if (ports[i].toLowerCase().contains("deluge")) return i;
    return -1;
  }

  @Test
  void tap() throws Exception {
    String[] outs = MidiOut.list();
    String[] ins = MidiIn.list();
    int oi = findDeluge(outs);
    int ii = findDeluge(ins);
    Assumptions.assumeTrue(
        oi >= 0 && ii >= 0, "no Deluge MIDI port (out=" + oi + " in=" + ii + ")");

    MidiOut out = new MidiOut();
    MidiIn in = new MidiIn();
    try {
      out.open(oi);
      in.open(ii);
      in.ignoreTypes(false, false, false); // allow SysEx
      System.out.println("Opened Deluge: out=" + outs[oi] + "  in=" + ins[ii]);

      // (1) Ping -> Pong (F0 00 21 7B 01 00 F7 ; reply has 0x7F)
      sendSysex(out, new int[] {0x00});
      byte[] pong = recvSysex(in, 1500);
      System.out.println(
          "PING -> "
              + (pong == null ? "NO REPLY" : hex(pong))
              + (isPong(pong) ? "  [PONG OK]" : ""));
      Assumptions.assumeTrue(isPong(pong), "no Pong — check firmware/port/MIDI-in enabled");

      // (2) Arm the DSP tap, then read chunk 0 to prove the command pipe + decode.
      sendSysex(out, new int[] {CMD_DEBUG, 0x03}); // arm
      Thread.sleep(200); // let ~4096 samples (93 ms) render
      DspTapCodec.Chunk c0 = readChunk(out, in, 0);
      System.out.println(
          "TAP chunk0: capturedCount="
              + (c0 == null ? "null" : c0.capturedCount())
              + " nSamples="
              + (c0 == null ? "-" : c0.nSamples()));
      Assumptions.assumeTrue(c0 != null && c0.capturedCount() > 0, "tap did not capture");

      // (3) Full capture (arm, optional MIDI note, read all chunks).
      boolean withNote = Boolean.getBoolean("tap.note"); // -Dtap.note=true to try a MIDI note
      sendSysex(out, new int[] {CMD_DEBUG, 0x03}); // re-arm
      if (withNote) {
        int ch = Integer.getInteger("tap.ch", 0);
        int note = Integer.getInteger("tap.midinote", 60);
        out.send(rawMsg(0x90 | (ch & 0x0F), note, 100)); // note-on
        Thread.sleep(200);
        out.send(rawMsg(0x80 | (ch & 0x0F), note, 0)); // note-off
      }
      Thread.sleep(150);
      int[] full = capture(out, in, c0.capturedCount());
      long nz = 0;
      int peak = 0;
      for (int v : full) {
        if (v != 0) nz++;
        peak = Math.max(peak, Math.abs(v));
      }
      System.out.printf(
          "FULL CAPTURE: %d samples, non-zero=%d, peak=%d (%.4f fs)%s%n",
          full.length, nz, peak, peak / 2.147483648e9, withNote ? " [after MIDI note]" : "");
      System.out.print("  first 8: ");
      for (int i = 0; i < Math.min(8, full.length); i++) System.out.print(full[i] + " ");
      System.out.println();
      if (nz == 0) {
        System.out.println(
            "  (all zero = silence: the Deluge isn't producing audio. Play/hold a note on the"
                + " device during capture, or retry with -Dtap.note=true after enabling MIDI-in.)");
      }
    } finally {
      try {
        in.close();
      } catch (Throwable ignore) {
      }
      try {
        out.close();
      } catch (Throwable ignore) {
      }
    }
  }

  // ── helpers ──

  static DspTapCodec.Chunk readChunk(MidiOut out, MidiIn in, int idx) throws Exception {
    for (int attempt = 0; attempt < 3; attempt++) {
      sendSysex(out, new int[] {CMD_DEBUG, 0x04, idx & 0x7F});
      byte[] reply = recvSysex(in, 1500);
      if (DspTapCodec.isTapReply(reply)) return DspTapCodec.decode(reply);
    }
    return null;
  }

  static int[] capture(MidiOut out, MidiIn in, int total) throws Exception {
    List<DspTapCodec.Chunk> chunks = new ArrayList<>();
    int per = 180;
    int nChunks = (total + per - 1) / per;
    for (int k = 0; k < nChunks; k++) {
      DspTapCodec.Chunk c = readChunk(out, in, k);
      if (c == null) throw new IllegalStateException("missing tap chunk " + k);
      chunks.add(c);
    }
    return DspTapCodec.reassemble(chunks);
  }

  static void sendSysex(MidiOut out, int[] payload) {
    byte[] b = new byte[1 + MFR.length + payload.length + 1];
    b[0] = (byte) 0xF0;
    System.arraycopy(MFR, 0, b, 1, MFR.length);
    for (int i = 0; i < payload.length; i++) b[1 + MFR.length + i] = (byte) payload[i];
    b[b.length - 1] = (byte) 0xF7;
    MidiMsg m = new MidiMsg();
    m.setData(b);
    out.send(m);
  }

  static MidiMsg rawMsg(int status, int d1, int d2) {
    MidiMsg m = new MidiMsg();
    m.setData(new byte[] {(byte) status, (byte) d1, (byte) d2});
    return m;
  }

  /** Poll for a complete SysEx (accumulate until 0xF7) within timeoutMs. */
  static byte[] recvSysex(MidiIn in, long timeoutMs) throws Exception {
    long deadline = System.currentTimeMillis() + timeoutMs;
    List<Byte> acc = new ArrayList<>();
    boolean inSysex = false;
    while (System.currentTimeMillis() < deadline) {
      MidiMsg m = new MidiMsg();
      if (in.recv(m)) {
        byte[] d = m.getData();
        if (d == null || d.length == 0) continue;
        for (byte x : d) {
          if ((x & 0xFF) == 0xF0) {
            inSysex = true;
            acc.clear();
          }
          if (inSysex) acc.add(x);
          if ((x & 0xFF) == 0xF7 && inSysex) {
            byte[] out = new byte[acc.size()];
            for (int i = 0; i < out.length; i++) out[i] = acc.get(i);
            return out;
          }
        }
      } else {
        Thread.sleep(1);
      }
    }
    return null;
  }

  static boolean isPong(byte[] s) {
    if (s == null) return false;
    for (byte b : s) if ((b & 0xFF) == 0x7F) return true;
    return false;
  }

  static String hex(byte[] b) {
    StringBuilder sb = new StringBuilder();
    for (byte x : b) sb.append(String.format("%02X ", x & 0xFF));
    return sb.toString().trim();
  }
}
