package org.deluge.usb;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.deluge.playback.PlaybackHandler;

/**
 * Diagnostic tool to test the USB file listing and downloading features.
 * Connects to the physical Deluge via CDC serial, lists the "/SONGS" folder,
 * and attempts to download the first XML song file found.
 */
public class HardwareUsbFileTransferDiagnostic {

  public static void main(String[] args) {
    System.out.println("Starting Deluge USB File Transfer Diagnostic...");

    // Create a dummy playback handler
    PlaybackHandler playbackHandler = new PlaybackHandler();
    DelugeUsbSyncService service = new DelugeUsbSyncService(playbackHandler);

    CountDownLatch listLatch = new CountDownLatch(1);
    CountDownLatch downloadLatch = new CountDownLatch(1);
    ByteArrayOutputStream fileBuffer = new ByteArrayOutputStream();

    service.addFileListener(new DelugeUsbSyncService.UsbFileListener() {
      @Override
      public void onDirectoryListingReceived(List<DelugeUsbSyncService.FileEntry> entries) {
        System.out.println("\n[USB] Directory Listing Received:");
        for (DelugeUsbSyncService.FileEntry entry : entries) {
          System.out.printf("  %s %s%n", entry.isDirectory ? "[DIR] " : "[FILE]", entry.name);
        }
        listLatch.countDown();

        // Find the first XML file and download it
        String fileToDownload = null;
        for (DelugeUsbSyncService.FileEntry entry : entries) {
          if (!entry.isDirectory && entry.name.toLowerCase().endsWith(".xml")) {
            fileToDownload = entry.name;
            break;
          }
        }

        if (fileToDownload != null) {
          String fullPath = "/SONGS/" + fileToDownload;
          System.out.println("\n[USB] Requesting read for: " + fullPath);
          service.requestFileRead(fullPath);
        } else {
          System.out.println("\n[USB] No XML files found in /SONGS directory to download.");
          downloadLatch.countDown();
        }
      }

      @Override
      public void onFileChunkReceived(int chunkIndex, boolean isEof, byte[] data) {
        System.out.printf("[USB] Received file chunk: %d (size: %d, isEof: %b)%n", chunkIndex, data.length, isEof);
        try {
          fileBuffer.write(data);
          if (isEof) {
            System.out.println("[USB] File download completed successfully!");
            byte[] fileBytes = fileBuffer.toByteArray();
            System.out.println("[USB] Downloaded file content preview:");
            String preview = new String(fileBytes, 0, Math.min(fileBytes.length, 500), java.nio.charset.StandardCharsets.UTF_8);
            System.out.println(preview);
            if (fileBytes.length > 500) {
              System.out.println("... [truncated]");
            }
            
            // Save locally
            try (FileOutputStream fos = new FileOutputStream("downloaded_song.xml")) {
              fos.write(fileBytes);
              System.out.println("[USB] Saved downloaded file to 'downloaded_song.xml'");
            }
            downloadLatch.countDown();
          }
        } catch (Exception e) {
          System.err.println("[USB] Error writing chunk: " + e.getMessage());
        }
      }

      @Override
      public void onError(String error) {
        System.err.println("[USB] Error: " + error);
        listLatch.countDown();
        downloadLatch.countDown();
      }
    });

    service.start();

    try {
      System.out.println("Waiting for Deluge device connection...");
      // Wait for listing
      if (listLatch.await(10, TimeUnit.SECONDS)) {
        // Request directory listing
        System.out.println("Connected! Requesting listing for /SONGS...");
        service.requestDirectoryListing("/SONGS");
        
        // Wait for download
        if (downloadLatch.await(30, TimeUnit.SECONDS)) {
          System.out.println("Diagnostic completed.");
        } else {
          System.err.println("Timeout waiting for file download.");
        }
      } else {
        System.err.println("Timeout waiting for device connection. Is the Deluge connected via USB and turned on?");
      }
    } catch (InterruptedException e) {
      System.err.println("Diagnostic interrupted.");
    } finally {
      service.stop();
      System.exit(0);
    }
  }
}
