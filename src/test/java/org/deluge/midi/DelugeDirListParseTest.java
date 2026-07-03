package org.deluge.midi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the parsing of the firmware {@code ^dir} reply (smsysex.cpp getDirEntries) that drives the
 * remote SD-card explorer. Directories (FatFS attr bit 0x10 = AM_DIR) must be excluded from the
 * file list but still counted for pagination, and the entry count must match so paging terminates.
 */
class DelugeDirListParseTest {

  // A representative reply: two song XMLs, one directory (SONG's .DATA folder), one more XML.
  private static final String DIR_REPLY =
      "{\"^dir\":{\"list\":["
          + "{\"name\":\"SONG001.XML\",\"size\":1234,\"date\":22887,\"time\":40000,\"attr\":32},"
          + "{\"name\":\"SONG002.XML\",\"size\":2345,\"date\":22887,\"time\":40001,\"attr\":32},"
          + "{\"name\":\"SONG001.DATA\",\"size\":0,\"date\":22887,\"time\":40002,\"attr\":16},"
          + "{\"name\":\"SONG003.XML\",\"size\":3456,\"date\":22887,\"time\":40003,\"attr\":32}"
          + "],\"err\":0}}";

  @Test
  void fileNames_excludeDirectories() {
    List<String> files = DelugeFileSyncService.getFileNamesFromList(DIR_REPLY);
    assertEquals(List.of("SONG001.XML", "SONG002.XML", "SONG003.XML"), files);
    assertFalse(files.contains("SONG001.DATA"), "AM_DIR (attr 0x10) entries are not files");
  }

  @Test
  void entryCount_includesDirectories_forPagination() {
    // 4 entries total (incl. the directory) so the pager advances offset past ALL of them.
    assertEquals(4, DelugeFileSyncService.countDirEntries(DIR_REPLY));
  }

  @Test
  void shortPage_isDetectedAsEnd() {
    // A page with fewer entries than MAX_DIR_LINES (25) signals the last page.
    assertTrue(DelugeFileSyncService.countDirEntries(DIR_REPLY) < 25);
  }

  @Test
  void emptyList_yieldsNoFilesAndZeroCount() {
    String empty = "{\"^dir\":{\"list\":[],\"err\":0}}";
    assertEquals(List.of(), DelugeFileSyncService.getFileNamesFromList(empty));
    assertEquals(0, DelugeFileSyncService.countDirEntries(empty));
  }
}
