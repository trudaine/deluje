package org.chuck.deluge.firmware.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Set;
import org.chuck.deluge.firmware.model.InstrumentClip;
import org.chuck.deluge.firmware.model.Song;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

/**
 * Stopgap guard for the bridge's fragile seam (pending the full {@code FirmwareSound}-façade
 * refactor): {@code FirmwareSound.syncParamsToFw2} manually copies model state into {@code
 * fw2Sound}, with no compiler check that every render-read field is covered. Three bugs this
 * session came from exactly that — most directly {@code voicePriority}, a {@code fw2Sound} scalar
 * the bridge simply never wrote, leaving it stuck at its default.
 *
 * <p>This does two things:
 *
 * <ol>
 *   <li><b>Census</b> — every scalar/enum (and primitive-array / enum-array) field of {@code
 *       firmware2.Sound} must be explicitly classified as {@link #BRIDGE_SYNCED} (model-driven) or
 *       {@link #RUNTIME_OR_DERIVED} (transport/runtime/derived, not directly model-driven). A NEW
 *       such field fails this test until someone classifies it — forcing the "do I need to sync
 *       this?" decision that was missed for {@code voicePriority}.
 *   <li><b>Propagation</b> — a representative set of model knobs are set to distinctive non-default
 *       values, built through the bridge, and asserted to actually reach {@code fw2Sound}.
 * </ol>
 */
class Fw2SyncCompletenessTest {

  /** Scalar/enum fw2Sound fields the bridge is responsible for driving from the model. */
  private static final Set<String> BRIDGE_SYNCED =
      Set.of(
          "synthMode",
          "customLfoWave",
          "oscTypes",
          "lpfMode",
          "hpfMode",
          "filterRoute",
          "numUnison",
          "unisonDetune",
          "unisonStereoSpread",
          "volumeNeutralValueForUnison",
          "modulator1ToModulator0",
          "fmRatio1",
          "fmRatio2",
          "oscillatorSync",
          "clippingAmount",
          "portamentoKnob",
          "oscRetriggerPhase",
          "modulatorRetriggerPhase",
          "modulatorTranspose",
          "polyphonic",
          "maxPolyphony",
          "voicePriority",
          "sampleStartPoint",
          "sampleEndPoint",
          "sampleLoopMode",
          "sampleLoopStart",
          "sampleReverse",
          "sampleTimestretch",
          "arpPhaseIncrement",
          "sidechainSend",
          "delayUserRate",
          "delayFeedbackAmount",
          "delayPingPong",
          "delayAnalog",
          "modFXRateIncrement",
          "modFXDepth",
          "modFXOffset",
          "modFXFeedback",
          "modFXType",
          "bitcrushParam",
          "srrParam",
          "eqBassParam",
          "eqTrebleParam",
          "currentBpm",
          "patchedParamValues");

  /** fw2Sound fields that are NOT directly model-driven (runtime, transport, or derived). */
  private static final Set<String> RUNTIME_OR_DERIVED =
      Set.of(
          "lastNoteCode", // runtime: last triggered note
          "timePerInternalTickInverse", // transport clock (documented gap; synced LFO unreachable)
          "timeStartedSkippingRenderingLFO", // runtime: skip/resume bookkeeping
          "unisonPan", // derived by setupUnisonStereoSpread() from unisonStereoSpread
          "monophonicExpressionValues", // runtime MPE smoothing state
          "expressionSourcesChangedAtSynthLevel", // runtime MPE flag
          "shouldLimitDelayFeedback", // fixed default, not model-driven
          "globalSourceValues"); // runtime modulation output (LFO/sidechain), recomputed per block

  @Test
  void everyScalarFw2SoundFieldIsClassified() {
    java.util.List<String> unclassified = new java.util.ArrayList<>();
    for (Field f : org.chuck.deluge.firmware2.Sound.class.getDeclaredFields()) {
      if (Modifier.isStatic(f.getModifiers())) continue;
      Class<?> t = f.getType();
      Class<?> base = t.isArray() ? t.getComponentType() : t;
      // Scope: primitives + enums (and their arrays). Object/collection fields (Lfo[], Sample[],
      // Sidechain, …) are out of scope for this scalar guard.
      if (!(base.isPrimitive() || base.isEnum())) continue;
      String name = f.getName();
      if (!BRIDGE_SYNCED.contains(name) && !RUNTIME_OR_DERIVED.contains(name)) {
        unclassified.add(name);
      }
    }
    if (!unclassified.isEmpty()) {
      fail(
          "Unclassified firmware2.Sound scalar field(s): "
              + unclassified
              + ". For each: if the bridge must drive it from the model, add it to BRIDGE_SYNCED"
              + " *and* to FirmwareSound.syncParamsToFw2 (this is the voicePriority bug class);"
              + " if it is runtime/transport/derived, add it to RUNTIME_OR_DERIVED.");
    }
  }

  @Test
  void keyModelFieldsPropagateToFw2Sound() throws Exception {
    SynthTrackModel synth = new SynthTrackModel("propagate");
    synth.setVoicePriority(2);
    synth.setMaxVoiceCount(3);
    synth.setPolyphony(SynthTrackModel.PolyphonyMode.MONO);
    synth.addClip(new org.chuck.deluge.model.ClipModel("c", 1, 16));

    ProjectModel project = new ProjectModel();
    project.addTrack(synth);
    Song song = FirmwareFactory.createSong(project);
    FirmwareSound sound = (FirmwareSound) ((InstrumentClip) song.clips.get(0)).sound;
    sound.syncParamsToFw2();

    assertEquals(2, sound.fw2Sound.voicePriority, "voicePriority must reach fw2Sound");
    assertEquals(3, sound.fw2Sound.maxPolyphony, "maxPolyphony must reach fw2Sound");
    assertEquals(
        org.chuck.deluge.firmware2.Sound.PolyphonyMode.MONO,
        sound.fw2Sound.polyphonic,
        "polyphony must reach fw2Sound");
    // patchedParamValues is reallocated and populated each sync (not left null/empty).
    assertTrue(
        sound.fw2Sound.patchedParamValues != null && sound.fw2Sound.patchedParamValues.length > 0,
        "patchedParamValues must be populated by sync");
  }
}
