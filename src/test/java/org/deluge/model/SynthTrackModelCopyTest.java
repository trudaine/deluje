package org.deluge.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link SynthTrackModel#copyParametersFrom(SynthTrackModel)} and its nested configs
 * copy every parameter without dropping flags or sharing mutable references.
 */
class SynthTrackModelCopyTest {

  @Test
  void rawKnobConfigCopiesLfoRateKnobSetFlag() {
    RawKnobConfig src = new RawKnobConfig();
    src.setLfoRateKnobQ31(0, 0x12345678);
    src.setLfoRateKnobQ31(2, -0x01020304);

    RawKnobConfig dst = new RawKnobConfig();
    dst.setLfoRateKnobQ31(1, 0x11111111);
    dst.copyFrom(src);

    assertTrue(dst.isLfoRateKnobSet(0));
    assertFalse(dst.isLfoRateKnobSet(1));
    assertTrue(dst.isLfoRateKnobSet(2));
    assertFalse(dst.isLfoRateKnobSet(3));
    assertEquals(0x12345678, dst.getLfoRateKnobQ31(0));
    assertEquals(-0x01020304, dst.getLfoRateKnobQ31(2));
  }

  @Test
  void compressorConfigCopiesSidechainRawRates() {
    CompressorConfig src = new CompressorConfig();
    src.setSidechainAttackRaw(327244);
    src.setSidechainReleaseRaw(936);

    CompressorConfig dst = new CompressorConfig();
    dst.copyFrom(src);

    assertEquals(327244, dst.getSidechainAttackRaw());
    assertEquals(936, dst.getSidechainReleaseRaw());
  }

  @Test
  void copyParametersFromPreservesArpPortamentoRawKnobsSidechainRawAndDetachesKeyZones() {
    SynthTrackModel src = new SynthTrackModel("Preset");
    ArpModel customArp =
        ArpModel.defaultConfig().toBuilder()
            .active(true)
            .mode("RANDOM")
            .numOctaves(3)
            .gate(0.75f)
            .syncLevel(8)
            .build();
    src.setArp(customArp);
    src.setArpRateQ31(0x20000000);
    src.setPortamento(0.42f);
    src.setPortamentoQ31(-0x10000000);
    src.getRawKnobs().setLfoRateKnobQ31(1, 0x05060708);
    src.getRawKnobs().setEnvKnobsQ31(0, 10, 20, 30, 40);
    src.setSidechainAttackRaw(327244);
    src.setSidechainReleaseRaw(936);

    KeyZone zone = new KeyZone();
    zone.samplePath = "SAMPLES/KICK.WAV";
    zone.minPitch = 36;
    zone.maxPitch = 48;
    zone.transpose = -12;
    zone.cents = 7;
    src.getKeyZones().getOsc1Zones().add(zone);

    SynthTrackModel dst = new SynthTrackModel("LiveTrack");
    dst.copyParametersFrom(src);

    assertEquals(customArp, dst.getArp());
    assertEquals(0x20000000, dst.getArpRateQ31());
    assertEquals(0.42f, dst.getPortamento(), 1e-6f);
    assertEquals(-0x10000000, dst.getPortamentoQ31());

    assertTrue(dst.getRawKnobs().isLfoRateKnobSet(1));
    assertEquals(0x05060708, dst.getRawKnobs().getLfoRateKnobQ31(1));
    assertTrue(dst.getRawKnobs().isEnvKnobSet(0));
    assertEquals(30, dst.getRawKnobs().getEnvSustainKnobQ31(0));

    assertEquals(327244, dst.getSidechainAttackRaw());
    assertEquals(936, dst.getSidechainReleaseRaw());

    assertEquals(1, dst.getKeyZones().getOsc1Zones().size());
    KeyZone copiedZone = dst.getKeyZones().getOsc1Zones().get(0);
    assertNotSame(zone, copiedZone);
    assertEquals("SAMPLES/KICK.WAV", copiedZone.samplePath);
    assertEquals(-12, copiedZone.transpose);
    assertEquals(7, copiedZone.cents);

    // Mutating the source zone after copy must not affect dst.
    zone.transpose = 24;
    zone.samplePath = "SAMPLES/SNARE.WAV";
    assertEquals(-12, copiedZone.transpose);
    assertEquals("SAMPLES/KICK.WAV", copiedZone.samplePath);
  }

  @Test
  void setEnvClearsStaleRawKnobWhenAdsrChangesButKeepsItWhenOnlyTargetChanges() {
    SynthTrackModel track = new SynthTrackModel("EnvTest");
    track.setEnv(0, new EnvelopeModel(0.01f, 0.1f, 0.7f, 0.2f, "NONE", 0.0f));
    track.getRawKnobs().setEnvKnobsQ31(0, 100, 200, 300, 400);
    track.getRawKnobs().setRawParamKnob(org.deluge.firmware2.Param.LOCAL_ENV_0_SUSTAIN, 300);

    // Changing only target/amount keeps the raw Q31 ADSR knobs intact.
    track.setEnv(0, new EnvelopeModel(0.01f, 0.1f, 0.7f, 0.2f, "FILTER", 0.5f));
    assertTrue(track.getRawKnobs().isEnvKnobSet(0));
    assertTrue(
        track
            .getRawKnobs()
            .getRawParamKnobs()
            .containsKey(org.deluge.firmware2.Param.LOCAL_ENV_0_SUSTAIN));

    // Changing attack/decay/sustain/release clears the stale raw Q31 override.
    track.setEnv(0, new EnvelopeModel(0.25f, 0.1f, 0.7f, 0.2f, "FILTER", 0.5f));
    assertFalse(track.getRawKnobs().isEnvKnobSet(0));
    assertFalse(
        track
            .getRawKnobs()
            .getRawParamKnobs()
            .containsKey(org.deluge.firmware2.Param.LOCAL_ENV_0_SUSTAIN));
  }
}
