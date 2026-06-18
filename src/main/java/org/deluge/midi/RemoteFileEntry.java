package org.deluge.midi;

/**
 * Represents a file or directory entry on the remote Synthstrom Deluge's SD card, preserving all
 * metadata fields returned by the hardware's SysEx dir command.
 */
public record RemoteFileEntry(
    String name,
    long size,
    long lastModifiedMillis,
    boolean isDirectory,
    boolean isReadOnly,
    boolean isHidden) {}
