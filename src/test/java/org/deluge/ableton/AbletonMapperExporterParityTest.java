package org.deluge.ableton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.deluge.model.ClipModel;
import org.deluge.model.KitTrackModel;
import org.deluge.model.NoteModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.StepData;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

class AbletonMapperExporterParityTest {

  private static Document parseXml(String xml) throws Exception {
    return DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void synthClipImportDoesNotDuplicateOffGridNotesOrClobberOnGridLengths() throws Exception {
    String xml =
        """
        <Ableton MajorVersion="5" MinorVersion="12.0">
          <LiveSet>
            <Tracks>
              <MidiTrack Id="0">
                <Name><EffectiveName Value="Poly Synth"/></Name>
                <DeviceChain>
                  <MainSequencer>
                    <ClipSlotList>
                      <ClipSlot>
                        <MidiClip>
                          <Name Value="Lead Clip"/>
                          <CurrentStart Value="0"/>
                          <CurrentEnd Value="4"/>
                          <KeyTracks>
                            <KeyTrack>
                              <MidiKey Value="64"/>
                              <Notes>
                                <MidiNoteEvent Time="0.125" Duration="0.5" Velocity="100" Probability="0.6"/>
                                <MidiNoteEvent Time="1.0" Duration="1.0" Velocity="110" Probability="0.8"/>
                              </Notes>
                            </KeyTrack>
                          </KeyTracks>
                        </MidiClip>
                      </ClipSlot>
                    </ClipSlotList>
                  </MainSequencer>
                </DeviceChain>
              </MidiTrack>
            </Tracks>
          </LiveSet>
        </Ableton>
        """;

    ProjectModel project = new ProjectModel();
    AbletonTrackMapper.importAbletonSet(parseXml(xml), project);

    assertEquals(1, project.getTracks().size());
    SynthTrackModel synth = (SynthTrackModel) project.getTracks().get(0);
    ClipModel clip = synth.getClips().get(0);

    List<NoteModel> notes = clip.getRawNoteEvents(0);
    assertNotNull(notes);
    assertEquals(2, notes.size(), "Off-grid note must not be duplicated by ClipModel.setStep()");

    NoteModel offGrid = notes.get(0);
    assertEquals(12, offGrid.getTickPos(), "0.125 beats * 96 PPQN = 12 ticks");
    assertEquals(48, offGrid.getTickLen(), "0.5 beats * 96 PPQN = 48 ticks");
    assertEquals(0.6f, offGrid.getProbability(), 0.01f);

    NoteModel onGrid = notes.get(1);
    assertEquals(96, onGrid.getTickPos(), "1.0 beat * 96 PPQN = 96 ticks");
    assertEquals(96, onGrid.getTickLen(), "1.0 beat * 96 PPQN = 96 ticks (not clobbered to 24)");
    assertEquals(0.8f, onGrid.getProbability(), 0.01f);
  }

  @Test
  void drumClipImportSetsIsKitFlagAndPreservesDrumRowPitchAndNotes() throws Exception {
    String xml =
        """
        <Ableton MajorVersion="5" MinorVersion="12.0">
          <LiveSet>
            <Tracks>
              <MidiTrack Id="0">
                <Name><EffectiveName Value="Drums"/></Name>
                <DeviceChain>
                  <DeviceList>
                    <DrumGroupDevice>
                      <Branches>
                        <DrumBranch>
                          <Name Value="Kick"/>
                          <BranchInfo><ReceivingNote Value="36"/></BranchInfo>
                        </DrumBranch>
                        <DrumBranch>
                          <Name Value="Snare"/>
                          <BranchInfo><ReceivingNote Value="37"/></BranchInfo>
                        </DrumBranch>
                      </Branches>
                    </DrumGroupDevice>
                  </DeviceList>
                  <MainSequencer>
                    <ClipSlotList>
                      <ClipSlot>
                        <MidiClip>
                          <Name Value="Beat 1"/>
                          <CurrentStart Value="0"/>
                          <CurrentEnd Value="4"/>
                          <KeyTracks>
                            <KeyTrack>
                              <MidiKey Value="36"/>
                              <Notes>
                                <MidiNoteEvent Time="0.0625" Duration="0.5" Velocity="120" Probability="0.75"/>
                              </Notes>
                            </KeyTrack>
                          </KeyTracks>
                        </MidiClip>
                      </ClipSlot>
                    </ClipSlotList>
                  </MainSequencer>
                </DeviceChain>
              </MidiTrack>
            </Tracks>
          </LiveSet>
        </Ableton>
        """;

    ProjectModel project = new ProjectModel();
    AbletonTrackMapper.importAbletonSet(parseXml(xml), project);

    KitTrackModel kit = (KitTrackModel) project.getTracks().get(0);
    ClipModel clip = kit.getClips().get(0);

    assertTrue(clip.isKit(), "Drum clip must have isKit=true");
    assertEquals(0, clip.getRowYNote(0), "Kit row 0 pitch must be drum index 0, not 60");
    assertEquals(
        36,
        clip.getStep(0, 0).pitch(),
        "Kit StepData.pitch() must retain source KeyTrack MidiKey 36, not row index 0");

    List<NoteModel> kickNotes = clip.getRawNoteEvents(0);
    assertEquals(1, kickNotes.size(), "Off-grid drum note must not be duplicated at step 0");
    assertEquals(6, kickNotes.get(0).getTickPos());
    assertEquals(48, kickNotes.get(0).getTickLen());
    assertEquals(0.75f, kickNotes.get(0).getProbability(), 0.01f);
  }

  @Test
  void exporterSerializesQuantizedGridStepDurationInBeatsAndHandlesTripletClips(
      @TempDir Path tempDir) throws Exception {
    ProjectModel project = new ProjectModel();
    project.setBpm(120.0f);

    SynthTrackModel synth = new SynthTrackModel("GridSynth");
    ClipModel clip = new ClipModel("TripletClip", 1, 12);
    clip.setTripletMode(true);
    clip.setRowYNote(0, 60);
    // Clear raw note events to exercise the quantized grid fallback path directly
    clip.setStep(0, 3, StepData.of(true, 0.8f, 2.0f, 0.9f, 60));
    clip.setRawNoteEvents(0, List.of());
    synth.addClip(clip);
    project.addTrack(synth);

    File alsFile = tempDir.resolve("grid_triplet.als").toFile();
    AbletonTrackExporter.exportProject(project, alsFile);

    Document doc = AbletonProjectManager.parseAlsToXml(alsFile);
    NodeList midiClips = doc.getElementsByTagName("MidiClip");
    assertEquals(1, midiClips.getLength());
    Element midiClip = (Element) midiClips.item(0);

    // 12 triplet steps * (32 / 96) beats/step = 4.0 beats
    NodeList currentEndList = midiClip.getElementsByTagName("CurrentEnd");
    double currentEnd =
        Double.parseDouble(((Element) currentEndList.item(0)).getAttribute("Value"));
    assertEquals(4.0, currentEnd, 1e-6, "12 triplet steps must equal 4.0 beats");

    NodeList noteEvents = midiClip.getElementsByTagName("MidiNoteEvent");
    assertEquals(1, noteEvents.getLength());
    Element noteEv = (Element) noteEvents.item(0);

    double timeBeats = Double.parseDouble(noteEv.getAttribute("Time"));
    double durationBeats = Double.parseDouble(noteEv.getAttribute("Duration"));

    // Step 3 at 1/3 beat per triplet step = 1.0 beat; gate=2.0 steps = 2/3 beat
    assertEquals(1.0, timeBeats, 1e-6, "Triplet step 3 must start at 1.0 beat");
    assertEquals(
        2.0 / 3.0,
        durationBeats,
        1e-6,
        "Grid step duration must be gate * stepBeats (2/3 beat, not gate/96)");

    // Round-trip: import the exported triplet .als back and verify triplet grid timing survives
    ProjectModel importedProject = new ProjectModel();
    AbletonTrackMapper.importAbletonSet(doc, importedProject);
    SynthTrackModel importedSynth = (SynthTrackModel) importedProject.getTracks().get(0);
    ClipModel importedClip = importedSynth.getClips().get(0);
    assertTrue(importedClip.isTripletMode(), "Imported clip must detect triplet grid mode");
    assertEquals(12, importedClip.getStepCount(), "12 triplet steps must round-trip as 12 steps");
    assertTrue(
        importedClip.getStep(0, 3).active(), "Triplet step 3 must land on step 3, not step 4");
    assertEquals(
        2.0f,
        importedClip.getStep(0, 3).gate(),
        0.01f,
        "Triplet gate in steps must round-trip as 2.0");
  }

  @Test
  void assetResolverVersionComparatorRanksMajorAndMinorVersionsNumerically() {
    List<String> names =
        new ArrayList<>(
            List.of(
                "Ableton Live 9 Suite.app",
                "Ableton Live 11 Standard.app",
                "Ableton Live 12 Suite.app",
                "Ableton Live 12.1 Beta.app",
                "Ableton Live 10 Suite.app"));

    names.sort(AbletonAssetResolver::compareAbletonVersionNames);

    assertEquals("Ableton Live 12.1 Beta.app", names.get(0));
    assertEquals("Ableton Live 12 Suite.app", names.get(1));
    assertEquals("Ableton Live 11 Standard.app", names.get(2));
    assertEquals("Ableton Live 10 Suite.app", names.get(3));
    assertEquals("Ableton Live 9 Suite.app", names.get(4));
  }
}
