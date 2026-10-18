package org.deluge.playback;

public class SampleVoiceSettings {
  public int startPoint = 0; // 0 to 65535
  public int endPoint = 65535; // 0 to 65535
  public int loopStart = 0; // 0 to 65535
  public int loopEnd = 0; // 0 = no loop-end point; > 0 = explicit loop-end frame
  public int loopMode = 0; // C SampleRepeatMode: 0 CUT, 1 ONCE, 2 LOOP, 3 STRETCH
  public boolean reverse = false;
  public boolean timestretch = false;
  public int interpolationMode = 1; // 0 = NAIVE, 1 = LINEAR, 2 = SINC
  public int pitchSpeedMode = 0; // 0 = PITCH, 1 = SPEED
  public int transpose = 0; // -24 to +24 semitones
  public int pitchSpeed = 0; // speed adjust
}
