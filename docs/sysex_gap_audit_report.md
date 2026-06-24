# Deluge-Java: SysEx API Gap Audit & Workstation Feature Discovery

This document presents a comprehensive comparative code audit between the native C++ Deluge firmware's SysEx JSON storage command parser ([smsysex.cpp](file://<DelugeFirmwareRoot>/src/deluge/storage/smsysex.cpp)) and the Java workstation's file synchronization service ([DelugeFileSyncService.java](../../deluge/src/main/java/org/chuck/deluge/midi/DelugeFileSyncService.java)). It highlights the gaps between the hardware's capabilities and the Java implementation and outlines the technical specifications required to bridge them.

---

## 1. Feature-by-Feature Gap Matrix

| SysEx JSON Command | C++ Firmware Handler | Java Workstation Implementation | Status / Gap Details |
| :--- | :--- | :--- | :--- |
| **`open`** | `openFile()` (supports `write`, `path`, `date`, `time` attributes with auto-directory creation) | Implemented in `downloadFileBlocking()` and `uploadFileBlocking()`. | ✅ Full Parity |
| **`close`** | `closeFile()` (closes the target file descriptor) | Implemented. | ✅ Full Parity |
| **`read`** | `readBlock()` (reads up to 1024-byte chunks from file) | Implemented (uses 512-byte block chunks). | ✅ Full Parity |
| **`write`** | `writeBlock()` (writes up to 1024-byte chunks, unpacking 7-to-8 bit) | Implemented (uses 512-byte block chunks). | ✅ Full Parity |
| **`dir`** | `getDirEntries()` (returns paginated entries with `name`, `size`, `date`, `time`, and `attr` attributes) | Implemented in `listSongs()`, but **discards all metadata**, returning a flat `List<String>` of names. | ⚠️ **Partial Gap**: The Java client is blind to file sizes, modification dates, and folder structures. |
| **`mkdir`** | `createDirectory()` (creates folder with optional `date` and `time` timestamps) | **None** | ❌ **Total Gap**: Cannot create directories from the Java Workstation. |
| **`delete`** | `deleteFile()` (deletes file or folder via `f_unlink`) | **None** | ❌ **Total Gap**: Cannot delete files/folders from the Java Workstation. |
| **`rename`** | `rename()` (renames file or folder via `f_rename`) | **None** | ❌ **Total Gap**: Cannot rename files/folders from the Java Workstation. |
| **`copy`** | `copyFile()` (copies file natively on SD card, preserving `date`/`time` timestamps) | **None** | ❌ **Total Gap**: Cannot copy files natively on the Deluge from the Java Workstation. |
| **`move`** | `moveFile()` (moves file natively; falls back to copy+delete for cross-filesystem; preserves timestamps) | **None** | ❌ **Total Gap**: Cannot move files natively on the Deluge from the Java Workstation. |
| **`utime`** | `updateTime()` (explicitly sets file/directory FAT modification timestamps) | Used internally at the end of `uploadFileBlocking()`. | ⚠️ **Partial Gap**: Lacks a public, standalone API to update timestamps on demand. |
| **`session`** | `assignSession()` (allocates unique client session ID `sid` and `mid` boundaries to prevent collisions) | **None** (Defaults to session `0`) | ❌ **Total Gap**: Java client cannot co-exist with other MIDI clients concurrently without packet collision. |
| **`ping`** | `doPing()` (returns standard ping reply) | Implemented in `DelugeHwStatusPanel`. | ✅ Full Parity |

---

## 2. High-Value Architectural Upgrades

To bridge these gaps and elevate the Java workstation into a world-class workspace manager, three major upgrades should be introduced.

### 2.1. Rich Directory Metadata Model (`RemoteFileEntry`)
Instead of discarding the metadata parsed from the `dir` command, we should introduce a structured record `RemoteFileEntry` to capture the full file attributes:
```java
public record RemoteFileEntry(
    String name,
    long size,
    long lastModifiedMillis,
    boolean isDirectory,
    boolean isReadOnly,
    boolean isHidden
) {}
```
*   **FAT Timestamp Decoder**: Decodes the 16-bit FAT date and 16-bit FAT time fields returned by the hardware into standard Java epoch milliseconds.
*   **Attribute Mask Solver**: Decodes the FatFS attribute byte (`AM_DIR = 0x10`, `AM_RDO = 0x01`, `AM_HID = 0x02`, etc.) to flag directories and system files correctly.
*   **Upgraded API**: `listDirectory(String remotePath, FileListCallback callback)` returning a `List<RemoteFileEntry>`. This allows the Swing UI to render a full-featured detail grid showing file sizes and modified times, just like a local file explorer!

### 2.2. Public File System Management APIs
Expose the complete suite of file system operations inside `DelugeFileSyncService.java` using virtual thread blocking downcalls and non-blocking asynchronous wrappers:

```java
/** Create a remote directory with optional timestamp. */
public void createDirectoryAsync(String remotePath, long millis, FileOpCallback callback);

/** Delete a remote file or directory. */
public void deleteFileOrDirectoryAsync(String remotePath, FileOpCallback callback);

/** Rename a remote file or directory. */
public void renameFileOrDirectoryAsync(String fromPath, String toPath, FileOpCallback callback);

/** Copy a file natively on the Deluge SD card. */
public void copyFileAsync(String fromPath, String toPath, long millis, FileOpCallback callback);

/** Move a file natively on the Deluge SD card. */
public void moveFileAsync(String fromPath, String toPath, long millis, FileOpCallback callback);

/** Explicitly set a remote file or directory's modification timestamp. */
public void setFileTimestampAsync(String remotePath, long millis, FileOpCallback callback);
```

### 2.3. Stateful Session Negotiation Protocol
To ensure the Java Workstation can run concurrently alongside the React web application or other MIDI editors on the same machine without packet interference, the MIDI service should negotiate a session at startup:
1.  **Handshake**: Upon connection, send a session request SysEx message:
    ```json
    {"session": {"tag": "Deluge-Java Workstation"}}
    ```
2.  **Negotiation**: The Deluge assigns a unique session ID (`sid`) and a message ID base (`midBase`).
3.  **Encapsulation**: All subsequent SysEx headers sent from Java must pack the allocated `sid` into the second byte of the SysEx frame (replacing the default `0`). The `DelugeSysExManager` will filter incoming replies, only routing packets carrying the workstation's unique session ID to the file service, completely avoiding cross-application cross-talk!
