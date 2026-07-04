# Deluge-Java: SysEx API Gap Audit & Workstation Feature Parity

This document presents a comparative code audit between the native C++ Deluge firmware's SysEx JSON storage command parser ([smsysex.cpp](file://<DelugeFirmwareRoot>/src/deluge/storage/smsysex.cpp)) and the Java workstation's file synchronization service ([DelugeFileSyncService.java](../src/main/java/org/deluge/midi/DelugeFileSyncService.java)). 

All previously identified gaps have been fully resolved, achieving complete feature parity between the hardware storage subsystem and the Java workstation.

---

## 1. Feature-by-Feature Gap Matrix

| SysEx JSON Command | C++ Firmware Handler | Java Workstation Implementation | Status / Details |
| :--- | :--- | :--- | :--- |
| **`open`** | `openFile()` (supports `write`, `path`, `date`, `time` attributes with auto-directory creation) | Implemented in `downloadFileBlocking()` and `uploadFileBlocking()`. | ✅ Full Parity |
| **`close`** | `closeFile()` (closes the target file descriptor) | Implemented. | ✅ Full Parity |
| **`read`** | `readBlock()` (reads up to 1024-byte chunks from file) | Implemented (uses 512-byte block chunks). | ✅ Full Parity |
| **`write`** | `writeBlock()` (writes up to 1024-byte chunks, unpacking 7-to-8 bit) | Implemented (uses 512-byte block chunks). | ✅ Full Parity |
| **`dir`** | `getDirEntries()` (returns paginated entries with `name`, `size`, `date`, `time`, and `attr` attributes) | Implemented via [RemoteFileEntry](../src/main/java/org/deluge/midi/RemoteFileEntry.java) metadata parsing. | ✅ Full Parity |
| **`mkdir`** | `createDirectory()` (creates folder with optional `date` and `time` timestamps) | Implemented in `createDirectoryBlocking()` and `createDirectoryAsync()`. | ✅ Full Parity |
| **`delete`** | `deleteFile()` (deletes file or folder via `f_unlink`) | Implemented in `deleteBlocking()` and `deleteAsync()`. | ✅ Full Parity |
| **`rename`** | `rename()` (renames file or folder via `f_rename`) | Implemented in `renameBlocking()` and `renameAsync()`. | ✅ Full Parity |
| **`copy`** | `copyFile()` (copies file natively on SD card, preserving `date`/`time` timestamps) | Implemented in `copyBlocking()` and `copyAsync()`. | ✅ Full Parity |
| **`move`** | `moveFile()` (moves file natively; falls back to copy+delete for cross-filesystem; preserves timestamps) | Implemented in `moveBlocking()` and `moveAsync()`. | ✅ Full Parity |
| **`utime`** | `updateTime()` (explicitly sets file/directory FAT modification timestamps) | Implemented in `setTimestampBlocking()` and `setTimestampAsync()`. | ✅ Full Parity |
| **`session`** | `assignSession()` (allocates unique client session ID `sid` and `mid` boundaries to prevent collisions) | Negotiated dynamically at connection in [MidiService.java](../src/main/java/org/deluge/midi/MidiService.java#L197). | ✅ Full Parity |
| **`ping`** | `doPing()` (returns standard ping reply) | Implemented in `DelugeHwStatusPanel`. | ✅ Full Parity |

---

## 2. Completed Architectural Upgrades

The Java Workstation leverages a high-fidelity remote storage layer to manage files on the physical Deluge:

### 2.1. Rich Directory Metadata Model (`RemoteFileEntry`)
The workstation decodes full directory metadata returned by the Deluge's `dir` command using the [RemoteFileEntry](../src/main/java/org/deluge/midi/RemoteFileEntry.java) record.
*   **FAT Timestamp Decoder**: Integrates `decodeFatDateTime` to decode the 16-bit FAT date and 16-bit FAT time fields returned by the hardware into standard Java epoch milliseconds.
*   **Attribute Mask Solver**: Decodes the FatFS attribute byte (`AM_DIR = 0x10`, `AM_RDO = 0x01`, `AM_HID = 0x02`, etc.) to correctly represent directories and file flags.
*   **Visual Integration**: The explorer tree in [HardwareSidebarTab.java](../src/main/java/org/deluge/ui/HardwareSidebarTab.java) utilizes this model to display files with correct icons, folder structures, and remote sizes.

### 2.2. Remote File System Management APIs
Exposes the complete suite of file system operations inside [DelugeFileSyncService.java](../src/main/java/org/deluge/midi/DelugeFileSyncService.java) utilizing virtual threads:
*   `createDirectoryAsync(String remotePath, long lastModifiedMillis, FileOpCallback callback)`
*   `deleteAsync(String remotePath, FileOpCallback callback)`
*   `renameAsync(String fromPath, String toPath, FileOpCallback callback)`
*   `copyAsync(String fromPath, String toPath, long lastModifiedMillis, FileOpCallback callback)`
*   `moveAsync(String fromPath, String toPath, long lastModifiedMillis, FileOpCallback callback)`
*   `setTimestampAsync(String remotePath, long lastModifiedMillis, FileOpCallback callback)`

### 2.3. Stateful Session Negotiation Protocol
To prevent packet collisions and allow concurrent operations when multiple MIDI clients are connected to the Deluge (e.g. Java Workstation and a web browser client), [DelugeSysExManager.java](../src/main/java/org/deluge/midi/DelugeSysExManager.java) negotiates a stateful session during connection:
1.  **Handshake**: Sends a JSON session request: `{"session": {"tag": "Deluge-Java Workstation"}}`.
2.  **ID Allocation**: Captures the unique session ID (`sid`) allocated by the hardware.
3.  **Encapsulation**: Automatically packs the allocated `sessionId` into the second byte of all outgoing SysEx frames, and filters incoming packets to ignore other clients' messages.
