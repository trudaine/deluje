package org.chuck.deluge.firmware.dsp.reverb;

import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.dsp.reverb.freeverb.Freeverb;

/**
 * Port of unified Reverb container from the C++ firmware. Manages runtime hot-swapping between
 * standard Freeverb, Mutable space, and Digital studio reverbs.
 */
public class ReverbContainer extends ReverbBase {
  public enum Model {
    FREEVERB,
    MUTABLE,
    DIGITAL
  }

  private final Freeverb freeverb = new Freeverb();
  private final MutableReverb mutable = new MutableReverb();
  private final DigitalReverb digital = new DigitalReverb();

  private ReverbBase activeReverb = freeverb;
  private Model currentModel = Model.FREEVERB;

  private float roomSize = 0.5f;
  private float damping = 0.5f;
  private float width = 0.5f;
  private float hpf = 0.0f;
  private float lpf = 0.0f;

  public ReverbContainer() {
    setModel(Model.FREEVERB);
  }

  public void setModel(Model m) {
    this.currentModel = m;
    switch (m) {
      case FREEVERB:
        activeReverb = freeverb;
        break;
      case MUTABLE:
        activeReverb = mutable;
        break;
      case DIGITAL:
        activeReverb = digital;
        break;
    }
    activeReverb.setPanLevels(getPanLeft(), getPanRight());
    activeReverb.setRoomSize(roomSize);
    activeReverb.setDamping(damping);
    activeReverb.setWidth(width);
    activeReverb.setHPF(hpf);
    activeReverb.setLPF(lpf);
  }

  public Model getModel() {
    return currentModel;
  }

  public void clear() {
    freeverb.clear();
    mutable.clear();
    digital.clear();
  }

  @Override
  public void setPanLevels(int panLeft, int panRight) {
    super.setPanLevels(panLeft, panRight);
    activeReverb.setPanLevels(panLeft, panRight);
  }

  @Override
  public void setRoomSize(float value) {
    this.roomSize = value;
    activeReverb.setRoomSize(value);
  }

  @Override
  public float getRoomSize() {
    return this.roomSize;
  }

  @Override
  public void setDamping(float value) {
    this.damping = value;
    activeReverb.setDamping(value);
  }

  @Override
  public float getDamping() {
    return this.damping;
  }

  @Override
  public void setWidth(float value) {
    this.width = value;
    activeReverb.setWidth(value);
  }

  @Override
  public float getWidth() {
    return this.width;
  }

  @Override
  public void setHPF(float f) {
    this.hpf = f;
    activeReverb.setHPF(f);
  }

  @Override
  public float getHPF() {
    return this.hpf;
  }

  @Override
  public void setLPF(float f) {
    this.lpf = f;
    activeReverb.setLPF(f);
  }

  @Override
  public float getLPF() {
    return this.lpf;
  }

  @Override
  public void process(int[] input, StereoSample[] output) {
    activeReverb.process(input, output);
  }
}
