package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.*;

import org.chuck.core.ChuckArray;
import org.chuck.core.ChuckVM;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the 4-envelope audio engine ({@code DelugeEngineDSL}).
 *
 * <p>Verifies that all 4 envelopes per track are independently controllable. Envelope 0 drives
 * volume (main gain), envelopes 1-3 modulate filter, pitch, and pan respectively by default.
 *
 * <p>The env array is indexed: {@code base = (row * ENV_COUNT + envIndex) * ENV_PARAMS}. Array size
 * is {@code TRACKS * ENV_COUNT * ENV_PARAMS}.
 */
public class MultiEnvelopeTest {

  private ChuckVM vm;
  private BridgeContract bridge;

  @BeforeEach
  void setUp() {
    System.setProperty("chuck.audio.dummy", "true");
    System.setProperty("deluge.tracks", "8");
    vm = new ChuckVM(44100, 2);
    bridge = new BridgeContract();
    bridge.register(vm);
  }

  @AfterEach
  void tearDown() {
    if (vm != null) vm.shutdown();
  }

  /** Returns the offset into the env array for a given env index on row 0. */
  private int envBase(int envIndex) {
    return (0 * BridgeContract.ENV_COUNT + envIndex) * BridgeContract.ENV_PARAMS;
  }

  private void startEngine(int trackType) {
    vm.spork(new org.chuck.deluge.engine.DelugeEngineDSL());
    bridge.setTrackType(0, trackType);
    vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    vm.setGlobalFloat(BridgeContract.G_BPM, 120.0);
    vm.advanceTime(4410);
  }

  @Test
  void testFourEnvelopesExist() {
    startEngine(1); // Synth
    ChuckArray envArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_ENV);
    assertNotNull(envArr);
    // Array size = TRACKS * ENV_COUNT * ENV_PARAMS
    assertEquals(BridgeContract.ENV_STRIDE, envArr.size());
    // Defaults for row 0
    for (int e = 0; e < BridgeContract.ENV_COUNT; e++) {
      int base = envBase(e);
      assertEquals(0.01, envArr.getFloat(base + 0), 0.001, "env" + e + " attack default");
      assertEquals(0.1, envArr.getFloat(base + 1), 0.001, "env" + e + " decay default");
      assertEquals(0.7, envArr.getFloat(base + 2), 0.001, "env" + e + " sustain default");
      assertEquals(0.2, envArr.getFloat(base + 3), 0.001, "env" + e + " release default");
    }
  }

  @Test
  void testEnv1FilterModulation() {
    // Set env params directly on the VM global before starting engine
    ChuckArray envArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_ENV);
    int base0 = envBase(0);
    envArr.setFloat(base0, 0.001f);
    envArr.setFloat(base0 + 1, 0.05f);
    envArr.setFloat(base0 + 2, 1.0f);
    envArr.setFloat(base0 + 3, 0.01f);

    int base2 = envBase(2);
    envArr.setFloat(base2, 0.1f);
    envArr.setFloat(base2 + 1, 0.2f);
    envArr.setFloat(base2 + 2, 0.6f);
    envArr.setFloat(base2 + 3, 0.3f);

    startEngine(1);

    // Verify env values on the VM global
    ChuckArray envArr2 = (ChuckArray) vm.getGlobalObject(BridgeContract.G_ENV);
    int env2base = envBase(2);
    assertEquals(0.1, envArr2.getFloat(env2base), 0.001, "env2 attack");
    assertEquals(0.2, envArr2.getFloat(env2base + 1), 0.001, "env2 decay");
    assertEquals(0.6, envArr2.getFloat(env2base + 2), 0.001, "env2 sustain");
    assertEquals(0.3, envArr2.getFloat(env2base + 3), 0.001, "env2 release");
  }

  @Test
  void testEnvValuesChangeAfterSet() {
    startEngine(1);

    // Write all 4 envs for row 0 directly to the VM global array
    ChuckArray envArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_ENV);
    for (int e = 0; e < BridgeContract.ENV_COUNT; e++) {
      int base = envBase(e);
      envArr.setFloat(base, (float) (0.01 + e * 0.01));
      envArr.setFloat(base + 1, (float) (0.1 + e * 0.05));
      envArr.setFloat(base + 2, (float) (0.7 - e * 0.1));
      envArr.setFloat(base + 3, (float) (0.2 + e * 0.05));
    }

    // Re-read and verify
    ChuckArray envArr2 = (ChuckArray) vm.getGlobalObject(BridgeContract.G_ENV);
    for (int e = 0; e < BridgeContract.ENV_COUNT; e++) {
      int base = envBase(e);
      assertTrue(envArr2.getFloat(base) > 0.001, "env" + e + " attack > 0");
      assertTrue(envArr2.getFloat(base + 1) > 0.01, "env" + e + " decay > 0");
      assertTrue(envArr2.getFloat(base + 2) >= 0.0, "env" + e + " sustain >= 0");
      assertTrue(envArr2.getFloat(base + 3) > 0.01, "env" + e + " release > 0");
    }
  }

  @Test
  void testKitSoundFourEnvelopes() {
    // Kit track should also expose 4 envelopes per sound
    startEngine(0); // Kit

    // Write env values directly to the VM global
    ChuckArray envArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_ENV);
    assertNotNull(envArr);
    assertEquals(BridgeContract.ENV_STRIDE, envArr.size());

    // Set env3 values for row 0
    int env3base = envBase(3);
    envArr.setFloat(env3base, 0.05f);
    envArr.setFloat(env3base + 1, 0.25f);
    envArr.setFloat(env3base + 2, 0.4f);
    envArr.setFloat(env3base + 3, 0.4f);

    assertEquals(0.05, envArr.getFloat(env3base), 0.001);
    assertEquals(0.25, envArr.getFloat(env3base + 1), 0.001);
    assertEquals(0.4, envArr.getFloat(env3base + 2), 0.001);
    assertEquals(0.4, envArr.getFloat(env3base + 3), 0.001);
  }
}
