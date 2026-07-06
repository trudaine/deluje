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

      // (3b) Poll mode: for -Dtap.poll=<seconds>, repeatedly capture and keep the loudest window
      // (so a note held on the DEVICE anytime during the run is caught — no tight timing). Saves
      // the loudest window to -Dtap.out (default target/tap_capture.txt) as one int32 per line.
      // (3a) Onset-synced mode: -Dtap.onset=true asks the firmware to arm at the next note onset
      // (Debug subcmd 5), so a single strike captures the bright ATTACK. Waits -Dtap.wait secs for
      // you to strike once, then reads back. Saves to -Dtap.out (default target/tap_capture.txt).
      if (Boolean.getBoolean("tap.onset")) {
        sendSysex(out, new int[] {CMD_DEBUG, 0x05}); // arm-on-next-note
        int waitSecs = Integer.getInteger("tap.wait", 8);
        System.out.println(
            "ARM-ON-NOTE set — STRIKE the note once now (waiting " + waitSecs + "s)…");
        long deadline = System.currentTimeMillis() + waitSecs * 1000L;
        DspTapCodec.Chunk probe = null;
        while (System.currentTimeMillis() < deadline) {
          Thread.sleep(300);
          probe = readChunk(out, in, 0);
          if (probe != null && probe.capturedCount() >= 4096) break;
        }
        Assumptions.assumeTrue(
            probe != null && probe.capturedCount() >= 4096,
            "no note captured (capturedCount="
                + (probe == null ? "null" : probe.capturedCount())
                + ") — did a note strike after arming?");
        int[] full = capture(out, in, probe.capturedCount());
        int pk = 0;
        long nz = 0;
        for (int v : full) {
          pk = Math.max(pk, Math.abs(v));
          if (v != 0) nz++;
        }
        System.out.printf(
            "ONSET CAPTURE (attack): %d samples, non-zero=%d, peak=%d (%.4f fs)%n",
            full.length, nz, pk, pk / 2.147483648e9);
        String outPath = System.getProperty("tap.out", "target/tap_capture.txt");
        StringBuilder sb = new StringBuilder();
        for (int v : full) sb.append(v).append('\n');
        java.nio.file.Files.writeString(java.nio.file.Path.of(outPath), sb.toString());
        System.out.println("  wrote " + full.length + " samples -> " + outPath);
        return;
      }

      int pollSecs = Integer.getInteger("tap.poll", 0);
      if (pollSecs > 0) {
        int[] best = new int[0];
        int bestPeak = 0;
        long deadline = System.currentTimeMillis() + pollSecs * 1000L;
        System.out.println("POLLING " + pollSecs + "s — hold a note on the Deluge now…");
        while (System.currentTimeMillis() < deadline) {
          sendSysex(out, new int[] {CMD_DEBUG, 0x03}); // arm
          Thread.sleep(120); // fill the 93 ms window (frozen until next arm)
          // Cheap probe: read only chunk 0 to see if this window has audio, before the full read.
          DspTapCodec.Chunk probe = readChunk(out, in, 0);
          if (probe == null) continue;
          int pk0 = 0;
          for (int v : probe.samples()) pk0 = Math.max(pk0, Math.abs(v));
          if (pk0 <= bestPeak) continue; // not louder than best-so-far — skip full read
          try {
            int[] w = capture(out, in, probe.capturedCount());
            int pk = 0;
            for (int v : w) pk = Math.max(pk, Math.abs(v));
            if (pk > bestPeak) {
              bestPeak = pk;
              best = w;
            }
          } catch (IllegalStateException dropped) {
            // A chunk dropped over MIDI — skip this window, keep polling.
          }
        }
        System.out.printf("POLL BEST: peak=%d (%.4f fs)%n", bestPeak, bestPeak / 2.147483648e9);
        String outPath = System.getProperty("tap.out", "target/tap_capture.txt");
        StringBuilder sb = new StringBuilder();
        for (int v : best) sb.append(v).append('\n');
        java.nio.file.Files.writeString(java.nio.file.Path.of(outPath), sb.toString());
        System.out.println("  wrote " + best.length + " samples -> " + outPath);
        Assumptions.assumeTrue(bestPeak > 0, "no audio captured — was a note held?");
        return;
      }

      // (3) Full capture (arm, optional MIDI note, read all chunks).
      boolean withNote = Boolean.getBoolean("tap.note"); // -Dtap.note=true to try a MIDI note
      int ch = Integer.getInteger("tap.ch", 0);
      int note = Integer.getInteger("tap.midinote", 60);
      boolean noteHeld = false;
      if (withNote) {
        // note-on FIRST, let it sustain, THEN arm — so audio is already flowing during capture
        // (disambiguates MIDI-routing failure from capture-timing).
        out.send(rawMsg(0x90 | (ch & 0x0F), note, 100));
        noteHeld = true;
        Thread.sleep(Integer.getInteger("tap.predelay", 300));
      }
      sendSysex(out, new int[] {CMD_DEBUG, 0x03}); // re-arm (capture the next 93 ms)
      Thread.sleep(150);
      int[] full = capture(out, in, c0.capturedCount());
      if (noteHeld) {
        out.send(rawMsg(0x80 | (ch & 0x0F), note, 0)); // note-off
      }
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
    for (int attempt = 0; attempt < 6; attempt++) {
      // Drain any stale bytes so a late reply from a prior request isn't mismatched to this chunk.
      drain(in);
      sendSysex(out, new int[] {CMD_DEBUG, 0x04, idx & 0x7F});
      byte[] reply = recvSysex(in, 600);
      if (DspTapCodec.isTapReply(reply)) {
        DspTapCodec.Chunk c = DspTapCodec.decode(reply);
        if (c.chunkIndex() == idx) return c; // ignore a stale reply for a different chunk
      }
      Thread.sleep(3);
    }
    return null;
  }

  /** Discard any pending input bytes (non-blocking). */
  static void drain(MidiIn in) {
    MidiMsg m = new MidiMsg();
    for (int i = 0; i < 64 && in.recv(m); i++) {
      m = new MidiMsg();
    }
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
