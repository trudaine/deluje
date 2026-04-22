package org.chuck.deluge.ui.util;

import java.util.HashMap;
import java.util.Map;

/** Short help concept definitions for dynamic onboarding live interactive tooltips. */
public class HelpTextManager {
  private static final Map<String, String> DICTIONARY = new HashMap<>();

  static {
    DICTIONARY.put("LEVEL", "Track Volume Level. [GLOBAL] Controls the global volume dynamics of the track.");
    DICTIONARY.put("PAN", "Track step Panning modulation. [PER STEP] Automation and drawing supported.");
    DICTIONARY.put("PITCH", "Track step active transposition. [PER STEP] Step pitch transpose values assignments.");
    DICTIONARY.put("FILTER", "Low-pass filter Cutoff frequency. [PER STEP] Live automation dynamic drawing supported.");
    DICTIONARY.put("RESONANCE", "Filter resonance cutoff dynamic value depth. [PER STEP]");
    DICTIONARY.put("DELAY", "Global step Delay parameter automation values. [PER STEP]");
    DICTIONARY.put("REVERB", "Global step Reverb dynamic parameter assignment values. [PER STEP]");
    DICTIONARY.put("GATE", "Note gate active playback percentage duration length. [PER STEP]");
    DICTIONARY.put("VELOCITY", "Note velocity volume dynamics. [PER STEP]");
    DICTIONARY.put("PROBABILITY", "Notes active odds assignments percentage. [PER STEP]");
    DICTIONARY.put("TRANSPOSE", "Track global scale pitch transpose. [GLOBAL] Dynamic pitch shift values.");
  }

  public static String getHelp(String label) {
    String match = label.toUpperCase().replace(" ", "");
    if (match.equals("START/END")) match = "GATE";
    return DICTIONARY.getOrDefault(match, "Dynamic sequencer parameter live assignment onboarding tutorial checks.");
  }
}
