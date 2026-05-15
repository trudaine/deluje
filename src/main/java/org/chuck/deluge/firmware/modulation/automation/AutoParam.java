package org.chuck.deluge.firmware.modulation.automation;

import java.util.ArrayList;
import java.util.List;

public class AutoParam {
  public final int paramId;
  public List<ParamNode> nodes = new ArrayList<>();
  public int currentValue;

  public AutoParam(int paramId) {
    this.paramId = paramId;
  }

  public void addNode(int pos, int value) {
    ParamNode node = new ParamNode();
    node.pos = pos;
    node.value = value;
    nodes.add(node);
    nodes.sort((a, b) -> Integer.compare(a.pos, b.pos));
  }

  public int getValueAt(int pos) {
    if (nodes.isEmpty()) return 0;
    // Simple search
    for (int i = 0; i < nodes.size() - 1; i++) {
        if (pos >= nodes.get(i).pos && pos < nodes.get(i+1).pos) {
            return nodes.get(i).value;
        }
    }
    return nodes.get(nodes.size() - 1).value;
  }

  public int valueIncrementPerHalfTick;
  public int ticksTilNextEvent = Integer.MAX_VALUE;

  public int processCurrentPos(
      int currentPos,
      int loopLength,
      boolean reversed,
      boolean didPingpong,
      boolean mayInterpolate) {
    if (nodes.isEmpty()) {
      return Integer.MAX_VALUE;
    }

    // ── Bit-Accurate Node Search ──
    ParamNode nextNode = null;
    int minDistance = Integer.MAX_VALUE;

    for (ParamNode node : nodes) {
      int distance = node.pos - currentPos;
      if (reversed) {
        distance = -distance;
      }
      if (distance < 0) {
        distance += loopLength;
      }
      if (distance < minDistance) {
        minDistance = distance;
        nextNode = node;
      }
    }

    if (minDistance == 0 && nextNode != null) {
      currentValue = nextNode.value;
      // Setup interpolation to next node
      ParamNode followingNode = findNextNode(nextNode, reversed, loopLength);
      if (followingNode != null && followingNode.interpolated && mayInterpolate) {
        int dist = followingNode.pos - nextNode.pos;
        if (reversed) dist = -dist;
        if (dist <= 0) dist += loopLength;

        if (dist > 0) {
          valueIncrementPerHalfTick =
              (int) (((long) (followingNode.value - nextNode.value) << 32) / (dist * 2));
        }
      } else {
        valueIncrementPerHalfTick = 0;
      }

      // Re-calculate distance to following node
      minDistance = findDistanceToNext(nextNode, reversed, loopLength);
    }

    ticksTilNextEvent = minDistance;
    return minDistance;
  }

  private ParamNode findNextNode(ParamNode current, boolean reversed, int loopLength) {
    int idx = nodes.indexOf(current);
    if (reversed) {
      idx--;
      if (idx < 0) idx = nodes.size() - 1;
    } else {
      idx++;
      if (idx >= nodes.size()) idx = 0;
    }
    return nodes.get(idx);
  }

  private int findDistanceToNext(ParamNode current, boolean reversed, int loopLength) {
    ParamNode next = findNextNode(current, reversed, loopLength);
    int dist = next.pos - current.pos;
    if (reversed) dist = -dist;
    if (dist <= 0) dist += loopLength;
    return dist;
  }

  public void notifyPingpongOccurred() {
      valueIncrementPerHalfTick = -valueIncrementPerHalfTick;
  }

  public void tickSamples(int numSamples, int timePerTimerTickInverse) {
    if (valueIncrementPerHalfTick != 0) {
      // Emulate fixed-point interpolation over numSamples
      // In firmware, this is often done in the rendering loop:
      // value += increment;
      currentValue += (int) (((long) valueIncrementPerHalfTick * numSamples) >> 32);
    }
  }
}
