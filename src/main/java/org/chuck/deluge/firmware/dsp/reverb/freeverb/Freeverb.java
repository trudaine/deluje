package org.chuck.deluge.firmware.dsp.reverb.freeverb;

import static org.chuck.deluge.firmware.util.Q31.*;

import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.dsp.reverb.ReverbBase;

public class Freeverb extends ReverbBase {
  private static final int numcombs = 8;
  private static final int numallpasses = 4;
  private static final float scalewet = 3;
  private static final float scaledry = 2;
  private static final float scaledamp = 0.4f;
  private static final float scaleroom = 0.28f;
  private static final float offsetroom = 0.7f;
  private static final int stereospread = 23;

  private float roomsize;
  private float damp;
  private float wet;
  private int wet2;
  private float dry;
  private float width;

  private final Comb[] combL = new Comb[numcombs];
  private final Comb[] combR = new Comb[numcombs];
  private final Allpass[] allpassL = new Allpass[numallpasses];
  private final Allpass[] allpassR = new Allpass[numallpasses];

  private int reverb_send_post_lpf = 0;

  public Freeverb() {
    for (int i = 0; i < numcombs; i++) {
      combL[i] = new Comb();
      combR[i] = new Comb();
    }
    for (int i = 0; i < numallpasses; i++) {
      allpassL[i] = new Allpass();
      allpassR[i] = new Allpass();
    }

    combL[0].setBuffer(new int[1116]);
    combR[0].setBuffer(new int[1116 + stereospread]);
    combL[1].setBuffer(new int[1188]);
    combR[1].setBuffer(new int[1188 + stereospread]);
    combL[2].setBuffer(new int[1277]);
    combR[2].setBuffer(new int[1277 + stereospread]);
    combL[3].setBuffer(new int[1356]);
    combR[3].setBuffer(new int[1356 + stereospread]);
    combL[4].setBuffer(new int[1422]);
    combR[4].setBuffer(new int[1422 + stereospread]);
    combL[5].setBuffer(new int[1491]);
    combR[5].setBuffer(new int[1491 + stereospread]);
    combL[6].setBuffer(new int[1557]);
    combR[6].setBuffer(new int[1557 + stereospread]);
    combL[7].setBuffer(new int[1617]);
    combR[7].setBuffer(new int[1617 + stereospread]);

    allpassL[0].setBuffer(new int[556]);
    allpassR[0].setBuffer(new int[556 + stereospread]);
    allpassL[1].setBuffer(new int[441]);
    allpassR[1].setBuffer(new int[441 + stereospread]);
    allpassL[2].setBuffer(new int[341]);
    allpassR[2].setBuffer(new int[341 + stereospread]);
    allpassL[3].setBuffer(new int[225]);
    allpassR[3].setBuffer(new int[225 + stereospread]);

    setRoomSize(0.5f);
    setDamping(0.5f);
    setWet(1.0f / scalewet);
    setDry(0);
    setWidth(1);
  }

  @Override
  public void setRoomSize(float value) {
    roomsize = (value * scaleroom) + offsetroom;
    update();
  }

  @Override
  public void setDamping(float value) {
    damp = value * scaledamp;
    update();
  }

  public void setWet(float value) {
    wet = value * scalewet;
    update();
  }

  public void setDry(float value) {
    dry = value * scaledry;
  }

  @Override
  public void setWidth(float value) {
    width = value;
    update();
  }

  private void update() {
    int feedback = (int) (roomsize * 2147483647.0);
    for (int i = 0; i < numcombs; i++) {
      combL[i].setFeedback(feedback);
      combR[i].setFeedback(feedback);
      combL[i].setDamp(damp);
      combR[i].setDamp(damp);
    }
    wet2 = (int) (width * 2147483647.0);
  }

  @Override
  public void process(int[] input, StereoSample[] output) {
    for (int i = 0; i < input.length; i++) {
      int reverb_sample = input[i];
      int distance_to_go = reverb_sample - reverb_send_post_lpf;
      reverb_send_post_lpf += distance_to_go >> 11;
      reverb_sample -= reverb_send_post_lpf;

      processOne(reverb_sample, output[i]);
    }
  }

  private void processOne(int input, StereoSample output_sample) {
    int out_l = 0;
    int out_r = 0;

    for (int i = 0; i < numcombs; i++) {
      out_l += combL[i].process(input);
      out_r += combR[i].process(input);
    }

    for (int i = 0; i < numallpasses; i++) {
      out_l = allpassL[i].process(out_l);
      out_r = allpassR[i].process(out_r);
    }

    int out_l_new = (out_l + multiply_32x32_rshift32_rounded(out_r, wet2)) << 1;
    int out_r_new = (out_r + multiply_32x32_rshift32_rounded(out_l, wet2)) << 1;

    output_sample.l += multiply_32x32_rshift32_rounded(out_l_new, getPanLeft());
    output_sample.r += multiply_32x32_rshift32_rounded(out_r_new, getPanRight());
  }

  @Override
  public void clear() {
    for (int i = 0; i < numcombs; i++) {
      combL[i].mute();
      combR[i].mute();
    }
    for (int i = 0; i < numallpasses; i++) {
      allpassL[i].mute();
      allpassR[i].mute();
    }
  }
}
