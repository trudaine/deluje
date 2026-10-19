package org.deluge.modulation.midi;

import java.util.List;
import org.deluge.modulation.automation.AutoParam;
import org.deluge.modulation.automation.ParamNode;

/**
 * Port of {@code MIDIParam} ({@code modulation/midi/midi_param.h:22-28}). Pairs a MIDI CC number
 * with its {@link AutoParam} timeline state.
 */
public class MidiParam {
  public final int cc;
  public final AutoParam param;

  public MidiParam(int cc) {
    this.cc = cc;
    this.param = new AutoParam(cc);
  }

  public MidiParam(int cc, int baseValueQ31, List<ParamNode> nodes) {
    this(cc);
    this.param.currentValue = baseValueQ31;
    if (nodes != null) {
      for (ParamNode node : nodes) {
        this.param.nodes.add(new ParamNode(node.pos, node.value, node.interpolated));
      }
    }
  }

  public MidiParam deepCopy() {
    return new MidiParam(cc, param.currentValue, param.nodes);
  }
}
