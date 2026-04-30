package org.chuck.deluge.model;

/** Represents an Audio track that can record from Adc and play back via LiSa. */
public class AudioTrackModel extends TrackModel {

  private float recordLengthSec = 10.0f;
  private boolean isRecording = false;
  private boolean isPlaying = false;
  private boolean isLooping = true;
  private float playRate = 1.0f;

  public AudioTrackModel(String name) {
    super(name, TrackType.AUDIO);
  }

  public float getRecordLengthSec() {
    return recordLengthSec;
  }

  public void setRecordLengthSec(float recordLengthSec) {
    this.recordLengthSec = Math.max(1.0f, Math.min(60.0f, recordLengthSec));
  }

  public boolean isRecording() {
    return isRecording;
  }

  public void setRecording(boolean recording) {
    this.isRecording = recording;
  }

  public boolean isPlaying() {
    return isPlaying;
  }

  public void setPlaying(boolean playing) {
    this.isPlaying = playing;
  }

  public boolean isLooping() {
    return isLooping;
  }

  public void setLooping(boolean looping) {
    this.isLooping = looping;
  }

  public float getPlayRate() {
    return playRate;
  }

  public void setPlayRate(float playRate) {
    this.playRate = Math.max(0.25f, Math.min(4.0f, playRate));
  }
}
