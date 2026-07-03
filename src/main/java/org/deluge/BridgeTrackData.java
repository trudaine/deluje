package org.deluge;

import org.deluge.shadow.core.ChuckArray;

/** Extracted TrackData class from BridgeContract.java to reduce file complexity. */
final class BridgeTrackData {
  static final int TRACKS = BridgeContract.TRACKS;
  static final int MAX_CLIPS_PER_TRACK = BridgeContract.MAX_CLIPS_PER_TRACK;

  // Global variables used by ChucK VM
  static final String G_TRACK_TYPE = BridgeContract.G_TRACK_TYPE;
  static final String G_OSC_TYPE = BridgeContract.G_OSC_TYPE;
  static final String G_TRACK_LEVEL = BridgeContract.G_TRACK_LEVEL;
  static final String G_MUTE = BridgeContract.G_MUTE;
  static final String G_FILTER = BridgeContract.G_FILTER;
  static final String G_FILTER_MODE = BridgeContract.G_FILTER_MODE;
  static final String G_FILTER_MORPH = BridgeContract.G_FILTER_MORPH;
  static final String G_FILTER_DRIVE = BridgeContract.G_FILTER_DRIVE;
  static final String G_FILTER_NOTCH = BridgeContract.G_FILTER_NOTCH;
  static final String G_FILTER_ROUTE = BridgeContract.G_FILTER_ROUTE;
  static final String G_MAX_VOICES = BridgeContract.G_MAX_VOICES;
  static final String G_DELAY_SEND = BridgeContract.G_DELAY_SEND;
  static final String G_REVERB_SEND = BridgeContract.G_REVERB_SEND;
  static final String G_TRACK_LENGTH = BridgeContract.G_TRACK_LENGTH;
  static final String G_CURRENT_CLIP = BridgeContract.G_CURRENT_CLIP;
  static final String G_CLIP_COUNT = BridgeContract.G_CLIP_COUNT;
  static final String G_LAUNCH_QUEUE = BridgeContract.G_LAUNCH_QUEUE;
  static final String G_CLIP_PLAY_MODE = BridgeContract.G_CLIP_PLAY_MODE;
  static final String G_CLIP_PLAY_DIRECTION = BridgeContract.G_CLIP_PLAY_DIRECTION;
  static final String G_TRACK_ID = BridgeContract.G_TRACK_ID;

  final int[] trackType = new int[TRACKS];
  final int[] oscType = new int[TRACKS];
  final float[] trackLevel = new float[TRACKS];
  final int[] mute = new int[TRACKS];
  final float[] filter = new float[TRACKS * 2];
  final int[] filterMode = new int[TRACKS];
  final float[] filterMorph = new float[TRACKS];
  final float[] filterDrive = new float[TRACKS];
  final int[] filterNotch = new int[TRACKS];
  final int[] filterRoute = new int[TRACKS];
  final int[] maxVoices = new int[TRACKS];
  final float[] delaySend = new float[TRACKS];
  final float[] reverbSend = new float[TRACKS];
  final int[] trackLength = new int[TRACKS];
  final int[] currentClip = new int[TRACKS];
  final int[] clipCount = new int[TRACKS];
  final int[] launchQueue = new int[TRACKS];
  final int[] clipPlayMode = new int[TRACKS * MAX_CLIPS_PER_TRACK]; // flat: [t*MAX + ci]
  final int[] clipPlayDirection = new int[TRACKS * MAX_CLIPS_PER_TRACK]; // flat: [t*MAX + ci]
  final int[] trackId = new int[TRACKS];

  void initDefaults() {
    for (int t = 0; t < TRACKS; t++) {
      trackType[t] = 0;
      oscType[t] = 0;
      trackLevel[t] = 0.7f;
      mute[t] = 0;
      filter[t * 2] = 1.0f;
      filter[t * 2 + 1] = 0.5f;
      filterMode[t] = 0;
      filterMorph[t] = 0f;
      filterDrive[t] = 1.0f;
      filterNotch[t] = 0;
      filterRoute[t] = 0;
      maxVoices[t] = 8;
      delaySend[t] = 0f;
      reverbSend[t] = 0.15f;
      trackLength[t] = 16;
      currentClip[t] = 0;
      clipCount[t] = 0;
      launchQueue[t] = -1;
      trackId[t] = t;
      for (int ci = 0; ci < MAX_CLIPS_PER_TRACK; ci++) {
        clipPlayMode[t * MAX_CLIPS_PER_TRACK + ci] = 0; // NORMAL
        clipPlayDirection[t * MAX_CLIPS_PER_TRACK + ci] = 0; // FORWARD
      }
    }
  }

  void register(BridgeContract bridge) {
    bridge.setGlobalObject(G_TRACK_TYPE, new ChuckArray(trackType));
    bridge.setGlobalObject(G_OSC_TYPE, new ChuckArray(oscType));
    bridge.setGlobalObject(G_TRACK_LEVEL, new ChuckArray(trackLevel));
    bridge.setGlobalObject(G_MUTE, new ChuckArray(mute));
    bridge.setGlobalObject(G_FILTER, new ChuckArray(filter));
    bridge.setGlobalObject(G_FILTER_MODE, new ChuckArray(filterMode));
    bridge.setGlobalObject(G_FILTER_MORPH, new ChuckArray(filterMorph));
    bridge.setGlobalObject(G_FILTER_DRIVE, new ChuckArray(filterDrive));
    bridge.setGlobalObject(G_FILTER_NOTCH, new ChuckArray(filterNotch));
    bridge.setGlobalObject(G_FILTER_ROUTE, new ChuckArray(filterRoute));
    bridge.setGlobalObject(G_MAX_VOICES, new ChuckArray(maxVoices));
    bridge.setGlobalObject(G_DELAY_SEND, new ChuckArray(delaySend));
    bridge.setGlobalObject(G_REVERB_SEND, new ChuckArray(reverbSend));
    bridge.setGlobalObject(G_TRACK_LENGTH, new ChuckArray(trackLength));
    bridge.setGlobalObject(G_CURRENT_CLIP, new ChuckArray(currentClip));
    bridge.setGlobalObject(G_CLIP_COUNT, new ChuckArray(clipCount));
    bridge.setGlobalObject(G_LAUNCH_QUEUE, new ChuckArray(launchQueue));
    bridge.setGlobalObject(G_CLIP_PLAY_MODE, new ChuckArray(clipPlayMode));
    bridge.setGlobalObject(G_CLIP_PLAY_DIRECTION, new ChuckArray(clipPlayDirection));
    bridge.setGlobalObject(G_TRACK_ID, new ChuckArray(trackId));
  }
}
