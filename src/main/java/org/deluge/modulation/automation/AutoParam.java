package org.deluge.modulation.automation;

import java.util.ArrayList;
import java.util.List;
import org.deluge.firmware2.Functions;

public class AutoParam {
  public final int paramId;
  public List<ParamNode> nodes = new ArrayList<>();
  public int currentValue;

  public AutoParam(int paramId) {
    this.paramId = paramId;
  }

  public void addNode(int pos, int value) {
    addNode(pos, value, false);
  }

  /**
   * C auto_param.cpp:1070-1095 (AutoParam::setNodeAtPos) — updates an existing node at {@code pos}
   * in place, or inserts a new node in sorted position order.
   */
  public void addNode(int pos, int value, boolean interpolated) {
    for (int i = 0; i < nodes.size(); i++) {
      ParamNode existing = nodes.get(i);
      if (existing.pos == pos) {
        existing.value = value;
        existing.interpolated = interpolated;
        return;
      }
    }
    ParamNode node = new ParamNode(pos, value, interpolated);
    nodes.add(node);
    nodes.sort((a, b) -> Integer.compare(a.pos, b.pos));
  }

  public int getValueAt(int pos) {
    return getValueAt(pos, Integer.MAX_VALUE, false);
  }

  /**
   * C auto_param.cpp:1485-1533 (AutoParam::getValueAtPos) — returns {@link #currentValue} when
   * unautomated, steps to {@code leftNode.value} when {@code rightNode} is not interpolated, and
   * linearly interpolates between {@code leftNode} and {@code rightNode} when {@code
   * rightNode.interpolated} is true.
   */
  public int getValueAt(int pos, int loopLength, boolean reversed) {
    if (nodes.isEmpty()) {
      return currentValue;
    }

    int searchKey = pos + (reversed ? 0 : 1);
    int rightI = nodes.size();
    for (int i = 0; i < nodes.size(); i++) {
      if (nodes.get(i).pos >= searchKey) {
        rightI = i;
        break;
      }
    }
    if (rightI >= nodes.size()) {
      rightI = 0;
    }
    ParamNode rightNode = nodes.get(rightI);

    int leftI = rightI - 1;
    if (leftI < 0) {
      leftI += nodes.size();
    }
    ParamNode leftNode = nodes.get(leftI);

    if (!rightNode.interpolated) {
      return leftNode.value;
    }

    int ticksSinceLeftNode = pos - leftNode.pos;
    if (ticksSinceLeftNode == 0) {
      return leftNode.value;
    }

    int effectiveLength = loopLength > 0 ? loopLength : Integer.MAX_VALUE;
    if (ticksSinceLeftNode < 0) {
      if (effectiveLength == Integer.MAX_VALUE) {
        return rightNode.value;
      }
      ticksSinceLeftNode += effectiveLength;
    }

    int ticksBetweenNodes = rightNode.pos - leftNode.pos;
    if (ticksBetweenNodes <= 0) {
      if (effectiveLength == Integer.MAX_VALUE) {
        return leftNode.value;
      }
      ticksBetweenNodes += effectiveLength;
    }

    long valueDistance = (long) rightNode.value - (long) leftNode.value;
    return (int) (leftNode.value + (valueDistance * ticksSinceLeftNode) / ticksBetweenNodes);
  }

  public int valueIncrementPerHalfTick;
  public int ticksTilNextEvent = Integer.MAX_VALUE;

  /** C auto_param.h:134, auto_param.cpp:778-783 (AutoParam::hasInterpolationIncrement). */
  public boolean hasInterpolationIncrement() {
    return valueIncrementPerHalfTick != 0;
  }

  /**
   * C auto_param.cpp:1546-1579 (AutoParam::setPlayPos) + auto_param.cpp:810-835
   * (AutoParam::setupInterpolation).
   */
  public void setPlayPos(int pos, int loopLength, boolean reversed) {
    valueIncrementPerHalfTick = 0;
    if (nodes.isEmpty()) {
      return;
    }
    currentValue = getValueAt(pos, loopLength, reversed);

    int searchKey = pos + (reversed ? 0 : 1);
    int rightI = nodes.size();
    for (int i = 0; i < nodes.size(); i++) {
      if (nodes.get(i).pos >= searchKey) {
        rightI = i;
        break;
      }
    }
    if (rightI >= nodes.size()) {
      rightI = 0;
    }
    ParamNode nextNodeOurDirection = nodes.get(rightI);

    if (nextNodeOurDirection.interpolated) {
      if (reversed) {
        int leftI = rightI - 1;
        if (leftI < 0) {
          leftI += nodes.size();
        }
        nextNodeOurDirection = nodes.get(leftI);
      }

      int effectiveLength = loopLength > 0 ? loopLength : Integer.MAX_VALUE;
      int halfDistance = (nextNodeOurDirection.value >> 1) - (currentValue >> 1);
      if (halfDistance != 0) {
        int ticksTilNextNode = nextNodeOurDirection.pos - pos;
        if (reversed) {
          ticksTilNextNode = -ticksTilNextNode;
        }
        if (ticksTilNextNode <= 0) {
          ticksTilNextNode += effectiveLength;
        }
        if (ticksTilNextNode > 0) {
          valueIncrementPerHalfTick = halfDistance / ticksTilNextNode;
        }
      }
    }
  }

  public int processCurrentPos(
      int currentPos,
      int loopLength,
      boolean reversed,
      boolean didPingpong,
      boolean mayInterpolate) {
    if (nodes.isEmpty()) {
      return Integer.MAX_VALUE;
    }

    // C song.cpp:5822 — Song::getLoopLength() returns 2147483647 for Arrangement / infinite length.
    int effectiveLength = loopLength > 0 ? loopLength : Integer.MAX_VALUE;

    ParamNode nextNode = null;
    int minDistance = Integer.MAX_VALUE;

    for (ParamNode node : nodes) {
      int distance = node.pos - currentPos;
      if (reversed) {
        distance = -distance;
      }
      if (distance < 0) {
        distance += effectiveLength;
      }
      if (distance < minDistance) {
        minDistance = distance;
        nextNode = node;
      }
    }

    if (minDistance == 0 && nextNode != null) {
      // C auto_param.cpp:456 — stop any pre-existing interpolation upon reaching a node.
      valueIncrementPerHalfTick = 0;

      ParamNode followingNode = findNextNode(nextNode, reversed);
      boolean noNeedToJumpToValue = nextNode.interpolated && mayInterpolate;

      // C auto_param.cpp:439, 466-476 — when playing reversed across a non-interpolated node, jump
      // to nodeToLeft.value (followingNode.value), which holds the value of the span to the left.
      int valueJustReached =
          (reversed && !noNeedToJumpToValue) ? followingNode.value : nextNode.value;
      currentValue = valueJustReached;

      // C auto_param.cpp:747 — the segment to the left of nodeJustReached stores its interpolated
      // flag on nodeJustReached, while the segment to the right stores it on nodeToRight.
      boolean segmentInterpolated = (reversed ? nextNode : followingNode).interpolated;
      if (mayInterpolate && segmentInterpolated) {
        int dist = findDistanceToNext(nextNode, followingNode, reversed, effectiveLength);
        // C auto_param.cpp:817, 865 — halfDistance / ticksTilNextNode (change per half-tick).
        int halfDistance = (followingNode.value >> 1) - (currentValue >> 1);
        if (halfDistance != 0 && dist > 0) {
          valueIncrementPerHalfTick = halfDistance / dist;
        }
      }

      minDistance = findDistanceToNext(nextNode, followingNode, reversed, effectiveLength);
    }

    ticksTilNextEvent = minDistance;
    return minDistance;
  }

  private ParamNode findNextNode(ParamNode current, boolean reversed) {
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

  private int findDistanceToNext(
      ParamNode current, ParamNode next, boolean reversed, int effectiveLength) {
    int dist = next.pos - current.pos;
    if (reversed) dist = -dist;
    if (dist <= 0) dist += effectiveLength;
    return dist;
  }

  public void notifyPingpongOccurred() {
    valueIncrementPerHalfTick = -valueIncrementPerHalfTick;
  }

  /**
   * C auto_param.cpp:943-946 (saturatingIncrement) — multiplies a per-half-tick increment by a
   * number of half-ticks, clamping to int32 limits rather than wrapping.
   */
  private static int saturatingIncrement(int incrementPerHalfTick, long halfTicks) {
    long increment = (long) incrementPerHalfTick * halfTicks;
    if (increment > Integer.MAX_VALUE) {
      return Integer.MAX_VALUE;
    }
    if (increment < Integer.MIN_VALUE) {
      return Integer.MIN_VALUE;
    }
    return (int) increment;
  }

  /**
   * C auto_param.cpp:915-938 (AutoParam::applyValueIncrement) — adds {@code valueIncrement} with
   * saturation and resets {@link #valueIncrementPerHalfTick} if a rail was hit.
   */
  private boolean applyValueIncrement(int valueIncrement) {
    if (valueIncrement == 0) {
      return false;
    }
    int oldValue = currentValue;
    currentValue = Functions.add_saturate(currentValue, valueIncrement);
    boolean overflowOccurred =
        (valueIncrement > 0 && oldValue > Integer.MAX_VALUE - valueIncrement)
            || (valueIncrement < 0 && oldValue < Integer.MIN_VALUE - valueIncrement);
    if (overflowOccurred) {
      valueIncrementPerHalfTick = 0;
    }
    return currentValue != oldValue;
  }

  /**
   * C auto_param.cpp:948-972 (AutoParam::tickSamples) — scales {@link #valueIncrementPerHalfTick}
   * by {@code timePerTimerTickInverse} and {@code 6 * numSamples}.
   */
  public void tickSamples(int numSamples, int timePerTimerTickInverse) {
    if (valueIncrementPerHalfTick == 0) {
      return;
    }
    int step =
        Functions.multiply_32x32_rshift32_rounded(
            valueIncrementPerHalfTick, timePerTimerTickInverse);
    int valueIncrement = saturatingIncrement(step, 6L * numSamples);
    applyValueIncrement(valueIncrement);
  }

  /**
   * C auto_param.cpp:974-995 (AutoParam::tickTicks) — advances interpolation by {@code 2 *
   * numTicks} half-ticks.
   */
  public boolean tickTicks(int numTicks) {
    if (valueIncrementPerHalfTick == 0) {
      return false;
    }
    int valueIncrement = saturatingIncrement(valueIncrementPerHalfTick, 2L * numTicks);
    return applyValueIncrement(valueIncrement);
  }
}
