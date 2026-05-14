package org.chuck.deluge.engine;

/**
 * Lock-free ring buffer for sample-accurate voice scheduling events.
 *
 * <p>The clock thread writes scheduled note-on/off events; the audio render loop reads them before
 * each buffer render, dispatching voice triggers at precise sample positions within the buffer.
 *
 * <p>This is a single-producer (clock thread), single-consumer (audio thread) ring buffer — no
 * locks needed.
 */
public class TickEventQueue {

  /** Command types. */
  public enum Command {
    SYNTH_NOTE_ON,
    SYNTH_NOTE_OFF,
    SAMPLE_NOTE_ON,
    SAMPLE_NOTE_OFF,
    DX7_NOTE_ON,
    DX7_NOTE_OFF,
    ALL_NOTES_OFF
  }

  /** A single scheduled event. */
  public static final class Event {
    public Command command;
    public int track;
    public int midiNote;
    public int velocity;
    public int slotHint; // preferred voice slot (-1 = allocator decides)
    public float gain;
    public double cutoff;
    public double resonance;
    public double attack;
    public double decay;
    public double sustain;
    public double release;
    public String samplePath;
    public double hpfFreq;
    public double hpfRes;

    public void set(Command cmd, int track, int midiNote, int velocity, int slotHint) {
      this.command = cmd;
      this.track = track;
      this.midiNote = midiNote;
      this.velocity = velocity;
      this.slotHint = slotHint;
      this.gain = 0.15f;
      this.cutoff = 1000.0;
      this.resonance = 0.1;
      this.attack = 0.01;
      this.decay = 0.1;
      this.sustain = 0.7;
      this.release = 0.2;
      this.samplePath = null;
      this.hpfFreq = 20.0;
      this.hpfRes = 0.707;
    }

    public void setSample(String path) {
      this.samplePath = path;
    }

    public void setADSR(double a, double d, double s, double r) {
      this.attack = a;
      this.decay = d;
      this.sustain = s;
      this.release = r;
    }

    public void setFilter(double c, double r) {
      this.cutoff = c;
      this.resonance = r;
    }

    public void setHpf(double freq, double res) {
      this.hpfFreq = freq;
      this.hpfRes = res;
    }

    public void clear() {
      this.command = null;
      this.track = -1;
      this.midiNote = 0;
      this.velocity = 0;
      this.slotHint = -1;
      this.samplePath = null;
    }
  }

  private static final int QUEUE_SIZE = 4096;
  private final Event[] events = new Event[QUEUE_SIZE];
  // Single-producer, single-consumer: volatile head/tail with wrap-around
  private volatile int writeHead = 0;
  private volatile int readTail = 0;

  public TickEventQueue() {
    for (int i = 0; i < QUEUE_SIZE; i++) {
      events[i] = new Event();
    }
  }

  /**
   * Push an event (called from clock thread — single producer).
   *
   * @return true if queued, false if full
   */
  public boolean push(Event template) {
    int next = (writeHead + 1) & (QUEUE_SIZE - 1);
    if (next == readTail) return false; // full

    // Copy from template
    Event e = events[writeHead];
    e.command = template.command;
    e.track = template.track;
    e.midiNote = template.midiNote;
    e.velocity = template.velocity;
    e.slotHint = template.slotHint;
    e.gain = template.gain;
    e.cutoff = template.cutoff;
    e.resonance = template.resonance;
    e.attack = template.attack;
    e.decay = template.decay;
    e.sustain = template.sustain;
    e.release = template.release;
    e.samplePath = template.samplePath;
    e.hpfFreq = template.hpfFreq;
    e.hpfRes = template.hpfRes;

    writeHead = next;
    return true;
  }

  /** Convenience: push a note-on event. */
  public boolean pushNoteOn(Command cmd, int track, int midiNote, int velocity) {
    int next = (writeHead + 1) & (QUEUE_SIZE - 1);
    if (next == readTail) return false;

    Event e = events[writeHead];
    e.set(cmd, track, midiNote, velocity, -1);
    writeHead = next;
    return true;
  }

  /** Convenience: push a note-off event. */
  public boolean pushNoteOff(Command cmd, int track, int midiNote) {
    int next = (writeHead + 1) & (QUEUE_SIZE - 1);
    if (next == readTail) return false;

    Event e = events[writeHead];
    e.set(cmd, track, midiNote, 0, -1);
    e.command = cmd; // override to off variant
    // For note-off, use the corresponding OFF command
    switch (cmd) {
      case SYNTH_NOTE_ON -> e.command = Command.SYNTH_NOTE_OFF;
      case SAMPLE_NOTE_ON -> e.command = Command.SAMPLE_NOTE_OFF;
      case DX7_NOTE_ON -> e.command = Command.DX7_NOTE_OFF;
    }
    writeHead = next;
    return true;
  }

  /** Send ALL_NOTES_OFF to clear all playing voices on the audio thread. */
  public boolean pushAllNotesOff() {
    int next = (writeHead + 1) & (QUEUE_SIZE - 1);
    if (next == readTail) return false;

    Event e = events[writeHead];
    e.command = Command.ALL_NOTES_OFF;
    writeHead = next;
    return true;
  }

  /**
   * Drain all pending events from the queue (called from audio thread — single consumer). Calls the
   * provided handler for each event.
   */
  public int drain(EventHandler handler) {
    int count = 0;
    while (readTail != writeHead) {
      handler.handle(events[readTail]);
      events[readTail].clear();
      readTail = (readTail + 1) & (QUEUE_SIZE - 1);
      count++;
    }
    return count;
  }

  /** Whether the queue has pending events. */
  public boolean hasEvents() {
    return readTail != writeHead;
  }

  @FunctionalInterface
  public interface EventHandler {
    void handle(Event event);
  }
}
