package org.deluge.xml;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.deluge.model.ClipModel;
import org.deluge.model.Drum;
import org.deluge.model.EnvelopeModel;
import org.deluge.model.KitTrackModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SoundDrum;
import org.deluge.model.StepData;
import org.deluge.model.SynthTrackModel;
import org.deluge.model.TrackModel;
import org.deluge.project.ProjectSerializer;
import org.junit.jupiter.api.Test;

/**
 * End-to-end integration and sanity test for the XML Loader (DelugeXmlParser) and XML Saver
 * (ProjectSerializer). Verifies roundtrip semantic preservation for all known project/synth XML
 * files and programmatically generated projects.
 */
public class XmlRoundtripTest {

  private static final float EPSILON = 0.01f;

  @Test
  void testExistingXmlFilesRoundtrip() throws Exception {
    // Locate all XML files in the resources directories
    List<Path> xmlFiles = new ArrayList<>();

    Path mainResources = Path.of("src/main/resources");
    if (Files.exists(mainResources)) {
      xmlFiles.addAll(findXmlFiles(mainResources));
    }

    Path testResources = Path.of("src/test/resources");
    if (Files.exists(testResources)) {
      xmlFiles.addAll(findXmlFiles(testResources));
    }

    System.out.printf(
        "[Test] Found %d XML files to audit for loader/saver roundtrip.%n", xmlFiles.size());
    assertFalse(xmlFiles.isEmpty(), "No XML files found to audit!");

    List<String> discrepancies = new ArrayList<>();
    File tempDir = Files.createTempDirectory("deluge_roundtrip_audit").toFile();
    tempDir.deleteOnExit();

    for (Path xmlPath : xmlFiles) {
      File originalFile = xmlPath.toFile();
      // Skip some specific skeleton/blank files if necessary, but audit all by default!
      System.out.printf("[Test] Auditing: %s%n", originalFile.getName());

      ProjectModel originalModel;
      try (FileInputStream fis = new FileInputStream(originalFile)) {
        originalModel = DelugeXmlParser.parseSong(fis, originalFile.getName());
      } catch (Exception e) {
        System.err.printf(
            "[WARN] Failed to parse original file %s: %s%n",
            originalFile.getName(), e.getMessage());
        continue;
      }

      if (originalModel == null) {
        discrepancies.add(originalFile.getName() + ": Parsed as null!");
        continue;
      }

      // Save to temp XML
      File savedFile = new File(tempDir, "saved_" + originalFile.getName());
      try {
        ProjectSerializer.save(originalModel, savedFile);
      } catch (Exception e) {
        discrepancies.add(originalFile.getName() + ": Failed to save! Error: " + e.getMessage());
        continue;
      }

      // Reload from temp XML
      ProjectModel reloadedModel;
      try (FileInputStream fis = new FileInputStream(savedFile)) {
        reloadedModel = DelugeXmlParser.parseSong(fis, savedFile.getName());
      } catch (Exception e) {
        discrepancies.add(
            originalFile.getName() + ": Failed to reload saved XML! Error: " + e.getMessage());
        continue;
      }

      // Semantic comparison
      compareProjects(originalFile.getName(), originalModel, reloadedModel, discrepancies);
    }

    // Report results
    if (!discrepancies.isEmpty()) {
      System.err.println("\n=== XML LOADER/SAVER ROUNDTRIP AUDIT DISCREPANCIES ===");
      for (String d : discrepancies) {
        System.err.println(" - " + d);
      }
      System.err.println("======================================================\n");
      fail(
          "Roundtrip audit failed with "
              + discrepancies.size()
              + " discrepancies! See logs above.");
    } else {
      System.out.println(
          "[Test] All XML files audited successfully with ZERO roundtrip discrepancies!");
    }
  }

  @Test
  void testProgrammaticComplexProjectRoundtrip() throws Exception {
    // 1. Programmatically construct a highly customized project from scratch
    ProjectModel original = new ProjectModel();
    original.setBpm(133.5f);
    original.setSwing(0.58f);

    // ── Track 1: Synth Track ──
    SynthTrackModel synth = new SynthTrackModel("LEAD_SYNTH");
    synth.setOsc1Type("SQUARE");
    synth.setOsc2Type("SAWTOOTH");
    synth.setLpfFreq(4500.0f);
    synth.setLpfRes(0.45f);
    synth.setFilterMode(org.deluge.model.FilterMode.LADDER_24);
    synth.setLpfMorph(0.35f);
    synth.setFilterDrive(0.2f);
    synth.setVolume(0.75f);

    // Set custom envelopes
    synth.setEnv(0, new EnvelopeModel(0.05f, 0.25f, 0.6f, 0.4f, "NONE", 0.0f));
    synth.setEnv(1, new EnvelopeModel(0.01f, 0.15f, 0.0f, 0.2f, "NONE", 0.0f));

    // Add a custom note clip (triplet mode)
    ClipModel synthClip = new ClipModel("SYNTH_CLIP", 8, 12);
    synthClip.setTripletMode(true);
    synthClip.setPlayDirection(ClipModel.PlayDirection.REVERSE);
    // Program a couple of custom steps
    synthClip.setStep(0, 0, StepData.of(true, 0.9f, 1.5f, 1.0f, 60)); // C5
    synthClip.setStep(2, 4, StepData.of(true, 0.7f, 0.5f, 1.0f, 64)); // E5
    synth.addClip(synthClip);
    original.addTrack(synth);

    // ── Track 2: Kit Track ──
    KitTrackModel kit = new KitTrackModel("DRUMS_KIT");

    SoundDrum kick = new SoundDrum("808_KICK");
    kick.setSamplePath("SAMPLES/DRUMS/Kick/808 Kick.wav");
    kick.setVolume(0.85f);
    kick.setPan(-0.1f);
    kit.addDrum(kick);

    SoundDrum snare = new SoundDrum("808_SNARE");
    snare.setSamplePath("SAMPLES/DRUMS/Snare/808 Snare.wav");
    snare.setVolume(0.80f);
    snare.setPan(0.2f);
    kit.addDrum(snare);

    // Add a 16-step kit clip
    ClipModel kitClip = new ClipModel("KIT_CLIP", 2, 16);
    kitClip.setStep(0, 0, StepData.of(true, 1.0f, 0.8f, 1.0f, 0)); // Kick step 0
    kitClip.setStep(0, 8, StepData.of(true, 0.9f, 0.8f, 1.0f, 0)); // Kick step 8
    kitClip.setStep(1, 4, StepData.of(true, 0.95f, 0.6f, 1.0f, 0)); // Snare step 4
    kitClip.setStep(1, 12, StepData.of(true, 0.95f, 0.6f, 1.0f, 0)); // Snare step 12
    kit.addClip(kitClip);
    original.addTrack(kit);

    // 2. Save project to a temporary file
    File tempFile = File.createTempFile("complex_project_roundtrip", ".xml");
    tempFile.deleteOnExit();
    ProjectSerializer.save(original, tempFile);

    // 3. Reload from the saved XML file
    ProjectModel reloaded = DelugeXmlParser.parseSong(tempFile);
    assertNotNull(reloaded, "Failed to parse saved complex project XML!");

    // 4. Assert absolute, field-by-field value parity
    List<String> discrepancies = new ArrayList<>();
    compareProjects("ProgrammaticComplexProject", original, reloaded, discrepancies);

    if (!discrepancies.isEmpty()) {
      fail("Programmatic complex project roundtrip failed with discrepancies: " + discrepancies);
    }
    System.out.println(
        "[Test] Programmatic complex project roundtrip completed with 100% value parity!");
  }

  private List<Path> findXmlFiles(Path dir) throws Exception {
    return Files.walk(dir)
        .filter(
            p ->
                Files.isRegularFile(p)
                    && (p.toString().endsWith(".xml") || p.toString().endsWith(".XML")))
        .collect(Collectors.toList());
  }

  private void compareProjects(
      String sourceName, ProjectModel orig, ProjectModel reloaded, List<String> discrepancies) {
    // Compare Global Parameters
    if (Math.abs(orig.getBpm() - reloaded.getBpm()) > EPSILON) {
      discrepancies.add(
          String.format(
              "%s: BPM mismatch (expected %.2f, was %.2f)",
              sourceName, orig.getBpm(), reloaded.getBpm()));
    }
    if (Math.abs(orig.getSwing() - reloaded.getSwing()) > EPSILON) {
      discrepancies.add(
          String.format(
              "%s: Swing mismatch (expected %.2f, was %.2f)",
              sourceName, orig.getSwing(), reloaded.getSwing()));
    }

    // Compare Tracks
    if (orig.getTracks().size() != reloaded.getTracks().size()) {
      discrepancies.add(
          String.format(
              "%s: Track count mismatch (expected %d, was %d)",
              sourceName, orig.getTracks().size(), reloaded.getTracks().size()));
      return;
    }

    for (int t = 0; t < orig.getTracks().size(); t++) {
      TrackModel origTrack = orig.getTracks().get(t);
      TrackModel relTrack = reloaded.getTracks().get(t);

      if (!origTrack.getClass().equals(relTrack.getClass())) {
        discrepancies.add(
            String.format(
                "%s: Track %d type mismatch (expected %s, was %s)",
                sourceName,
                t,
                origTrack.getClass().getSimpleName(),
                relTrack.getClass().getSimpleName()));
        continue;
      }

      if (!origTrack.getName().equals(relTrack.getName())) {
        discrepancies.add(
            String.format(
                "%s: Track %d name mismatch (expected %s, was %s)",
                sourceName, t, origTrack.getName(), relTrack.getName()));
      }

      // Track-Specific Audits
      if (origTrack instanceof SynthTrackModel origSynth) {
        SynthTrackModel relSynth = (SynthTrackModel) relTrack;
        compareSynthTracks(sourceName, t, origSynth, relSynth, discrepancies);
      } else if (origTrack instanceof KitTrackModel origKit) {
        KitTrackModel relKit = (KitTrackModel) relTrack;
        compareKitTracks(sourceName, t, origKit, relKit, discrepancies);
      }

      // Compare Clips and Steps
      compareClips(sourceName, t, origTrack, relTrack, discrepancies);
    }
  }

  private void compareSynthTracks(
      String src, int idx, SynthTrackModel orig, SynthTrackModel rel, List<String> discrepancies) {
    if (!orig.getOsc1Type().equalsIgnoreCase(rel.getOsc1Type())) {
      discrepancies.add(
          String.format(
              "%s: Track %d Osc1Type mismatch (expected %s, was %s)",
              src, idx, orig.getOsc1Type(), rel.getOsc1Type()));
    }
    if (Math.abs(orig.getLpfFreq() - rel.getLpfFreq()) > 1.0f) {
      discrepancies.add(
          String.format(
              "%s: Track %d LPF Cutoff mismatch (expected %.2f, was %.2f)",
              src, idx, orig.getLpfFreq(), rel.getLpfFreq()));
    }
    if (Math.abs(orig.getLpfRes() - rel.getLpfRes()) > 1.0f) {
      discrepancies.add(
          String.format(
              "%s: Track %d LPF Resonance mismatch (expected %.2f, was %.2f)",
              src, idx, orig.getLpfRes(), rel.getLpfRes()));
    }
    if (orig.getFilterMode() != rel.getFilterMode()) {
      discrepancies.add(
          String.format(
              "%s: Track %d FilterMode mismatch (expected %s, was %s)",
              src, idx, orig.getFilterMode(), rel.getFilterMode()));
    }
    if (Math.abs(orig.getVolume() - rel.getVolume()) > EPSILON) {
      discrepancies.add(
          String.format(
              "%s: Track %d Synth Volume mismatch (expected %.4f, was %.4f)",
              src, idx, orig.getVolume(), rel.getVolume()));
    }

    // Envelope comparisons
    compareEnvelopes(src, idx, "Env1 (AMP)", orig.getEnv(0), rel.getEnv(0), discrepancies);
    compareEnvelopes(src, idx, "Env2 (MOD)", orig.getEnv(1), rel.getEnv(1), discrepancies);
  }

  private void compareKitTracks(
      String src, int idx, KitTrackModel orig, KitTrackModel rel, List<String> discrepancies) {
    List<Drum> origDrums = orig.getDrums();
    List<Drum> relDrums = rel.getDrums();

    if (origDrums.size() != relDrums.size()) {
      discrepancies.add(
          String.format(
              "%s: Track %d Kit Drum count mismatch (expected %d, was %d)",
              src, idx, origDrums.size(), relDrums.size()));
      return;
    }

    for (int d = 0; d < origDrums.size(); d++) {
      Drum origD = origDrums.get(d);
      Drum relD = relDrums.get(d);

      if (!origD.getName().equals(relD.getName())) {
        discrepancies.add(
            String.format(
                "%s: Track %d Drum %d name mismatch (expected %s, was %s)",
                src, idx, d, origD.getName(), relD.getName()));
      }

      if (origD instanceof SoundDrum origS && relD instanceof SoundDrum relS) {
        // Sample paths must match (ignoring backslash differences)
        String origPath =
            origS.getSamplePath() != null ? origS.getSamplePath().replace("\\", "/") : "";
        String relPath =
            relS.getSamplePath() != null ? relS.getSamplePath().replace("\\", "/") : "";
        if (!origPath.equals(relPath)) {
          discrepancies.add(
              String.format(
                  "%s: Track %d Drum %d sample path mismatch (expected %s, was %s)",
                  src, idx, d, origPath, relPath));
        }
        if (Math.abs(origS.getVolume() - relS.getVolume()) > EPSILON) {
          discrepancies.add(
              String.format(
                  "%s: Track %d Drum %d volume mismatch (expected %.4f, was %.4f)",
                  src, idx, d, origS.getVolume(), relS.getVolume()));
        }
        if (Math.abs(origS.getPan() - relS.getPan()) > EPSILON) {
          discrepancies.add(
              String.format(
                  "%s: Track %d Drum %d panning mismatch (expected %.4f, was %.4f)",
                  src, idx, d, origS.getPan(), relS.getPan()));
        }
      }
    }
  }

  private void compareEnvelopes(
      String src,
      int tIdx,
      String label,
      EnvelopeModel orig,
      EnvelopeModel rel,
      List<String> discrepancies) {
    if (orig == null && rel == null) return;
    if (orig == null || rel == null) {
      discrepancies.add(
          String.format("%s: Track %d Envelope %s presence mismatch", src, tIdx, label));
      return;
    }
    if (Math.abs(orig.attack() - rel.attack()) > EPSILON) {
      discrepancies.add(
          String.format(
              "%s: Track %d Envelope %s Attack mismatch (expected %.4f, was %.4f)",
              src, tIdx, label, orig.attack(), rel.attack()));
    }
    if (Math.abs(orig.decay() - rel.decay()) > EPSILON) {
      discrepancies.add(
          String.format(
              "%s: Track %d Envelope %s Decay mismatch (expected %.4f, was %.4f)",
              src, tIdx, label, orig.decay(), rel.decay()));
    }
    if (Math.abs(orig.sustain() - rel.sustain()) > EPSILON) {
      discrepancies.add(
          String.format(
              "%s: Track %d Envelope %s Sustain mismatch (expected %.4f, was %.4f)",
              src, tIdx, label, orig.sustain(), rel.sustain()));
    }
    if (Math.abs(orig.release() - rel.release()) > EPSILON) {
      discrepancies.add(
          String.format(
              "%s: Track %d Envelope %s Release mismatch (expected %.4f, was %.4f)",
              src, tIdx, label, orig.release(), rel.release()));
    }
  }

  private void compareClips(
      String src, int tIdx, TrackModel orig, TrackModel rel, List<String> discrepancies) {
    List<ClipModel> origClips = orig.getClips();
    List<ClipModel> relClips = rel.getClips();

    if (origClips.size() != relClips.size()) {
      discrepancies.add(
          String.format(
              "%s: Track %d Clip count mismatch (expected %d, was %d)",
              src, tIdx, origClips.size(), relClips.size()));
      return;
    }

    for (int c = 0; c < origClips.size(); c++) {
      ClipModel origC = origClips.get(c);
      ClipModel relC = relClips.get(c);

      if (origC.getStepCount() != relC.getStepCount()) {
        discrepancies.add(
            String.format(
                "%s: Track %d Clip %d Step count mismatch (expected %d, was %d)",
                src, tIdx, c, origC.getStepCount(), relC.getStepCount()));
        continue;
      }

      if (origC.isTripletMode() != relC.isTripletMode()) {
        discrepancies.add(
            String.format(
                "%s: Track %d Clip %d TripletMode mismatch (expected %b, was %b)",
                src, tIdx, c, origC.isTripletMode(), relC.isTripletMode()));
      }

      if (origC.getPlayDirection() != relC.getPlayDirection()) {
        discrepancies.add(
            String.format(
                "%s: Track %d Clip %d PlayDirection mismatch (expected %s, was %s)",
                src, tIdx, c, origC.getPlayDirection(), relC.getPlayDirection()));
      }

      if (orig instanceof KitTrackModel) {
        // Kit tracks: row count and row order must match exactly because rows correspond to
        // specific drum sounds
        if (origC.getRowCount() != relC.getRowCount()) {
          discrepancies.add(
              String.format(
                  "%s: Track %d Clip %d Row count mismatch (expected %d, was %d)",
                  src, tIdx, c, origC.getRowCount(), relC.getRowCount()));
          continue;
        }

        for (int r = 0; r < origC.getRowCount(); r++) {
          for (int s = 0; s < origC.getStepCount(); s++) {
            StepData origS = origC.getStep(r, s);
            StepData relS = relC.getStep(r, s);

            if (origS.active() != relS.active()) {
              discrepancies.add(
                  String.format(
                      "%s: Track %d Clip %d Step [%d,%d] active state mismatch (expected %b, was %b)",
                      src, tIdx, c, r, s, origS.active(), relS.active()));
              continue;
            }

            if (origS.active()) {
              if (Math.abs(origS.velocity() - relS.velocity()) > EPSILON) {
                discrepancies.add(
                    String.format(
                        "%s: Track %d Clip %d Step [%d,%d] Velocity mismatch (expected %.4f, was %.4f)",
                        src, tIdx, c, r, s, origS.velocity(), relS.velocity()));
              }
              // Use a larger tolerance for gate to handle tick quantization (e.g. 0.05f)
              if (Math.abs(origS.gate() - relS.gate()) > 0.05f) {
                discrepancies.add(
                    String.format(
                        "%s: Track %d Clip %d Step [%d,%d] Gate/Duration mismatch (expected %.4f, was %.4f)",
                        src, tIdx, c, r, s, origS.gate(), relS.gate()));
              }
            }
          }
        }
      } else {
        // Synth tracks: empty rows are skipped by the serializer, so we compare active note events
        // semantically
        List<NoteEvent> origNotes = new ArrayList<>();
        for (int r = 0; r < origC.getRowCount(); r++) {
          for (int s = 0; s < origC.getStepCount(); s++) {
            StepData sd = origC.getStep(r, s);
            if (sd.active()) {
              origNotes.add(new NoteEvent(s, sd.pitch(), sd.velocity(), sd.gate()));
            }
          }
        }

        List<NoteEvent> relNotes = new ArrayList<>();
        for (int r = 0; r < relC.getRowCount(); r++) {
          for (int s = 0; s < relC.getStepCount(); s++) {
            StepData sd = relC.getStep(r, s);
            if (sd.active()) {
              relNotes.add(new NoteEvent(s, sd.pitch(), sd.velocity(), sd.gate()));
            }
          }
        }

        if (origNotes.size() != relNotes.size()) {
          discrepancies.add(
              String.format(
                  "%s: Track %d Clip %d active note count mismatch (expected %d, was %d)",
                  src, tIdx, c, origNotes.size(), relNotes.size()));
          continue;
        }

        origNotes.sort(null);
        relNotes.sort(null);

        for (int i = 0; i < origNotes.size(); i++) {
          NoteEvent o = origNotes.get(i);
          NoteEvent r = relNotes.get(i);

          if (o.step != r.step) {
            discrepancies.add(
                String.format(
                    "%s: Track %d Clip %d Note %d step index mismatch (expected %d, was %d)",
                    src, tIdx, c, i, o.step, r.step));
          }
          if (o.pitch != r.pitch) {
            discrepancies.add(
                String.format(
                    "%s: Track %d Clip %d Note %d pitch mismatch (expected %d, was %d)",
                    src, tIdx, c, i, o.pitch, r.pitch));
          }
          if (Math.abs(o.velocity - r.velocity) > EPSILON) {
            discrepancies.add(
                String.format(
                    "%s: Track %d Clip %d Note %d velocity mismatch (expected %.4f, was %.4f)",
                    src, tIdx, c, i, o.velocity, r.velocity));
          }
          if (Math.abs(o.gate - r.gate) > 0.05f) {
            discrepancies.add(
                String.format(
                    "%s: Track %d Clip %d Note %d gate/duration mismatch (expected %.4f, was %.4f)",
                    src, tIdx, c, i, o.gate, r.gate));
          }
        }
      }
    }
  }

  private static class NoteEvent implements Comparable<NoteEvent> {
    int step;
    int pitch;
    float velocity;
    float gate;

    NoteEvent(int step, int pitch, float velocity, float gate) {
      this.step = step;
      this.pitch = pitch;
      this.velocity = velocity;
      this.gate = gate;
    }

    @Override
    public int compareTo(NoteEvent other) {
      if (this.step != other.step) return Integer.compare(this.step, other.step);
      return Integer.compare(this.pitch, other.pitch);
    }
  }
}
