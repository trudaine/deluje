package org.chuck.deluge.xml;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class DelugeHexMapperTest {

  @Test
  void testHexToFloat() {
    assertEquals(1.0f, DelugeHexMapper.hexToFloat("0x7FFFFFFF"), 0.0001f);
    assertEquals(-1.0f, DelugeHexMapper.hexToFloat("0x80000000"), 0.0001f);
    assertEquals(0.0f, DelugeHexMapper.hexToFloat("0x00000000"), 0.0001f);
    assertEquals(0.5f, DelugeHexMapper.hexToFloat("0x3FFFFFFF"), 0.0001f);
  }

  @Test
  void testFloatToHex() {
    assertEquals("0x7FFFFFFF", DelugeHexMapper.floatToHex(1.0f));
    assertEquals("0x80000000", DelugeHexMapper.floatToHex(-1.0f));
    assertEquals("0x00000000", DelugeHexMapper.floatToHex(0.0f));
  }

  @Test
  void testRoundTripHz() {
    // 440 Hz -> Hex -> Hz
    String hex = DelugeHexMapper.hzToHex(440.0f);
    float result = DelugeHexMapper.hexToHz(hex);
    assertEquals(440.0f, result, 0.1f);

    // Minimum
    assertEquals(20.0f, DelugeHexMapper.hexToHz(DelugeHexMapper.hzToHex(20.0f)), 0.1f);

    // Maximum
    assertEquals(20000.0f, DelugeHexMapper.hexToHz(DelugeHexMapper.hzToHex(20000.0f)), 0.1f);
  }
}
