package org.deluge.xml;

import java.io.InputStream;
import java.io.File;
import org.deluge.model.KitTrackModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.deluge.model.SoundDrum;
import org.w3c.dom.Element;

/**
 * Coordinator entry point for Deluge XML parsing. Delegates actual parsing of
 * songs, synths, and kits to domain-specific parser subclasses.
 */
public class DelugeXmlParser {

  public static ProjectModel parseSong(File xmlFile) throws Exception {
    return SongXmlParser.parseSong(xmlFile);
  }

  public static ProjectModel parseSong(InputStream is, String name) throws Exception {
    return SongXmlParser.parseSong(is, name);
  }

  public static SynthTrackModel parseSynth(File xmlFile) throws Exception {
    return InstrumentXmlParser.parseSynth(xmlFile);
  }

  public static SynthTrackModel parseSynth(InputStream is, String name) throws Exception {
    return InstrumentXmlParser.parseSynth(is, name);
  }

  public static KitTrackModel parseKit(File xmlFile) throws Exception {
    return KitXmlParser.parseKit(xmlFile);
  }

  public static KitTrackModel parseKit(InputStream is, String name) throws Exception {
    return KitXmlParser.parseKit(is, name);
  }

  public static SoundDrum parseSoundDrum(File xmlFile) throws Exception {
    return KitXmlParser.parseSoundDrum(xmlFile);
  }

  public static SoundDrum parseSoundDrum(Element soundNode, String soundName) {
    return KitXmlParser.parseSoundDrum(soundNode, soundName);
  }
}
