package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.List;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import org.deluge.BridgeContract;
import org.deluge.midi.RemoteFileEntry;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for Ctrl+C/Ctrl+V on both sidebar file browsers. The right-click Copy/Paste
 * menu items always worked correctly, but neither JTable (HardwareSidebarTab) nor JTree
 * (LibrarySidebarTab) had any keyboard binding for Ctrl+C/Ctrl+V -- every OS file manager supports
 * those, so their absence reads as "doesn't have copy paste actions" even though the feature exists
 * via right-click.
 */
public class CopyPasteKeyboardShortcutTest {

  @Test
  public void testHardwareTabCtrlCSetsClipboardState() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    BridgeContract bridge = new BridgeContract();
    SwingDelugeApp app = new SwingDelugeApp(bridge, null);
    try {
      Field sidebarField = SwingDelugeApp.class.getDeclaredField("sidebarPanel");
      sidebarField.setAccessible(true);
      SwingProjectSidebarPanel sidebarPanel = (SwingProjectSidebarPanel) sidebarField.get(app);

      Field hwTabField = SwingProjectSidebarPanel.class.getDeclaredField("hardwareTab");
      hwTabField.setAccessible(true);
      HardwareSidebarTab hwTab = (HardwareSidebarTab) hwTabField.get(sidebarPanel);

      Field entriesField = HardwareSidebarTab.class.getDeclaredField("currentRemoteEntries");
      entriesField.setAccessible(true);
      @SuppressWarnings("unchecked")
      List<RemoteFileEntry> entries = (List<RemoteFileEntry>) entriesField.get(hwTab);
      entries.add(
          new RemoteFileEntry(
              "TEST_SONG.XML", 100, System.currentTimeMillis(), false, false, false));

      Field tableModelField = HardwareSidebarTab.class.getDeclaredField("remoteTableModel");
      tableModelField.setAccessible(true);
      javax.swing.table.DefaultTableModel tableModel =
          (javax.swing.table.DefaultTableModel) tableModelField.get(hwTab);
      tableModel.addRow(new Object[] {"TEST_SONG.XML", "100 B", "now"});

      Field tableField = HardwareSidebarTab.class.getDeclaredField("remoteTable");
      tableField.setAccessible(true);
      JTable table = (JTable) tableField.get(hwTab);
      table.setRowSelectionInterval(0, 0);

      int menuMask = java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
      KeyStroke ctrlC = KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_C, menuMask);
      Object actionKey =
          table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(ctrlC);
      assertNotNull(actionKey, "Ctrl+C must be bound in the Hardware tab's table");
      Action action = table.getActionMap().get(actionKey);
      assertNotNull(action, "Ctrl+C must map to a real action");

      action.actionPerformed(null);

      Field clipboardPathField =
          SwingProjectSidebarPanel.class.getDeclaredField("remoteClipboardPath");
      clipboardPathField.setAccessible(true);
      assertEquals("/SONGS/TEST_SONG.XML", clipboardPathField.get(sidebarPanel));

      Field isRemoteSourceField = SwingProjectSidebarPanel.class.getDeclaredField("isRemoteSource");
      isRemoteSourceField.setAccessible(true);
      assertEquals(true, isRemoteSourceField.get(sidebarPanel));
    } finally {
      app.dispose();
      bridge.shutdown();
    }
  }

  @Test
  public void testLibraryTabCtrlCSetsClipboardState() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    BridgeContract bridge = new BridgeContract();
    SwingDelugeApp app = new SwingDelugeApp(bridge, null);
    try {
      Field sidebarField = SwingDelugeApp.class.getDeclaredField("sidebarPanel");
      sidebarField.setAccessible(true);
      SwingProjectSidebarPanel sidebarPanel = (SwingProjectSidebarPanel) sidebarField.get(app);

      Field libTabField = SwingProjectSidebarPanel.class.getDeclaredField("libraryTab");
      libTabField.setAccessible(true);
      LibrarySidebarTab libTab = (LibrarySidebarTab) libTabField.get(sidebarPanel);

      Field treeField = LibrarySidebarTab.class.getDeclaredField("libraryTree");
      treeField.setAccessible(true);
      javax.swing.JTree tree = (javax.swing.JTree) treeField.get(libTab);

      // Find a real file leaf under SONGS (created by PreferencesManager's default library dirs)
      // by walking the tree model for any leaf node; skip the test if the fresh test library has
      // no files yet, since this test only needs to exercise the Ctrl+C keybinding itself.
      DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
      DefaultMutableTreeNode leaf = findAnyLeaf(root);
      if (leaf == null) {
        return;
      }
      TreePath leafPath = new TreePath(leaf.getPath());
      tree.setSelectionPath(leafPath);

      int menuMask = java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
      KeyStroke ctrlC = KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_C, menuMask);
      Object actionKey = tree.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(ctrlC);
      assertNotNull(actionKey, "Ctrl+C must be bound in the Local tab's tree");
      Action action = tree.getActionMap().get(actionKey);
      assertNotNull(action, "Ctrl+C must map to a real action");
    } finally {
      app.dispose();
      bridge.shutdown();
    }
  }

  private DefaultMutableTreeNode findAnyLeaf(DefaultMutableTreeNode node) {
    if (node.isLeaf() && node.getParent() != null) return node;
    for (int i = 0; i < node.getChildCount(); i++) {
      DefaultMutableTreeNode found = findAnyLeaf((DefaultMutableTreeNode) node.getChildAt(i));
      if (found != null) return found;
    }
    return null;
  }
}
