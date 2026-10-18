package org.deluge.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.deluge.modulation.automation.ParamNode;
import org.deluge.xml.DelugeHexMapper;

/**
 * Represents a modulation routing (source -> destination) with an amount, polarity, nested depth
 * control cables, and optional automation nodes on {@code amount} (C {@code
 * PatchCableSet::readPatchCablesFromFile}, {@code patch_cable_set.cpp:848, 870}).
 */
public record PatchCable(
    String source,
    String destination,
    float amount,
    Polarity polarity,
    List<PatchCable> depthControlledBy,
    List<ParamNode> automationNodes,
    Integer rawAmountQ31) {

  public enum Polarity {
    UNIPOLAR,
    BIPOLAR
  }

  public PatchCable {
    depthControlledBy = depthControlledBy != null ? depthControlledBy : Collections.emptyList();
    automationNodes = automationNodes != null ? automationNodes : Collections.emptyList();
  }

  /** Creates a cable without automation nodes or raw Q31 override. */
  public PatchCable(
      String source,
      String destination,
      float amount,
      Polarity polarity,
      List<PatchCable> depthControlledBy) {
    this(source, destination, amount, polarity, depthControlledBy, Collections.emptyList(), null);
  }

  /** Creates a default cable with empty depth control list. */
  public PatchCable(String source, String destination, float amount, Polarity polarity) {
    this(source, destination, amount, polarity, Collections.emptyList());
  }

  /** Creates a default UNIPOLAR cable at 0 amount for migration convenience. */
  public PatchCable(String source, String destination, float amount) {
    this(source, destination, amount, Polarity.UNIPOLAR, Collections.emptyList());
  }

  /** C {@code AutoParam::containsSomething(0)} ({@code auto_param.cpp:2152-2158}). */
  public boolean containsSomething() {
    if (!automationNodes.isEmpty()) {
      return true;
    }
    return resolveAmountQ31() != 0;
  }

  /** Resolves the signed 32-bit Q31 cable amount, preferring the lossless XML hex value. */
  public int resolveAmountQ31() {
    return rawAmountQ31 != null ? rawAmountQ31 : (int) (amount * 2147483647.0);
  }

  /** Resolves the 8-char {@code 0xXXXXXXXX} base hex string for XML serialization. */
  public String resolveBaseHex() {
    return rawAmountQ31 != null
        ? String.format("0x%08X", rawAmountQ31)
        : DelugeHexMapper.floatToHex(amount);
  }

  /** Deep-copies this cable, its nested depth cables, and its automation nodes. */
  public PatchCable deepCopy() {
    List<PatchCable> copiedDepth =
        depthControlledBy.isEmpty() ? Collections.emptyList() : new ArrayList<>();
    for (PatchCable dc : depthControlledBy) {
      copiedDepth.add(dc.deepCopy());
    }
    List<ParamNode> copiedNodes =
        automationNodes.isEmpty() ? Collections.emptyList() : new ArrayList<>();
    for (ParamNode n : automationNodes) {
      copiedNodes.add(new ParamNode(n.pos, n.value, n.interpolated));
    }
    return new PatchCable(
        source, destination, amount, polarity, copiedDepth, copiedNodes, rawAmountQ31);
  }

  /**
   * Scales the linear XML patch amount into a usable DSP coefficient. Destinations like PITCH
   * require exponential (quadratic) scaling.
   */
  public static float applyScaling(String dest, float rawAmount) {
    if ("PITCH".equals(dest) || "OSC1_PITCH".equals(dest) || "OSC2_PITCH".equals(dest)) {
      // Quadratic scaling preserving sign
      return Math.signum(rawAmount) * (rawAmount * rawAmount);
    }
    // Linear scaling for non-pitch destinations
    return rawAmount;
  }
}
