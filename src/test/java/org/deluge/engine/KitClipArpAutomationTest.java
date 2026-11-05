package org.deluge.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.deluge.firmware2.Param;
import org.deluge.model.ArpModel;
import org.deluge.model.ClipModel;
import org.deluge.model.KitTrackModel;
import org.deluge.modulation.automation.ParamNode;
import org.junit.jupiter.api.Test;

/**
 * A kit clip's {@code <kitParams>} unpatched automation has to reach the kit arp.
 *
 * <p>The C reads a kit's unpatched params out of the active clip's ParamManager on every render —
 * {@code Kit::setupAndRenderArpPreOutput} is handed {@code paramManager = getParamManager(song)},
 * calls {@code arpSettings->updateParamsFromUnpatchedParamSet(unpatchedParams)} and takes the gate
 * from {@code unpatchedParams->getValue(UNPATCHED_ARP_GATE)} (kit.cpp:709-724).
 *
 * <p>Here the curves were parsed into {@code ClipModel.getAutomationCurves()} and advanced on the
 * clip's timeline, but nothing recorded them into a ParamManager for a kit, so no consumer could
 * see them: the kit arp ran at whatever {@code syncKitTopLevelParams} wrote at load, for the whole
 * clip.
 */
class KitClipArpAutomationTest {

  private static KitTrackModel lastTrack;

  private static final int GATE_AT_START = 0x20000000;
  private static final int GATE_LATER = 0x70000000;

  /** A kit track whose single clip automates the arp gate from one value to another. */
  private static ClipModel kitClipWithGateAutomation(FirmwareKit kit) {
    KitTrackModel track = new KitTrackModel("KIT");
    lastTrack = track;
    ClipModel clip = new ClipModel("c", 8, 16);
    clip.setArpFromXml(
        ArpModel.defaultConfig().toBuilder().active(true).mode("arp").kitArp(1).build());
    // Two nodes, as <kitParams> automation parses into (SongXmlParser.parseKitParamsElement ->
    // parseHexGlobalEffectableAutomation -> clip.getAutomationCurves()).
    clip.getAutomationCurves()
        .put(
            "arpeggiatorGate",
            List.of(new ParamNode(0, GATE_AT_START, false), new ParamNode(48, GATE_LATER, false)));
    clip.setSound(kit);
    track.addClip(clip);
    track.setActiveClipIndex(0);
    FirmwareFactory.syncKitTopLevelParams(track, kit);
    return clip;
  }

  @Test
  void aKitClipsArpGateCurveIsRecordedAndReachesTheArp() {
    FirmwareKit kit = new FirmwareKit();
    ClipModel clip = kitClipWithGateAutomation(kit);

    assertTrue(
        clip.getTimelineParamManager().mightContainAutomation(),
        "the clip's <kitParams> curve must be recorded into the ParamManager the timeline ticks");
    var auto = clip.getTimelineParamManager().getAutomatedParam(Param.UNPATCHED_ARP_GATE);
    assertTrue(auto != null && !auto.nodes.isEmpty(), "and specifically as UNPATCHED_ARP_GATE");

    // Advance the clip's timeline the way ClipModel.processCurrentPos does, and read the gate off
    // the kit after each step.
    int loopLength = 16 * 24;
    clip.getTimelineParamManager().processCurrentPos(0, loopLength, false, false, true);
    kit.applyClipArpAutomation();
    assertEquals(
        GATE_AT_START,
        kit.arpGateKnob,
        "at pos 0 the arp gate must come from the curve, not from the load-time knob");

    clip.getTimelineParamManager().processCurrentPos(48, loopLength, false, false, true);
    kit.applyClipArpAutomation();
    assertEquals(
        GATE_LATER, kit.arpGateKnob, "the arp gate must track the curve as the clip plays");
    assertNotEquals(
        GATE_AT_START, kit.arpGateKnob, "a static gate for the whole clip is the bug being fixed");
  }

  @Test
  void aKitClipsArpRateCurveIsRecordedAsTheUNPATCHEDRateAndReachesTheArp() {
    // getParamIdFromName answers for a VOICE, where the arp rate is the patched GLOBAL_ARP_RATE
    // (54) reached through the patcher. A kit has no voice — Kit::setupAndRenderArpPreOutput reads
    // its rate from the unpatched set, UNPATCHED_ARP_RATE (120), at kit.cpp:723. Since 54 is below
    // UNPATCHED_START (90), the unpatched filter discarded every kit rate curve until the kit path
    // remapped the id.
    FirmwareKit kit = new FirmwareKit();
    KitTrackModel track = new KitTrackModel("KIT");
    ClipModel clip = new ClipModel("c", 8, 16);
    clip.setArpFromXml(
        ArpModel.defaultConfig().toBuilder().active(true).mode("arp").kitArp(1).build());
    clip.getAutomationCurves().put("arpeggiatorRate", List.of(new ParamNode(0, 0x55000000, false)));
    clip.setSound(kit);
    track.addClip(clip);
    track.setActiveClipIndex(0);
    FirmwareFactory.syncKitTopLevelParams(track, kit);

    assertEquals(
        null,
        clip.getTimelineParamManager().getAutomatedParam(Param.GLOBAL_ARP_RATE),
        "a kit rate curve must NOT be recorded under the patched GLOBAL_ARP_RATE id");
    var rate = clip.getTimelineParamManager().getAutomatedParam(Param.UNPATCHED_ARP_RATE);
    assertTrue(
        rate != null && !rate.nodes.isEmpty(),
        "it must be recorded as UNPATCHED_ARP_RATE, which is what the kit arp reads");

    clip.getTimelineParamManager().processCurrentPos(0, 16 * 24, false, false, true);
    kit.applyClipArpAutomation();
    assertEquals(
        0x55000000,
        kit.arpRateKnob,
        "and must reach arpRateKnob — UNPATCHED_ARP_RATE (120) is outside the"
            + " ARP_GATE..SPREAD_VELOCITY block (101..115), so it needs reading separately");
  }

  @Test
  void aLiveRefreshBetweenNodesDoesNotResetTheAutomatedGate() {
    // A freshly recorded AutoParam starts at a zero current value with no slope, and
    // SwingKitConfigDialog re-applies the whole kit model at 5 Hz while playing — so without a seek
    // to the clip's current position an automated gate was knocked back to zero five times a
    // second. The C never rebuilds a ParamManager, so it has no equivalent step.
    FirmwareKit kit = new FirmwareKit();
    ClipModel clip = kitClipWithGateAutomation(kit);
    KitTrackModel owner = lastTrack;

    // Move the CLIP itself — setPos is what sets lastProcessedPos, which is what the seek reads.
    // Advancing only the ParamManager leaves lastProcessedPos at zero, and with both nodes
    // non-interpolated the gate at pos 24 equals the gate at pos 0, so such a test would also pass
    // if the refresh always seeked to zero. Position 72 is past the second node (48), so its value
    // DIFFERS from the start and a wrong-position seek is detectable.
    clip.setPos(72);
    kit.applyClipArpAutomation();
    int gateMidCurve = kit.arpGateKnob;
    assertEquals(
        GATE_LATER,
        gateMidCurve,
        "past the second node the gate must be the later value, or this test cannot tell a"
            + " wrong-position seek from a right one");
    assertNotEquals(GATE_AT_START, gateMidCurve, "and must not still be the value from position 0");

    // The 5 Hz live refresh: rebuilds the automation under the playing clip.
    FirmwareFactory.syncKitTopLevelParams(owner, kit);
    kit.applyClipArpAutomation();

    assertEquals(
        gateMidCurve,
        kit.arpGateKnob,
        "a live refresh must leave the automated gate where the clip's position puts it, not at"
            + " the start of the curve or at zero");
  }

  @Test
  void stepAutomationKeepsZerosAndUsesTheFullQ31Range() {
    // The step-array form of automation (ClipModel.setAutomation) is normalized 0..1 with -1
    // meaning "no node here" (resizeAutomationArrays fills with -1f). Two things followed from
    // copying the synth path's conversion:
    //   * `> 0.0f` dropped a step automated to ZERO, which is a real value — a gate automated to 0
    //     is not an absent node;
    //   * scaling by MAX mapped 0..1 into the POSITIVE HALF of signed q31 only, so 0.5 landed near
    //     75% instead of the midpoint, and every rate value was biased upward.
    FirmwareKit kit = new FirmwareKit();
    KitTrackModel track = new KitTrackModel("KIT");
    ClipModel clip = new ClipModel("c", 8, 16);
    clip.setArpFromXml(
        ArpModel.defaultConfig().toBuilder().active(true).mode("arp").kitArp(1).build());
    clip.setAutomation("arpeggiatorGate", 0, 0.5f);
    clip.setAutomation("arpeggiatorGate", 1, 0.0f);
    clip.setSound(kit);
    track.addClip(clip);
    track.setActiveClipIndex(0);
    FirmwareFactory.syncKitTopLevelParams(track, kit);

    var gate = clip.getTimelineParamManager().getAutomatedParam(Param.UNPATCHED_ARP_GATE);
    assertTrue(gate != null && !gate.nodes.isEmpty(), "the step automation must be recorded");
    assertEquals(2, gate.nodes.size(), "BOTH steps, including the one automated to zero");

    // 0.5 -> the midpoint of signed q31, i.e. ~0, not ~0x3FFFFFFF.
    int half = gate.nodes.get(0).value;
    assertTrue(
        Math.abs((long) half) < 0x01000000L,
        "0.5 must map to the signed-q31 midpoint; got "
            + half
            + " (positive-half scaling of 0.5"
            + " would give about "
            + (int) (0.5 * 2147483647.0)
            + ")");
    // 0.0 -> the bottom of the range.
    assertEquals(
        Integer.MIN_VALUE, gate.nodes.get(1).value, "0.0 must map to the bottom of the range");
  }

  @Test
  void advancingAKitClipsAutomationTakesTheKitMonitor() {
    // syncKitTopLevelParams rebuilds this ParamManager under `synchronized (kit)`, and
    // recordParamValue publishes each AutoParam BEFORE filling its ordinary ArrayList of nodes — so
    // a sequencer-side read during a rebuild could see a half-built curve or throw
    // ConcurrentModificationException while nodes are added and sorted. The kit dialog makes that
    // rebuild happen at 5 Hz during playback.
    //
    // Decisive rather than circumstantial: seekTimelineParamManagerToCurrentPos takes exactly one
    // lock, so whether it blocks on a parked holder of the kit monitor distinguishes "reader takes
    // the writers' lock" from "reader takes something else" completely.
    FirmwareKit kit = new FirmwareKit();
    ClipModel clip = kitClipWithGateAutomation(kit);
    assertTrue(
        clip.getTimelineParamManager().mightContainAutomation(),
        "the clip must have automation, or the seek returns early and proves nothing");

    java.util.concurrent.CountDownLatch holding = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    Thread holder =
        new Thread(
            () -> {
              synchronized (kit) {
                holding.countDown();
                try {
                  release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }
            });
    holder.start();
    try {
      assertTrue(holding.await(10, java.util.concurrent.TimeUnit.SECONDS), "holder must take it");
      // Both reader entry points, so neither is left outside the lock: the seek and the
      // per-tick advance. processCurrentPos also has its PRESENCE CHECK inside the critical
      // section, so it cannot observe the momentarily empty manager a rebuild passes through.
      Thread seeker =
          new Thread(
              () -> {
                clip.seekTimelineParamManagerToCurrentPos();
                clip.processCurrentPos(1);
              });
      seeker.start();
      seeker.join(400);
      boolean blocked = seeker.isAlive();
      release.countDown();
      seeker.join(10_000);
      assertTrue(
          blocked,
          "advancing a kit clip's automation must block on the KIT monitor, which is the lock its"
              + " rebuild is performed under");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    } finally {
      release.countDown();
    }
  }

  @Test
  void aSequencedDrumNoteOnSeesTheCurrentAutomationNotTheLastTicksValue() {
    // A sequenced note-on runs after the clip advances its automation but BEFORE the kit's next arp
    // tick, so triggerDrum used to read the previous arpSettings. For a drum routed through the kit
    // arp that is audible: a noteProbability automated from 100% to 0% on the hit's own tick would
    // let the hit sound anyway. The C has no gap — it reads the unpatched set at the point of use
    // (kit.cpp:714).
    FirmwareKit kit = new FirmwareKit();
    KitTrackModel track = new KitTrackModel("KIT");
    ClipModel clip = new ClipModel("c", 8, 16);
    clip.setArpFromXml(
        ArpModel.defaultConfig().toBuilder().active(true).mode("arp").kitArp(1).build());
    clip.getAutomationCurves()
        .put(
            "noteProbability",
            List.of(
                new ParamNode(0, Integer.MAX_VALUE, false), // 100% at the start
                new ParamNode(48, Integer.MIN_VALUE, false))); // 0% from tick 48
    clip.setSound(kit);
    track.addClip(clip);
    track.setActiveClipIndex(0);
    FirmwareFactory.syncKitTopLevelParams(track, kit);

    // Park the clip in the 0% span and advance its automation, as the sequencer does...
    clip.setPos(72);
    int automated =
        clip.getTimelineParamManager()
            .getAutomatedParam(Param.UNPATCHED_NOTE_PROBABILITY)
            .currentValue;
    assertEquals(
        Integer.MIN_VALUE,
        automated,
        "the clip must be parked in the 0% span, or this proves nothing");

    // ...and then the note-on arrives, with NO explicit applyClipArpAutomation in between. That is
    // the gap: the arp tick that would have applied it has not happened yet.
    kit.triggerDrum(0, 100);

    assertEquals(
        0,
        kit.arpSettings.noteProbability,
        "triggerDrum must see the automation the clip has already advanced to, not the value from"
            + " the previous tick");
  }

  @Test
  void onlyUnpatchedParamsAreRecordedForAKit() {
    // A kit clip's <kitParams> describes the kit BUS, not a voice, so patched ids have no meaning
    // here — recording them would put voice automation on a kit's ParamManager.
    //
    // Asserting against the id the mapper ACTUALLY returns: "volume" maps to
    // GLOBAL_VOLUME_POST_FX (45), not LOCAL_VOLUME. An earlier version of this test asserted
    // LOCAL_VOLUME was absent and refreshed a brand-new track with no clips, so it passed with the
    // filtering removed entirely — twice vacuous.
    FirmwareKit kit = new FirmwareKit();
    ClipModel clip = kitClipWithGateAutomation(kit);
    KitTrackModel owner = lastTrack;
    clip.getAutomationCurves().put("volume", List.of(new ParamNode(0, 0x40000000, false)));
    FirmwareFactory.syncKitTopLevelParams(owner, kit);

    assertEquals(
        Param.GLOBAL_VOLUME_POST_FX,
        FirmwareFactory.getParamIdFromName("volume"),
        "the mapper must still return the patched id, or this test targets the wrong one");
    assertEquals(
        null,
        clip.getTimelineParamManager().getAutomatedParam(Param.GLOBAL_VOLUME_POST_FX),
        "a patched param must not be recorded onto a kit's ParamManager");
    assertTrue(
        clip.getTimelineParamManager().getAutomatedParam(Param.UNPATCHED_ARP_GATE) != null,
        "while the unpatched one alongside it still is — so the filter is selective, not a no-op");
  }

  /**
   * What this class does NOT test, said plainly rather than left implied: that a different gate
   * VALUE changes the rendered audio. That is a property of the kit arpeggiator itself, ported in
   * #138, not of this automation path, and {@code KitArpeggiatorTest} already drives it — it drops
   * {@code kit.arpGateKnob} to {@code Integer.MIN_VALUE} and renders to force a post-arp note-off
   * through gate expiry (C kit.cpp:737, 754). Asserting it again here would conflate the two.
   *
   * <p>An audio probe was attempted and removed rather than weakened: a bare kit slot has no note
   * tails, so {@code bypassesKitArp} routes it straight to the drum (C kit.cpp:1303-1318) and the
   * kit arp never runs at all. The first version of it also used {@code getActiveVoiceCount()},
   * which is useless for this — a released drum voice stays in its envelope release and keeps
   * counting as active, so both gates read [1,1,...].
   */
}
