package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import org.deluge.BridgeContract;
import org.deluge.model.KitTrackModel;
import org.deluge.model.LfoModel;
import org.deluge.model.LfoType;
import org.deluge.model.SoundDrum;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

/** Verifies LfoPanel sync combo mapping between UI indices and (syncLevel, syncType). */
class LfoPanelSyncMappingTest {

  @Test
  void syncComboHelpersRoundTripEvenTripletAndDottedSelections() {
    // 0: OFF
    assertEquals(0, LfoPanel.syncComboIndexFor(0, 0));
    assertEquals(0, LfoPanel.syncComboIndexFor(0, 1));
    assertEquals(0, LfoPanel.syncLevelForComboIndex(0));
    assertEquals(0, LfoPanel.syncTypeForComboIndex(0));

    // 1..7: Even 1/1..1/64
    for (int level = 1; level <= 7; level++) {
      int idx = LfoPanel.syncComboIndexFor(level, 0);
      assertEquals(level, idx);
      assertEquals(level, LfoPanel.syncLevelForComboIndex(idx));
      assertEquals(0, LfoPanel.syncTypeForComboIndex(idx));
    }

    // 8..13: Triplet 1/2T..1/64T (syncLevel 2..7, syncType 1)
    for (int level = 2; level <= 7; level++) {
      int idx = LfoPanel.syncComboIndexFor(level, 1);
      assertEquals(8 + (level - 2), idx);
      assertEquals(level, LfoPanel.syncLevelForComboIndex(idx));
      assertEquals(1, LfoPanel.syncTypeForComboIndex(idx));
    }

    // 14..19: Dotted 1/2D..1/64D (syncLevel 2..7, syncType 2)
    for (int level = 2; level <= 7; level++) {
      int idx = LfoPanel.syncComboIndexFor(level, 2);
      assertEquals(14 + (level - 2), idx);
      assertEquals(level, LfoPanel.syncLevelForComboIndex(idx));
      assertEquals(2, LfoPanel.syncTypeForComboIndex(idx));
    }

    // Preset syncLevel > 7 clamps to 1/64 in the matching subdivision family
    assertEquals(13, LfoPanel.syncComboIndexFor(9, 1));
    assertEquals(19, LfoPanel.syncComboIndexFor(8, 2));
  }

  @Test
  void lfoPanelSyncComboLoadsAndSavesTripletAndDottedSyncTypes() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          SynthTrackModel model = new SynthTrackModel("Synth");
          // Initialize LFO 0 to 1/8T (syncLevel=4, syncType=1)
          model.setLfo(0, new LfoModel(1.0f, LfoType.SINE, 0.5f, "Filter", false, 4, 1));

          LfoPanel panel = new LfoPanel(model, 0);
          List<JComboBox<?>> syncCombos = findSyncCombos(panel);
          JComboBox<?> lfo0Sync = syncCombos.get(0);

          assertEquals("1/8T", lfo0Sync.getSelectedItem());
          assertEquals(10, lfo0Sync.getSelectedIndex());

          // Select "1/16D" (index 17 -> syncLevel=5, syncType=2)
          lfo0Sync.setSelectedIndex(17);
          LfoModel updated = model.getLfo(0);
          assertEquals(5, updated.syncLevel());
          assertEquals(2, updated.syncType());

          // Select "OFF" (index 0 -> syncLevel=0, syncType=0)
          lfo0Sync.setSelectedIndex(0);
          LfoModel off = model.getLfo(0);
          assertEquals(0, off.syncLevel());
          assertEquals(0, off.syncType());
        });
  }

  @Test
  void swingKitConfigDialogMapsSidechainSyncLevelAndSyncTypeSeparately() throws Exception {
    if (GraphicsEnvironment.isHeadless()) {
      return;
    }
    SwingUtilities.invokeAndWait(
        () -> {
          KitTrackModel kit = new KitTrackModel("Kit");
          SoundDrum drum = new SoundDrum("Kick");
          drum.setSidechainSyncLevel(4); // 1/8
          drum.setSidechainSyncType(1); // Triplet
          kit.addDrum(drum);

          BridgeContract bridge = new BridgeContract();
          SwingKitConfigDialog dialog = new SwingKitConfigDialog(null, kit, bridge, 0);
          try {
            List<JComboBox<?>> tenItemCombos = findCombosWithSize(dialog.getContentPane(), 10);
            List<JComboBox<?>> threeItemCombos = findCombosWithSize(dialog.getContentPane(), 3);

            JComboBox<?> scSyncCombo =
                tenItemCombos.stream()
                    .filter(c -> "Off".equals(c.getItemAt(0)) && "1/256".equals(c.getItemAt(9)))
                    .findFirst()
                    .orElseThrow();
            JComboBox<?> scTypeCombo =
                threeItemCombos.stream()
                    .filter(c -> "Even".equals(c.getItemAt(0)) && "Dotted".equals(c.getItemAt(2)))
                    .findFirst()
                    .orElseThrow();

            assertEquals(4, scSyncCombo.getSelectedIndex());
            assertEquals("1/8", scSyncCombo.getSelectedItem());
            assertEquals(1, scTypeCombo.getSelectedIndex());
            assertEquals("Triplet", scTypeCombo.getSelectedItem());

            scSyncCombo.setSelectedIndex(6); // 1/32
            scTypeCombo.setSelectedIndex(2); // Dotted
            assertEquals(6, drum.getSidechainSyncLevel());
            assertEquals(6, bridge.getKitSidechainSyncLevel(0));
            assertEquals(2, drum.getSidechainSyncType());
            assertEquals(2, bridge.getKitSidechainSyncType(0));
          } finally {
            dialog.dispose();
          }
        });
  }

  private static List<JComboBox<?>> findSyncCombos(Container root) {
    return findCombosWithSize(root, 20);
  }

  private static List<JComboBox<?>> findCombosWithSize(Container root, int itemCount) {
    List<JComboBox<?>> result = new ArrayList<>();
    for (Component comp : root.getComponents()) {
      if (comp instanceof JComboBox<?> combo && combo.getItemCount() == itemCount) {
        result.add(combo);
      } else if (comp instanceof Container child) {
        result.addAll(findCombosWithSize(child, itemCount));
      }
    }
    return result;
  }
}
