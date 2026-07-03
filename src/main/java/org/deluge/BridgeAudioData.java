package org.deluge;

import org.deluge.shadow.core.ChuckArray;

/**
 * Extracted AudioData class from BridgeContract.java to reduce file complexity.
 */
final class BridgeAudioData {
  static final int TRACKS = BridgeContract.TRACKS;

  // Global variables used by ChucK VM
  static final String G_AUDIO_REC = BridgeContract.G_AUDIO_REC;
  static final String G_AUDIO_PLAY = BridgeContract.G_AUDIO_PLAY;
  static final String G_AUDIO_LOOP = BridgeContract.G_AUDIO_LOOP;
  static final String G_AUDIO_RATE = BridgeContract.G_AUDIO_RATE;
  static final String G_AUDIO_THRESHOLD = BridgeContract.G_AUDIO_THRESHOLD;
  static final String G_AUDIO_THRESHOLD_LEVEL = BridgeContract.G_AUDIO_THRESHOLD_LEVEL;

  final int[] audioRec = new int[TRACKS];
  final int[] audioPlay = new int[TRACKS];
  final int[] audioLoop = new int[TRACKS];
  final float[] audioRate = new float[TRACKS];
  final int[] audioThreshold = new int[TRACKS];
  final float[] audioThresholdLevel = new float[TRACKS];

  void initDefaults() {
    for (int t = 0; t < TRACKS; t++) {
      audioRec[t] = 0;
      audioPlay[t] = 0;
      audioLoop[t] = 1;
      audioRate[t] = 1f;
      audioThreshold[t] = 0;
      audioThresholdLevel[t] = 0f;
    }
  }

  void register(BridgeContract bridge) {
    bridge.setGlobalObject(G_AUDIO_REC, new ChuckArray(audioRec));
    bridge.setGlobalObject(G_AUDIO_PLAY, new ChuckArray(audioPlay));
    bridge.setGlobalObject(G_AUDIO_LOOP, new ChuckArray(audioLoop));
    bridge.setGlobalObject(G_AUDIO_RATE, new ChuckArray(audioRate));
    bridge.setGlobalObject(G_AUDIO_THRESHOLD, new ChuckArray(audioThreshold));
    bridge.setGlobalObject(G_AUDIO_THRESHOLD_LEVEL, new ChuckArray(audioThresholdLevel));
  }
}
