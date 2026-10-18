/*
 * Copyright © 2026 Synthstrom Audible Limited
 *
 * This file is part of The Synthstrom Audible Deluge Workstation.
 */

package org.deluge.usb;

import com.fazecast.jSerialComm.SerialPort;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.playback.PlaybackHandler;

/**
 * Service that connects to the physical Deluge workstation via USB CDC Virtual Serial port.
 * Receives real-time playhead sync packets (tick count, BPM, and play state) and syncs the Java
 * workstation engine in real-time. Also supports file system listing and file reading over USB.
 */
public class DelugeUsbSyncService {
  private static final Logger LOGGER = Logger.getLogger(DelugeUsbSyncService.class.getName());
  private static final int USB_VID = 0x16D0;
  private static final int USB_PID = 0x0CE2;

  public static class FileEntry {
    public final boolean isDirectory;
    public final String name;

    public FileEntry(boolean isDirectory, String name) {
      this.isDirectory = isDirectory;
      this.name = name;
    }
  }

  public interface UsbFileListener {
    void onDirectoryListingReceived(List<FileEntry> entries);

    void onFileChunkReceived(int chunkIndex, boolean isEof, byte[] data);

    void onError(String error);
  }

  public interface UsbParameterListener {
    void onParameterReceived(int paramKind, int paramID, int value);
  }

  private final PlaybackHandler playbackHandler;
  private volatile boolean running = false;
  private Thread thread;
  private SerialPort serialPort;
  private final List<UsbFileListener> fileListeners = new CopyOnWriteArrayList<>();
  private final List<UsbParameterListener> parameterListeners = new CopyOnWriteArrayList<>();

  public DelugeUsbSyncService(PlaybackHandler playbackHandler) {
    this.playbackHandler = playbackHandler;
  }

  public void addFileListener(UsbFileListener listener) {
    fileListeners.add(listener);
  }

  public void removeFileListener(UsbFileListener listener) {
    fileListeners.remove(listener);
  }

  private void notifyDirectoryListing(List<FileEntry> entries) {
    for (UsbFileListener listener : fileListeners) {
      listener.onDirectoryListingReceived(entries);
    }
  }

  private void notifyFileChunk(int chunkIndex, boolean isEof, byte[] data) {
    for (UsbFileListener listener : fileListeners) {
      listener.onFileChunkReceived(chunkIndex, isEof, data);
    }
  }

  private void notifyError(String error) {
    for (UsbFileListener listener : fileListeners) {
      listener.onError(error);
    }
  }

  public void addParameterListener(UsbParameterListener listener) {
    parameterListeners.add(listener);
  }

  public void removeParameterListener(UsbParameterListener listener) {
    parameterListeners.remove(listener);
  }

  private void notifyParameterReceived(int paramKind, int paramID, int value) {
    for (UsbParameterListener listener : parameterListeners) {
      listener.onParameterReceived(paramKind, paramID, value);
    }
  }

  public synchronized void requestDirectoryListing(String path) {
    sendPacket(0x02, path.getBytes(StandardCharsets.UTF_8));
  }

  public synchronized void requestFileRead(String filePath) {
    sendPacket(0x04, filePath.getBytes(StandardCharsets.UTF_8));
  }

  public synchronized void requestParameterWrite(int paramKind, int paramID, int value) {
    byte[] payload = new byte[7];
    payload[0] = (byte) paramKind;
    payload[1] = (byte) (paramID & 0xFF);
    payload[2] = (byte) ((paramID >> 8) & 0xFF);
    payload[3] = (byte) (value & 0xFF);
    payload[4] = (byte) ((value >> 8) & 0xFF);
    payload[5] = (byte) ((value >> 16) & 0xFF);
    payload[6] = (byte) ((value >> 24) & 0xFF);
    sendPacket(0x09, payload);
  }

  public synchronized void requestParameterRead(int paramKind, int paramID) {
    byte[] payload = new byte[3];
    payload[0] = (byte) paramKind;
    payload[1] = (byte) (paramID & 0xFF);
    payload[2] = (byte) ((paramID >> 8) & 0xFF);
    sendPacket(0x0A, payload);
  }

  private void sendPacket(int cmd, byte[] payload) {
    if (serialPort == null || !serialPort.isOpen()) {
      return;
    }
    int len = payload != null ? payload.length : 0;
    byte[] msg = new byte[len + 6];
    msg[0] = (byte) 0xDE;
    msg[1] = (byte) 0x4C;
    msg[2] = (byte) cmd;
    msg[3] = (byte) (len & 0xFF);
    msg[4] = (byte) ((len >> 8) & 0xFF);
    if (len > 0) {
      System.arraycopy(payload, 0, msg, 5, len);
    }
    int checksum = cmd ^ (len & 0xFF) ^ ((len >> 8) & 0xFF);
    if (payload != null) {
      for (int i = 0; i < len; i++) {
        checksum ^= payload[i];
      }
    }
    msg[5 + len] = (byte) checksum;
    serialPort.writeBytes(msg, msg.length);
  }

  public synchronized void start() {
    if (running) return;
    running = true;
    thread = new Thread(this::runLoop, "DelugeUsbSync-Thread");
    thread.setDaemon(true);
    thread.start();
  }

  public synchronized void stop() {
    running = false;
    if (thread != null) {
      thread.interrupt();
    }
    closePort();
  }

  private void closePort() {
    if (serialPort != null && serialPort.isOpen()) {
      serialPort.closePort();
    }
    serialPort = null;
  }

  private void runLoop() {
    while (running) {
      try {
        if (serialPort == null || !serialPort.isOpen()) {
          serialPort = findDelugePort();
          if (serialPort == null) {
            Thread.sleep(2000);
            continue;
          }
          serialPort.setBaudRate(115200);
          serialPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 1000, 0);
          if (!serialPort.openPort()) {
            serialPort = null;
            Thread.sleep(2000);
            continue;
          }
          LOGGER.fine(
              "[USB] Successfully connected to Deluge CDC Serial port: "
                  + serialPort.getSystemPortName());
        }

        InputStream in = serialPort.getInputStream();
        readPacketsFromStream(in);
        if (running) {
          LOGGER.fine("[USB] Deluge disconnected (stream closed).");
          closePort();
        }
      } catch (InterruptedException e) {
        break;
      } catch (Exception e) {
        LOGGER.warning("[USB] Error in serial reading loop: " + e.getMessage());
        closePort();
        try {
          Thread.sleep(2000);
        } catch (InterruptedException ie) {
          break;
        }
      }
    }
  }

  /**
   * Reads framed {@code 0xDE 0x4C} packets from {@code in} until EOF or {@link #stop()} is called.
   * Package-private so tests can exercise stream resynchronization and bounds checks directly.
   */
  void readPacketsFromStream(InputStream in) throws Exception {
    byte[] header = new byte[5];
    while (thread == null || running) {
      if (readFully(in, header, 0, 5) < 0) {
        return;
      }

      while (header[0] != (byte) 0xDE || header[1] != (byte) 0x4C) {
        if (!resyncHeader(in, header)) {
          return;
        }
      }

      int cmd = header[2] & 0xFF;
      int len = (header[3] & 0xFF) | ((header[4] & 0xFF) << 8);

      if (len < 0 || len > 1024) {
        continue;
      }

      byte[] payload = new byte[len];
      if (readFully(in, payload, 0, len) < 0) {
        return;
      }

      int checksumByte = in.read();
      if (checksumByte < 0) {
        return;
      }

      int calculatedChecksum = cmd ^ (len & 0xFF) ^ ((len >> 8) & 0xFF);
      for (byte b : payload) {
        calculatedChecksum ^= (b & 0xFF);
      }

      if ((checksumByte & 0xFF) != (calculatedChecksum & 0xFF)) {
        // Checksum error, ignore packet
        continue;
      }

      if (cmd == 1) { // Playhead Sync
        if (payload.length < 9) {
          continue;
        }
        int tick =
            ((payload[0] & 0xFF)
                | ((payload[1] & 0xFF) << 8)
                | ((payload[2] & 0xFF) << 16)
                | ((payload[3] & 0xFF) << 24));

        int bpm =
            ((payload[4] & 0xFF)
                | ((payload[5] & 0xFF) << 8)
                | ((payload[6] & 0xFF) << 16)
                | ((payload[7] & 0xFF) << 24));

        int playState = payload[8] & 0xFF;

        handleSync(tick, bpm, playState);
      } else if (cmd == 3) { // File List Response
        if (payload.length < 1) {
          continue;
        }
        int status = payload[0] & 0xFF;
        if (status != 0) {
          notifyError("Failed to read directory listing.");
        } else {
          List<FileEntry> entries = new ArrayList<>();
          int idx = 1;
          while (idx < payload.length) {
            char type = (char) payload[idx++];
            int start = idx;
            while (idx < payload.length && payload[idx] != 0) {
              idx++;
            }
            if (idx > start) {
              String name = new String(payload, start, idx - start, StandardCharsets.UTF_8);
              entries.add(new FileEntry(type == 'd', name));
            }
            idx++;
          }
          notifyDirectoryListing(entries);
        }
      } else if (cmd == 5) { // Read File Data Chunk
        if (payload.length < 5) {
          continue;
        }
        int status = payload[0] & 0xFF;
        int chunkIndex = (payload[1] & 0xFF) | ((payload[2] & 0xFF) << 8);
        int chunkSize = (payload[3] & 0xFF) | ((payload[4] & 0xFF) << 8);

        if (status == 2) {
          notifyError("Failed to read file chunk (error on Deluge).");
        } else if (chunkSize >= 0 && payload.length >= 5 + chunkSize) {
          byte[] fileData = new byte[chunkSize];
          System.arraycopy(payload, 5, fileData, 0, chunkSize);
          boolean isEof = (status == 1);
          notifyFileChunk(chunkIndex, isEof, fileData);
        }
      } else if (cmd == 11) { // Parameter Value Response (0x0B)
        if (payload.length >= 7) {
          int paramKind = payload[0] & 0xFF;
          int paramID = (payload[1] & 0xFF) | ((payload[2] & 0xFF) << 8);
          int value =
              (payload[3] & 0xFF)
                  | ((payload[4] & 0xFF) << 8)
                  | ((payload[5] & 0xFF) << 16)
                  | ((payload[6] & 0xFF) << 24);
          notifyParameterReceived(paramKind, paramID, value);
        }
      }
    }
  }

  private boolean resyncHeader(InputStream in, byte[] header) throws Exception {
    // Check if the 0xDE 0x4C magic already starts inside header[1..3]
    for (int offset = 1; offset <= 3; offset++) {
      if (header[offset] == (byte) 0xDE && header[offset + 1] == (byte) 0x4C) {
        int remaining = 5 - offset;
        System.arraycopy(header, offset, header, 0, remaining);
        return readFully(in, header, remaining, offset) >= 0;
      }
    }

    // Otherwise scan the stream for 0xDE 0x4C (preserving a trailing 0xDE at header[4])
    boolean prevWasDe = (header[4] == (byte) 0xDE);
    int b;
    while ((b = in.read()) >= 0) {
      if (prevWasDe && b == 0x4C) {
        header[0] = (byte) 0xDE;
        header[1] = (byte) 0x4C;
        return readFully(in, header, 2, 3) >= 0;
      }
      prevWasDe = (b == 0xDE);
    }
    return false;
  }

  private int readFully(InputStream in, byte[] buf, int offset, int len) throws Exception {
    int total = 0;
    while (total < len) {
      int read = in.read(buf, offset + total, len - total);
      if (read < 0) return -1;
      total += read;
    }
    return total;
  }

  private SerialPort findDelugePort() {
    for (SerialPort port : SerialPort.getCommPorts()) {
      if (port.getVendorID() == USB_VID && port.getProductID() == USB_PID) {
        return port;
      }
      String desc = port.getPortDescription();
      if (desc != null && (desc.toLowerCase().contains("deluge"))) {
        return port;
      }
    }
    return null;
  }

  private void handleSync(int remoteTick, int remoteBpm, int playState) {
    ProjectModel project = playbackHandler.getProject();
    if (project == null) return;

    float bpm = remoteBpm / 1000.0f;
    project.setTempoBPM(bpm);

    if (playState == 0) {
      if (playbackHandler.isPlaying()) {
        playbackHandler.stop();
      }
    } else {
      if (!playbackHandler.isPlaying()) {
        playbackHandler.start();
      }

      int localTick = playbackHandler.lastSwungTickActioned;
      int diff = remoteTick - localTick;
      if (diff > 0 && diff < 96) {
        playbackHandler.advanceTicks(diff);
      } else if (diff != 0) {
        playbackHandler.lastSwungTickActioned = remoteTick;
        for (ClipModel clip : project.getClips()) {
          clip.lastProcessedPos = remoteTick;
        }
      }
    }
  }
}
