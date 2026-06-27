package org.deluge.model;

/**
 * Encapsulates all compressor and sidechain parameters for a synthesizer track. This includes the
 * compressor's threshold, ratio, attack, release, blend, and shape, as well as sidechain envelope
 * and sync configurations.
 */
public class CompressorConfig {
  private float compressorAttack = 0.0f;
  private float compressorRelease = 0.0f;
  private int compressorSyncLevel = 0;
  private float compressorBlend = 0.0f;
  private float compressorSidechainHpf = 0.0f;
  private int compressorSyncType = 0;
  private int sidechainSyncLevel = 0;
  private int sidechainSyncType = 0;
  private float sidechainAttack = 0.0f;
  private float sidechainRelease = 0.0f;
  private float compressorThreshold = 0.0f;
  private float compressorRatio = 0.0f;
  private float compressorShape = 0.92f;

  public float getCompressorAttack() {
    return compressorAttack;
  }

  public void setCompressorAttack(float compressorAttack) {
    this.compressorAttack = compressorAttack;
  }

  public float getCompressorRelease() {
    return compressorRelease;
  }

  public void setCompressorRelease(float compressorRelease) {
    this.compressorRelease = compressorRelease;
  }

  public int getCompressorSyncLevel() {
    return compressorSyncLevel;
  }

  public void setCompressorSyncLevel(int compressorSyncLevel) {
    this.compressorSyncLevel = compressorSyncLevel;
  }

  public float getCompressorBlend() {
    return compressorBlend;
  }

  public void setCompressorBlend(float compressorBlend) {
    this.compressorBlend = compressorBlend;
  }

  public float getCompressorSidechainHpf() {
    return compressorSidechainHpf;
  }

  public void setCompressorSidechainHpf(float compressorSidechainHpf) {
    this.compressorSidechainHpf = compressorSidechainHpf;
  }

  public int getCompressorSyncType() {
    return compressorSyncType;
  }

  public void setCompressorSyncType(int compressorSyncType) {
    this.compressorSyncType = compressorSyncType;
  }

  public int getSidechainSyncLevel() {
    return sidechainSyncLevel;
  }

  public void setSidechainSyncLevel(int sidechainSyncLevel) {
    this.sidechainSyncLevel = sidechainSyncLevel;
  }

  public int getSidechainSyncType() {
    return sidechainSyncType;
  }

  public void setSidechainSyncType(int sidechainSyncType) {
    this.sidechainSyncType = sidechainSyncType;
  }

  public float getSidechainAttack() {
    return sidechainAttack;
  }

  public void setSidechainAttack(float sidechainAttack) {
    this.sidechainAttack = sidechainAttack;
  }

  public float getSidechainRelease() {
    return sidechainRelease;
  }

  public void setSidechainRelease(float sidechainRelease) {
    this.sidechainRelease = sidechainRelease;
  }

  public float getCompressorThreshold() {
    return compressorThreshold;
  }

  public void setCompressorThreshold(float compressorThreshold) {
    this.compressorThreshold = compressorThreshold;
  }

  public float getCompressorRatio() {
    return compressorRatio;
  }

  public void setCompressorRatio(float compressorRatio) {
    this.compressorRatio = compressorRatio;
  }

  public float getCompressorShape() {
    return compressorShape;
  }

  public void setCompressorShape(float compressorShape) {
    this.compressorShape = compressorShape;
  }

  /**
   * Copies all parameters from another compressor configuration.
   *
   * @param other the source configuration to copy from
   */
  public void copyFrom(CompressorConfig other) {
    this.compressorAttack = other.compressorAttack;
    this.compressorRelease = other.compressorRelease;
    this.compressorSyncLevel = other.compressorSyncLevel;
    this.compressorBlend = other.compressorBlend;
    this.compressorSidechainHpf = other.compressorSidechainHpf;
    this.compressorSyncType = other.compressorSyncType;
    this.sidechainSyncLevel = other.sidechainSyncLevel;
    this.sidechainSyncType = other.sidechainSyncType;
    this.sidechainAttack = other.sidechainAttack;
    this.sidechainRelease = other.sidechainRelease;
    this.compressorThreshold = other.compressorThreshold;
    this.compressorRatio = other.compressorRatio;
    this.compressorShape = other.compressorShape;
  }
}
