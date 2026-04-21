package org.chuck.deluge.xml;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.chuck.deluge.model.StepData;
import org.junit.jupiter.api.Test;

class DelugeNoteDataMapperTest {

  @Test
  void testEncodeRow() {
    List<StepData> row = new ArrayList<>();
    for (int i = 0; i < 16; i++) {
      row.add(StepData.empty());
    }
    // Set step 2 active (24 ticks)
    row.set(2, new StepData(true, 0.8f, 1.0f, 1.0f, 0));

    String hex = DelugeNoteDataMapper.encodeRow(row);

    // 24 dec = 18 hex
    // Length 1 step = 12 ticks = C hex
    assertTrue(hex.contains("000000180000000C4014"));
  }

  @Test
  void testDecodeRow() {
    String hex = "0x000000180000000C4014";
    List<StepData> row = DelugeNoteDataMapper.decodeRow(hex, 16);

    assertTrue(row.get(2).active());
    assertEquals(1.0f, row.get(2).gate(), 0.001);

    // Step 0 should be empty
    assertFalse(row.get(0).active());
  }
}
