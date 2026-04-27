package org.chuck.deluge.ui.netbeans;

/** ViewModel for the Synth/Kit Editor component. */
public class EditorViewModel extends BaseViewModel {
  private String osc1Type = "Sine";
  private int osc1Vol = 64;
  private String osc2Type = "Sine";
  private int osc2Vol = 0;
  private int filterCutoff = 64;
  private int attack = 10;
  private int decay = 20;
  private int sustain = 80;
  private int release = 30;

  // Getters/Setters with firePropertyChange...
  public String getOsc1Type() {
    return osc1Type;
  }

  public void setOsc1Type(String v) {
    String old = osc1Type;
    osc1Type = v;
    firePropertyChange("osc1Type", old, v);
  }

  public int getOsc1Vol() {
    return osc1Vol;
  }

  public void setOsc1Vol(int v) {
    int old = osc1Vol;
    osc1Vol = v;
    firePropertyChange("osc1Vol", old, v);
  }

  public String getOsc2Type() {
    return osc2Type;
  }

  public void setOsc2Type(String v) {
    String old = osc2Type;
    osc2Type = v;
    firePropertyChange("osc2Type", old, v);
  }

  public int getOsc2Vol() {
    return osc2Vol;
  }

  public void setOsc2Vol(int v) {
    int old = osc2Vol;
    osc2Vol = v;
    firePropertyChange("osc2Vol", old, v);
  }

  public int getFilterCutoff() {
    return filterCutoff;
  }

  public void setFilterCutoff(int v) {
    int old = filterCutoff;
    filterCutoff = v;
    firePropertyChange("filterCutoff", old, v);
  }

  public int getAttack() {
    return attack;
  }

  public void setAttack(int v) {
    int old = attack;
    attack = v;
    firePropertyChange("attack", old, v);
  }

  public int getDecay() {
    return decay;
  }

  public void setDecay(int v) {
    int old = decay;
    decay = v;
    firePropertyChange("decay", old, v);
  }

  public int getSustain() {
    return sustain;
  }

  public void setSustain(int v) {
    int old = sustain;
    sustain = v;
    firePropertyChange("sustain", old, v);
  }

  public int getRelease() {
    return release;
  }

  public void setRelease(int v) {
    int old = release;
    release = v;
    firePropertyChange("release", old, v);
  }
}
