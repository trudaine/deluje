package org.chuck.deluge.kit;

import java.io.File;
import java.util.List;
import org.chuck.deluge.model.KitTrackModel;
import org.chuck.deluge.model.SoundDrum;
import org.chuck.deluge.model.SynthTrackModel;
import org.chuck.deluge.xml.DelugeXmlParser;

/** Assembles a KitTrackModel from multiple synth preset XML files. */
public class KitAssembler {

  /**
   * Loads multiple synth XML files and produces a KitTrackModel where each synth sound is assigned
   * to one lane. The synth preset path is stored as the "sample" reference, producing valid XML
   * that the Deluge hardware can interpret as a synth-on-kit-lane.
   */
  public static KitTrackModel assembleFromSynths(
      String kitName, List<File> synthFiles, List<Integer> muteGroups, List<Integer> pitchOffsets)
      throws Exception {

    KitTrackModel kit = new KitTrackModel(kitName);

    for (int i = 0; i < synthFiles.size(); i++) {
      SynthTrackModel synth = DelugeXmlParser.parseSynth(synthFiles.get(i));
      String laneName = synth.getName();
      if (laneName == null || laneName.isBlank()) {
        laneName = "Lane " + (i + 1);
      }

      SoundDrum sound = new SoundDrum(laneName);
      // Store the synth preset path as the sample reference.
      // The Deluge firmware renders this as a synth voice on the kit lane.
      sound.setSamplePath(synthFiles.get(i).getAbsolutePath());

      if (i < muteGroups.size()) {
        sound.setMuteGroup(muteGroups.get(i));
      }
      if (i < pitchOffsets.size()) {
        sound.setPitchSemitones(pitchOffsets.get(i));
      }

      kit.addDrum(sound);
    }

    return kit;
  }
}
