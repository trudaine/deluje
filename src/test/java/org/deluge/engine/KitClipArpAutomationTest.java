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

    // Park the clip BETWEEN the two nodes (0 and 48).
    clip.getTimelineParamManager().processCurrentPos(0, 16 * 24, false, false, true);
    clip.getTimelineParamManager().processCurrentPos(24, 16 * 24, false, false, true);
    kit.applyClipArpAutomation();
    int gateMidCurve = kit.arpGateKnob;
    assertNotEquals(0, gateMidCurve, "the gate must be driven mid-curve, or this proves nothing");

    // The 5 Hz live refresh: rebuilds the automation under the playing clip.
    FirmwareFactory.syncKitTopLevelParams(owner, kit);
    kit.applyClipArpAutomation();

    assertEquals(
        gateMidCurve,
        kit.arpGateKnob,
        "a live refresh must leave the automated gate where the clip's position puts it");
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
