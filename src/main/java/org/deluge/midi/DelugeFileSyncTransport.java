package org.deluge.midi;

/**
 * Production {@link CardTransport} backed by the existing {@link DelugeFileSyncService} (chunked
 * smSysex file I/O over USB). Obtain the service from a connected {@code MidiService} via {@code
 * midiService.getFileSyncService()}.
 */
public class DelugeFileSyncTransport implements CardTransport {
  private final DelugeFileSyncService fileSync;

  public DelugeFileSyncTransport(DelugeFileSyncService fileSync) {
    this.fileSync = fileSync;
  }

  @Override
  public void upload(String remotePath, byte[] content) throws Exception {
    fileSync.uploadFileBlocking(remotePath, content);
  }

  @Override
  public byte[] download(String remotePath) throws Exception {
    return fileSync.downloadFileBlocking(remotePath);
  }
}
