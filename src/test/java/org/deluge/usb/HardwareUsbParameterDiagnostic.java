package org.deluge.usb;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.deluge.playback.PlaybackHandler;

/**
 * Diagnostic tool to test real-time USB parameter reading and writing.
 * Connects to the Deluge, reads the current value of LOCAL_LPF_RESONANCE (ID 84, Kind 0),
 * changes its value, reads it back, and reports the results.
 */
public class HardwareUsbParameterDiagnostic {

  private static final int PARAM_LPF_RESONANCE = 84;
  private static final int KIND_PATCHED = 0; // LOCAL

  public static void main(String[] args) {
    System.out.println("Starting Deluge USB Parameter Edit Diagnostic...");

    PlaybackHandler playbackHandler = new PlaybackHandler();
    DelugeUsbSyncService service = new DelugeUsbSyncService(playbackHandler);

    CountDownLatch readLatch = new CountDownLatch(1);
    CountDownLatch writeLatch = new CountDownLatch(1);
    CountDownLatch verifyLatch = new CountDownLatch(1);

    final int[] receivedVal = new int[1];

    service.addParameterListener(new DelugeUsbSyncService.UsbParameterListener() {
      @Override
      public void onParameterReceived(int paramKind, int paramID, int value) {
        System.out.printf("[USB] Received parameter update: Kind=%d, ID=%d, Value=%d%n", paramKind, paramID, value);
        if (paramKind == KIND_PATCHED && paramID == PARAM_LPF_RESONANCE) {
          receivedVal[0] = value;
          if (readLatch.getCount() > 0) {
            readLatch.countDown();
          } else if (verifyLatch.getCount() > 0) {
            verifyLatch.countDown();
          }
        }
      }
    });

    service.start();

    try {
      System.out.println("Waiting for Deluge device connection...");
      Thread.sleep(3000); // Wait for port to open and establish connection

      // 1. Read current value
      System.out.println("\n[1] Reading current LPF Resonance value...");
      service.requestParameterRead(KIND_PATCHED, PARAM_LPF_RESONANCE);
      if (readLatch.await(5, TimeUnit.SECONDS)) {
        int originalValue = receivedVal[0];
        System.out.printf("Original LPF Resonance value: %d%n", originalValue);

        // 2. Write new value
        int newValue = (originalValue > 0) ? originalValue / 2 : 0x10000000;
        System.out.printf("\n[2] Writing new LPF Resonance value: %d...%n", newValue);
        service.requestParameterWrite(KIND_PATCHED, PARAM_LPF_RESONANCE, newValue);
        
        // Wait a brief moment for update to apply
        Thread.sleep(500);

        // 3. Read and verify
        System.out.println("\n[3] Verifying new value by reading back...");
        service.requestParameterRead(KIND_PATCHED, PARAM_LPF_RESONANCE);
        if (verifyLatch.await(5, TimeUnit.SECONDS)) {
          System.out.printf("Read back value: %d%n", receivedVal[0]);
          if (receivedVal[0] == newValue) {
            System.out.println("\nSUCCESS: Parameter read/write cycle verified successfully!");
          } else {
            System.err.println("\nFAILURE: Read back value did not match written value.");
          }
        } else {
          System.err.println("Timeout waiting for value verification readback.");
        }
      } else {
        System.err.println("Timeout waiting for initial parameter read. Is the Deluge connected via USB and is a Synth clip active?");
      }
    } catch (InterruptedException e) {
      System.err.println("Diagnostic interrupted.");
    } finally {
      service.stop();
      System.exit(0);
    }
  }
}
