package org.deluge.midi;

import java.io.File;
import java.nio.file.Files;
import org.deluge.shadow.midi.MidiIn;
import org.deluge.shadow.midi.MidiMsg;
import org.deluge.shadow.midi.MidiOut;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Pushes a local preset/song file to the connected Deluge's SD card over USB (smSysex file API via
 * {@link DelugeFileSyncService}) — no card removal needed. Requires a Deluge on USB. Run:
 *
 * <pre>mvn test -Dtest=HardwareCardPushTest -Dpush.file=/path/to/x.XML -Dpush.remote=/SYNTHS/x.XML
 * </pre>
 *
 * <p>NOTE: this copies the file onto the card; you still LOAD/select it on the device (the firmware
 * has no input-injection SysEx).
 */
@Tag("hardware")
class HardwareCardPushTest {

  @Test
  void push() throws Exception {
    File file =
        new File(
            System.getProperty(
                "push.file", System.getProperty("user.home") + "/a/T28_drive_saturate.XML"));
    Assumptions.assumeTrue(file.isFile(), "no file to push: " + file);
    String remote = System.getProperty("push.remote", "/SYNTHS/" + file.getName());

    String[] outs = MidiOut.list();
    String[] ins = MidiIn.list();
    int oi = HardwareDspTapTest.findDeluge(outs);
    int ii = HardwareDspTapTest.findDeluge(ins);
    Assumptions.assumeTrue(oi >= 0 && ii >= 0, "no Deluge MIDI port");

    MidiOut out = new MidiOut();
    MidiIn in = new MidiIn();
    out.open(oi);
    in.open(ii);
    in.ignoreTypes(false, false, false);

    DelugeSysExManager mgr = new DelugeSysExManager();
    mgr.setMidiOut(out);

    // Receive pump: accumulate complete SysEx messages and feed them to the manager (mirrors
    // MidiService's input loop) so file-API replies resolve.
    final boolean[] running = {true};
    Thread pump =
        new Thread(
            () -> {
              MidiMsg m = new MidiMsg();
              java.io.ByteArrayOutputStream acc = new java.io.ByteArrayOutputStream();
              boolean inSx = false;
              while (running[0]) {
                if (in.recv(m)) {
                  byte[] d = m.getData();
                  if (d == null || d.length == 0) continue;
                  for (byte b : d) {
                    if ((b & 0xFF) == 0xF0) {
                      acc.reset();
                      inSx = true;
                    }
                    if (inSx) acc.write(b);
                    if ((b & 0xFF) == 0xF7 && inSx) {
                      inSx = false;
                      byte[] sx = acc.toByteArray();
                      if (Boolean.getBoolean("push.debug")) {
                        int nn = Math.min(sx.length, 12);
                        StringBuilder h = new StringBuilder();
                        for (int k = 0; k < nn; k++) h.append(String.format("%02X ", sx[k] & 0xFF));
                        System.out.println("  <- sysex[" + sx.length + "] " + h);
                      }
                      mgr.handleIncomingSysEx(sx);
                    }
                  }
                } else {
                  try {
                    Thread.sleep(1);
                  } catch (InterruptedException e) {
                    return;
                  }
                }
              }
            },
            "card-push-pump");
    pump.setDaemon(true);
    pump.start();

    try {
      mgr.negotiateSession("card-push").get(5, java.util.concurrent.TimeUnit.SECONDS);
      System.out.println("session " + mgr.getSessionId() + " — uploading " + file.getName());
      byte[] content = Files.readAllBytes(file.toPath());
      new DelugeFileSyncService(mgr).uploadFileBlocking(remote, content, file.lastModified());
      System.out.println("PUSHED " + content.length + " bytes -> " + remote);
    } finally {
      running[0] = false;
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
}
