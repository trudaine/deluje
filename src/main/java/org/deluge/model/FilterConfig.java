package org.deluge.model;

/**
 * Encapsulates all filter-related parameters for a synthesizer track, including low-pass and
 * high-pass filter modes, cutoffs, resonance, drive, and routing.
 */
public class FilterConfig {
  private FilterMode filterMode = FilterMode.LADDER_24;
  private float lpfFreq = 20000.0f;
  private float lpfRes = 0.0f;
  private float lpfMorph = 0.0f;
  private float hpfFreq = 20.0f;
  private float hpfRes = 0.0f;
  private float hpfMorph = 0.0f;
  private FilterMode hpfMode = FilterMode.LADDER_12;
  private float hpfFm = 0.0f;
  private float filterDrive = 1.0f;
  private boolean filterNotch = false;
  private int filterRoute = 0; // 0=SERIES_LPF_HPF, 1=SERIES_HPF_LPF, 2=PARALLEL

  public FilterMode getFilterMode() {
    return filterMode;
  }

  public void setFilterMode(FilterMode filterMode) {
    this.filterMode = filterMode;
  }

  public float getLpfFreq() {
    return lpfFreq;
  }

  public void setLpfFreq(float lpfFreq) {
    this.lpfFreq = lpfFreq;
  }

  public float getLpfRes() {
    return lpfRes;
  }

  public void setLpfRes(float lpfRes) {
    this.lpfRes = lpfRes;
  }

  public float getLpfMorph() {
    return lpfMorph;
  }

  public void setLpfMorph(float lpfMorph) {
    this.lpfMorph = lpfMorph;
  }

  public float getHpfFreq() {
    return hpfFreq;
  }

  public void setHpfFreq(float hpfFreq) {
    this.hpfFreq = hpfFreq;
  }

  public float getHpfRes() {
    return hpfRes;
  }

  public void setHpfRes(float hpfRes) {
    this.hpfRes = hpfRes;
  }

  public float getHpfMorph() {
    return hpfMorph;
  }

  public void setHpfMorph(float hpfMorph) {
    this.hpfMorph = hpfMorph;
  }

  public FilterMode getHpfMode() {
    return hpfMode;
  }

  public void setHpfMode(FilterMode hpfMode) {
    this.hpfMode = hpfMode;
  }

  public float getHpfFm() {
    return hpfFm;
  }

  public void setHpfFm(float hpfFm) {
    this.hpfFm = hpfFm;
  }

  public float getFilterDrive() {
    return filterDrive;
  }

  public void setFilterDrive(float filterDrive) {
    this.filterDrive = filterDrive;
  }

  public boolean isFilterNotch() {
    return filterNotch;
  }

  public void setFilterNotch(boolean filterNotch) {
    this.filterNotch = filterNotch;
  }

  public int getFilterRoute() {
    return filterRoute;
  }

  public void setFilterRoute(int filterRoute) {
    this.filterRoute = filterRoute;
  }

  /**
   * Copies all parameters from another filter configuration.
   *
   * @param other the source configuration to copy from
   */
  public void copyFrom(FilterConfig other) {
    this.filterMode = other.filterMode;
    this.lpfFreq = other.lpfFreq;
    this.lpfRes = other.lpfRes;
    this.lpfMorph = other.lpfMorph;
    this.hpfFreq = other.hpfFreq;
    this.hpfRes = other.hpfRes;
    this.hpfMorph = other.hpfMorph;
    this.hpfMode = other.hpfMode;
    this.hpfFm = other.hpfFm;
    this.filterDrive = other.filterDrive;
    this.filterNotch = other.filterNotch;
    this.filterRoute = other.filterRoute;
  }
}
