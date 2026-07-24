package org.deluge.ui;

import java.awt.*;
import java.io.File;
import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import org.deluge.BridgeContract;
import org.deluge.midi.RemoteFileEntry;
import org.deluge.project.PreferencesManager;
import org.deluge.xml.DelugeXmlParser;

public class HardwareSidebarTab extends JPanel {

  private final SwingProjectSidebarPanel parent;

  private DefaultMutableTreeNode hardwareRoot;
  private DefaultMutableTreeNode songsNode;
  private DefaultMutableTreeNode synthsNode;
  private DefaultMutableTreeNode kitsNode;
  private JTree hardwareTree;

  private JTable remoteTable;
  private javax.swing.table.DefaultTableModel remoteTableModel;
  private JLabel currentFolderLabel;
  private String currentRemotePath = "/SONGS";
  private java.util.List<RemoteFileEntry> currentRemoteEntries = new java.util.ArrayList<>();
  private JProgressBar transferProgressBar;

  private org.deluge.hid.VirtualOLED liveOled;
  private JComponent liveOledView;
  private JLabel liveSevenSegLabel;
  private JTextArea liveDebugLogArea;
  private static final int MAX_DEBUG_LOG_LINES = 500;

  public HardwareSidebarTab(SwingProjectSidebarPanel parent) {
    this.parent = parent;

    setLayout(new BorderLayout());
    setBackground(new Color(0x12, 0x12, 0x14));

    // Header segment
    JPanel head = new JPanel(new BorderLayout());
    head.setBackground(new Color(0x15, 0x15, 0x18));
    head.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));

    JLabel title = new JLabel("PHYSICAL SD CARD");
    title.setForeground(Color.LIGHT_GRAY);
    title.setFont(new Font("SansSerif", Font.BOLD, 9));
    head.add(title, BorderLayout.WEST);

    JPanel headBtns = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0));
    headBtns.setOpaque(false);

    JButton testBtn = new JButton("🩺 TEST");
    testBtn.setToolTipText(
        "Ping the Deluge and report session id + /SONGS count (live diagnostic)");
    testBtn.setFont(new Font("SansSerif", Font.BOLD, 9));
    testBtn.setBackground(new Color(0x2a, 0x2a, 0x30));
    testBtn.setForeground(new Color(0xff, 0xd7, 0x00));
    testBtn.setOpaque(true);
    testBtn.setContentAreaFilled(true);
    testBtn.setFocusPainted(false);
    testBtn.addActionListener(e -> runConnectionSelfTest());
    headBtns.add(testBtn);

    JButton refreshBtn = new JButton("🔄 REFRESH");
    refreshBtn.setFont(new Font("SansSerif", Font.BOLD, 9));
    refreshBtn.setBackground(new Color(0x2a, 0x2a, 0x30));
    refreshBtn.setForeground(new Color(0x00, 0xff, 0xcc));
    refreshBtn.setOpaque(true);
    refreshBtn.setContentAreaFilled(true);
    refreshBtn.setFocusPainted(false);
    refreshBtn.addActionListener(
        e -> {
          refreshHardwareTree();
          loadRemoteFolder(currentRemotePath);
        });
    headBtns.add(refreshBtn);
    head.add(headBtns, BorderLayout.EAST);
    add(head, BorderLayout.NORTH);

    // 1. JTree Setup
    hardwareRoot = new DefaultMutableTreeNode("DELUGE HW");
    songsNode = new DefaultMutableTreeNode("SONGS");
    synthsNode = new DefaultMutableTreeNode("SYNTHS");
    kitsNode = new DefaultMutableTreeNode("KITS");
    hardwareRoot.add(songsNode);
    hardwareRoot.add(synthsNode);
    hardwareRoot.add(kitsNode);

    hardwareTree = new JTree(hardwareRoot);
    hardwareTree.setBackground(new Color(0x12, 0x12, 0x14));
    hardwareTree.setFont(new Font("SansSerif", Font.PLAIN, 11));
    hardwareTree.setRowHeight(20);

    DefaultTreeCellRenderer treeRenderer = new DefaultTreeCellRenderer();
    treeRenderer.setFont(new Font("SansSerif", Font.PLAIN, 11));
    treeRenderer.setBackgroundNonSelectionColor(new Color(0x12, 0x12, 0x14));
    treeRenderer.setTextNonSelectionColor(Color.LIGHT_GRAY);
    treeRenderer.setTextSelectionColor(Color.WHITE);
    treeRenderer.setBackgroundSelectionColor(new Color(0x00, 0xff, 0xcc, 0x33));
    hardwareTree.setCellRenderer(treeRenderer);

    // Tree Selection Listener
    hardwareTree.addTreeSelectionListener(
        e -> {
          javax.swing.tree.TreePath path = hardwareTree.getSelectionPath();
          if (path != null) {
            javax.swing.tree.DefaultMutableTreeNode node =
                (javax.swing.tree.DefaultMutableTreeNode) path.getLastPathComponent();
            String folder = node.getUserObject().toString();
            if ("DELUGE HW".equals(folder)) {
              loadRemoteFolder("/");
            } else if ("SONGS".equals(folder) || "SYNTHS".equals(folder) || "KITS".equals(folder)) {
              loadRemoteFolder("/" + folder);
            }
          }
        });

    JScrollPane treeScroll = new JScrollPane(hardwareTree);
    treeScroll.setBorder(BorderFactory.createEmptyBorder());

    // 2. JTable Setup
    String[] columnNames = {"Name", "Size", "Date Modified"};
    remoteTableModel =
        new javax.swing.table.DefaultTableModel(columnNames, 0) {
          @Override
          public boolean isCellEditable(int row, int column) {
            return false;
          }
        };
    remoteTable = new JTable(remoteTableModel);
    remoteTable.setBackground(new Color(0x15, 0x15, 0x18));
    remoteTable.setForeground(Color.LIGHT_GRAY);
    remoteTable.setGridColor(new Color(0x25, 0x25, 0x28));
    remoteTable.setFont(new Font("SansSerif", Font.PLAIN, 10));
    remoteTable.setRowHeight(22);
    remoteTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    remoteTable.setSelectionBackground(new Color(0x00, 0xff, 0xcc, 0x33));
    remoteTable.setSelectionForeground(Color.WHITE);
    remoteTable.setShowGrid(true);
    remoteTable.setShowHorizontalLines(true);
    remoteTable.setShowVerticalLines(false);

    // Custom header styling
    remoteTable.getTableHeader().setBackground(new Color(0x1a, 0x1a, 0x20));
    remoteTable.getTableHeader().setForeground(Color.LIGHT_GRAY);
    remoteTable.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 9));
    remoteTable
        .getTableHeader()
        .setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0x25, 0x25, 0x28)));

    // Table Double-Click Listener
    remoteTable.addMouseListener(
        new java.awt.event.MouseAdapter() {
          @Override
          public void mousePressed(java.awt.event.MouseEvent e) {
            if (e.getClickCount() == 2) {
              int row = remoteTable.getSelectedRow();
              if (row >= 0 && row < currentRemoteEntries.size()) {
                RemoteFileEntry entry = currentRemoteEntries.get(row);
                if (entry.isDirectory()) {
                  String nextPath =
                      "/".equals(currentRemotePath)
                          ? "/" + entry.name()
                          : currentRemotePath + "/" + entry.name();
                  loadRemoteFolder(nextPath);
                } else {
                  String cat = "SONGS";
                  if (currentRemotePath.contains("/SYNTHS")) cat = "SYNTHS";
                  else if (currentRemotePath.contains("/KITS")) cat = "KITS";
                  downloadAndLoadRemoteFile(cat, entry.name());
                }
              }
            }
          }
        });

    // 3. Right-Click Popup Menu for Table
    JPopupMenu popupMenu = new JPopupMenu();

    JMenuItem downloadItem = new JMenuItem("📥 Download & Open");
    downloadItem.addActionListener(
        e -> {
          int row = remoteTable.getSelectedRow();
          if (row >= 0 && row < currentRemoteEntries.size()) {
            RemoteFileEntry entry = currentRemoteEntries.get(row);
            if (!entry.isDirectory()) {
              String cat = "SONGS";
              if (currentRemotePath.contains("/SYNTHS")) cat = "SYNTHS";
              else if (currentRemotePath.contains("/KITS")) cat = "KITS";
              downloadAndLoadRemoteFile(cat, entry.name());
            }
          }
        });
    popupMenu.add(downloadItem);

    JMenuItem uploadItem = new JMenuItem("📤 Upload Local File...");
    uploadItem.addActionListener(e -> triggerLocalUpload());
    popupMenu.add(uploadItem);

    JMenuItem mkdirItem = new JMenuItem("📁 New Folder...");
    mkdirItem.addActionListener(e -> triggerNewFolder());
    popupMenu.add(mkdirItem);

    JMenuItem renameItem = new JMenuItem("✏️ Rename...");
    renameItem.addActionListener(e -> triggerRename());
    popupMenu.add(renameItem);

    JMenuItem deleteItem = new JMenuItem("❌ Delete");
    deleteItem.addActionListener(e -> triggerDelete());
    popupMenu.add(deleteItem);

    JMenuItem copyItem = new JMenuItem("📋 Copy");
    copyItem.addActionListener(e -> performCopy());
    popupMenu.add(copyItem);

    JMenuItem pasteItem = new JMenuItem("📋 Paste");
    pasteItem.addActionListener(e -> performPaste());
    popupMenu.add(pasteItem);

    // Ctrl+C/Ctrl+V: every OS file manager supports these, so a right-click-only Copy/Paste reads
    // as "doesn't have copy paste actions" even though the menu items work correctly.
    int menuMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
    remoteTable
        .getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        .put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_C, menuMask), "hwCopy");
    remoteTable
        .getActionMap()
        .put(
            "hwCopy",
            new AbstractAction() {
              @Override
              public void actionPerformed(java.awt.event.ActionEvent e) {
                performCopy();
              }
            });
    remoteTable
        .getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        .put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_V, menuMask), "hwPaste");
    remoteTable
        .getActionMap()
        .put(
            "hwPaste",
            new AbstractAction() {
              @Override
              public void actionPerformed(java.awt.event.ActionEvent e) {
                performPaste();
              }
            });

    SwingGridPanel.stylePopupMenu(popupMenu);

    // Apply color highlighting to special actions
    for (java.awt.Component comp : popupMenu.getComponents()) {
      if (comp instanceof JMenuItem mi) {
        if ("📥 Download & Open".equals(mi.getText())) {
          mi.setForeground(new Color(0x00, 0xff, 0xcc));
        } else if ("❌ Delete".equals(mi.getText())) {
          mi.setForeground(Color.RED);
        }
      }
    }

    popupMenu.addPopupMenuListener(
        new javax.swing.event.PopupMenuListener() {
          @Override
          public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent ev) {
            boolean hasSelection = remoteTable.getSelectedRow() != -1;
            downloadItem.setEnabled(hasSelection);
            renameItem.setEnabled(hasSelection);
            deleteItem.setEnabled(hasSelection);
            copyItem.setEnabled(hasSelection);

            boolean canPaste =
                (parent.localClipboardFile != null) || (parent.remoteClipboardPath != null);
            pasteItem.setEnabled(canPaste);
          }

          @Override
          public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent ev) {}

          @Override
          public void popupMenuCanceled(javax.swing.event.PopupMenuEvent ev) {}
        });

    // Bind popup menu
    remoteTable.setComponentPopupMenu(popupMenu);

    JScrollPane tableScroll = new JScrollPane(remoteTable);
    tableScroll.setBorder(BorderFactory.createEmptyBorder());

    // Table container panel with title label showing current path
    JPanel tablePanel = new JPanel(new BorderLayout());
    tablePanel.setBackground(new Color(0x12, 0x12, 0x14));

    JPanel tableHeader = new JPanel(new BorderLayout());
    tableHeader.setBackground(new Color(0x15, 0x15, 0x18));
    tableHeader.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));

    currentFolderLabel = new JLabel("📂 /SONGS");
    currentFolderLabel.setForeground(Color.WHITE);
    currentFolderLabel.setFont(new Font("SansSerif", Font.BOLD, 10));
    tableHeader.add(currentFolderLabel, BorderLayout.WEST);
    tablePanel.add(tableHeader, BorderLayout.NORTH);
    tablePanel.add(tableScroll, BorderLayout.CENTER);

    // Progress bar for async transfers
    transferProgressBar = new JProgressBar(0, 100);
    transferProgressBar.setBackground(new Color(0x12, 0x12, 0x14));
    transferProgressBar.setForeground(new Color(0x00, 0xff, 0xcc));
    transferProgressBar.setStringPainted(true);
    transferProgressBar.setFont(new Font("SansSerif", Font.BOLD, 9));
    transferProgressBar.setVisible(false);
    tablePanel.add(transferProgressBar, BorderLayout.SOUTH);

    // 4. Split Pane holding both Tree (top) and Table (bottom)
    JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, treeScroll, tablePanel);
    splitPane.setDividerLocation(150);
    splitPane.setBackground(new Color(0x12, 0x12, 0x14));
    splitPane.setBorder(BorderFactory.createEmptyBorder());

    add(splitPane, BorderLayout.CENTER);
    add(buildLiveDeviceMonitorPanel(), BorderLayout.SOUTH);

    // Initial folder load
    Timer initialLoadTimer = new Timer(800, ev -> loadRemoteFolder("/SONGS"));
    initialLoadTimer.setRepeats(false);
    initialLoadTimer.start();

    // Deferred so SwingDelugeApp.mainInstance is guaranteed set by the time we look it up.
    Timer wireListenersTimer = new Timer(800, ev -> wireLiveDeviceListeners());
    wireListenersTimer.setRepeats(false);
    wireListenersTimer.start();
  }

  /**
   * Builds the live-mirror panel for the physical Deluge's own screen: its real OLED frame buffer
   * (or 7-segment text, on older hardware) and its real-time debug log stream. Both were already
   * being requested from the device (DelugeHwStatusPanel calls startOledStreaming() on connect) but
   * had no UI consumer -- DelugeSysExManager.DisplayListener/MidiDebugListener were set up in the
   * protocol layer and never wired to anything, so the frames/messages were silently dropped.
   */
  private JPanel buildLiveDeviceMonitorPanel() {
    JPanel panel = new JPanel(new BorderLayout(6, 0));
    panel.setBackground(new Color(0x15, 0x15, 0x18));
    panel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));

    JLabel title = new JLabel("LIVE DEVICE SCREEN");
    title.setForeground(Color.LIGHT_GRAY);
    title.setFont(new Font("SansSerif", Font.BOLD, 9));
    JPanel titleRow = new JPanel(new BorderLayout());
    titleRow.setOpaque(false);
    titleRow.add(title, BorderLayout.WEST);
    liveSevenSegLabel = new JLabel("");
    liveSevenSegLabel.setForeground(new Color(0x00, 0xff, 0xcc));
    liveSevenSegLabel.setFont(new Font("Monospaced", Font.BOLD, 10));
    titleRow.add(liveSevenSegLabel, BorderLayout.EAST);
    panel.add(titleRow, BorderLayout.NORTH);

    liveOled = new org.deluge.hid.VirtualOLED();
    liveOledView =
        new JComponent() {
          @Override
          protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, getWidth(), getHeight());
            ((Graphics2D) g).drawImage(liveOled.getImage(), 0, 0, getWidth(), getHeight(), null);
          }
        };
    liveOledView.setPreferredSize(new Dimension(256, 96));
    liveOledView.setMinimumSize(new Dimension(256, 96));
    liveOledView.setBorder(BorderFactory.createLineBorder(new Color(0x2d, 0x2d, 0x34), 1));
    liveOledView.setToolTipText(
        "Mirrors the physical Deluge's own screen (only updates while a real device is connected)");
    panel.add(liveOledView, BorderLayout.WEST);

    liveDebugLogArea = new JTextArea();
    liveDebugLogArea.setEditable(false);
    liveDebugLogArea.setBackground(new Color(0x0e, 0x0e, 0x10));
    liveDebugLogArea.setForeground(new Color(0x8a, 0xff, 0x8a));
    liveDebugLogArea.setFont(new Font("Monospaced", Font.PLAIN, 9));
    liveDebugLogArea.setLineWrap(true);
    JScrollPane debugScroll = new JScrollPane(liveDebugLogArea);
    debugScroll.setPreferredSize(new Dimension(200, 96));
    debugScroll.setBorder(BorderFactory.createLineBorder(new Color(0x2d, 0x2d, 0x34), 1));
    panel.add(debugScroll, BorderLayout.CENTER);

    return panel;
  }

  /** Registers this panel as the live consumer of the real device's OLED/7-seg/debug streams. */
  private void wireLiveDeviceListeners() {
    if (SwingDelugeApp.mainInstance == null
        || SwingDelugeApp.mainInstance.getMidiService() == null) {
      return;
    }
    var sysex = SwingDelugeApp.mainInstance.getMidiService().getSysExManager();
    sysex.setDisplayListener(
        new org.deluge.midi.DelugeSysExManager.DisplayListener() {
          @Override
          public void onOledFrame(byte[] frameBuffer) {
            SwingUtilities.invokeLater(
                () -> {
                  liveOled.drawRawFrameBuffer(frameBuffer);
                  liveOledView.repaint();
                });
          }

          @Override
          public void onSevenSegment(String text) {
            SwingUtilities.invokeLater(() -> liveSevenSegLabel.setText(text));
          }
        });
    sysex.setMidiDebugListener(
        message -> SwingUtilities.invokeLater(() -> appendDebugLine(message)));
  }

  private void appendDebugLine(String message) {
    liveDebugLogArea.append(message + "\n");
    int excess = liveDebugLogArea.getLineCount() - MAX_DEBUG_LOG_LINES;
    if (excess > 0) {
      try {
        int endOfExcess = liveDebugLogArea.getLineEndOffset(excess - 1);
        liveDebugLogArea.replaceRange("", 0, endOfExcess);
      } catch (javax.swing.text.BadLocationException ignored) {
      }
    }
    liveDebugLogArea.setCaretPosition(liveDebugLogArea.getDocument().getLength());
  }

  private void runConnectionSelfTest() {
    if (SwingDelugeApp.mainInstance == null) return;
    var midi = SwingDelugeApp.mainInstance.getMidiService();
    var sysex = midi.getSysExManager();
    var fileSync = midi.getFileSyncService();
    Thread.ofVirtual()
        .name("DelugeSelfTest")
        .start(
            () -> {
              StringBuilder log = new StringBuilder();
              // 1. Output port wired up?
              if (!sysex.hasMidiOut()) {
                log.append("✗ No MIDI OUT port configured.\n   Select the Deluge MIDI port first.");
                showSelfTestResult(log.toString());
                return;
              }
              log.append("✓ MIDI OUT configured\n");

              // 2. Ping round-trip.
              try {
                java.util.concurrent.CompletableFuture<Long> ping =
                    new java.util.concurrent.CompletableFuture<>();
                long t0 = System.nanoTime();
                sysex.sendRequest(
                    "{\"ping\":{}}", (j, b) -> ping.complete((System.nanoTime() - t0) / 1_000_000));
                long ms = ping.get(2, java.util.concurrent.TimeUnit.SECONDS);
                log.append("✓ Ping reply in ").append(ms).append(" ms\n");
              } catch (Exception ex) {
                log.append("✗ No ping reply within 2s on the selected port \"")
                    .append(org.deluge.project.PreferencesManager.get("midi.input", "None"))
                    .append("\".\n   Scanning all Deluge cables to find one that responds…\n\n");
                String working = scanDelugePorts(log);
                if (working != null) {
                  log.append("\nApplying \"").append(working).append("\" and reconnecting…\n");
                  try {
                    org.deluge.project.PreferencesManager.set("midi.input", working);
                    midi.reconnect();
                    Thread.sleep(1200); // let ALSA re-subscribe and the session re-negotiate
                    java.util.concurrent.CompletableFuture<Long> ping2 =
                        new java.util.concurrent.CompletableFuture<>();
                    long t1 = System.nanoTime();
                    sysex.sendRequest(
                        "{\"ping\":{}}",
                        (j, b) -> ping2.complete((System.nanoTime() - t1) / 1_000_000));
                    long ms = ping2.get(3, java.util.concurrent.TimeUnit.SECONDS);
                    log.append("✓ Reconnected on \"")
                        .append(working)
                        .append("\" — ping ")
                        .append(ms)
                        .append(" ms. Click REFRESH to load the SD card.");
                  } catch (Exception re) {
                    log.append(
                        "✗ Reconnect still got no reply. Power-cycle the Deluge and re-attach"
                            + " the USB to the Crostini container.");
                  }
                }
                showSelfTestResult(log.toString());
                return;
              }

              // 3. Session negotiation.
              try {
                sysex.negotiateSession("DelugeJava").get(2, java.util.concurrent.TimeUnit.SECONDS);
                log.append("✓ Session id=")
                    .append(sysex.getSessionId())
                    .append(" (midMin=")
                    .append(sysex.getMidMin())
                    .append(", midMax=")
                    .append(sysex.getMidMax())
                    .append(")\n");
              } catch (Exception ex) {
                log.append("✗ Session negotiation timed out (continuing to dir test).\n");
              }

              // 4. /SONGS listing count (the actual explorer path).
              java.util.concurrent.CompletableFuture<Integer> songs =
                  new java.util.concurrent.CompletableFuture<>();
              fileSync.listSongs(
                  "/SONGS",
                  new org.deluge.midi.DelugeFileSyncService.FileListCallback() {
                    @Override
                    public void onSuccess(java.util.List<String> files) {
                      songs.complete(files.size());
                    }

                    @Override
                    public void onFailure(Throwable t) {
                      songs.completeExceptionally(t);
                    }
                  });
              try {
                int n = songs.get(20, java.util.concurrent.TimeUnit.SECONDS);
                log.append("✓ /SONGS listing returned ").append(n).append(" file(s)");
                if (n == 0) {
                  log.append("\n   (0 files — is the SD card inserted and /SONGS non-empty?)");
                }
              } catch (Exception ex) {
                log.append("✗ /SONGS listing failed: ").append(ex.getMessage());
              }
              showSelfTestResult(log.toString());
            });
  }

  private String scanDelugePorts(StringBuilder sb) {
    String[] outPorts = org.deluge.shadow.midi.MidiOut.list();
    String[] inPorts = org.deluge.shadow.midi.MidiIn.list();
    sb.append("Output ports: ").append(java.util.Arrays.toString(outPorts)).append('\n');
    sb.append("Input ports:  ").append(java.util.Arrays.toString(inPorts)).append("\n\n");

    String working = null;
    for (String name : outPorts) {
      int inIdx = -1;
      for (int i = 0; i < inPorts.length; i++) {
        if (inPorts[i].equals(name)) {
          inIdx = i;
          break;
        }
      }
      if (inIdx < 0) {
        sb.append("• ").append(name).append(" — no matching INPUT cable, skipped\n");
        continue;
      }
      org.deluge.shadow.midi.MidiOut out = new org.deluge.shadow.midi.MidiOut();
      org.deluge.shadow.midi.MidiIn in = new org.deluge.shadow.midi.MidiIn();
      try {
        if (!out.open(name) || !in.open(inIdx)) {
          sb.append("• ").append(name).append(" — could not open (in use?), skipped\n");
          continue;
        }
        in.ignoreTypes(false, false, false);
        byte[] json = "{\"ping\":{}}".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] pkt = new byte[7 + json.length + 1];
        pkt[0] = (byte) 0xF0;
        pkt[1] = 0x00;
        pkt[2] = 0x21;
        pkt[3] = 0x7B;
        pkt[4] = 0x01;
        pkt[5] = 0x04;
        pkt[6] = 0x01;
        System.arraycopy(json, 0, pkt, 7, json.length);
        pkt[pkt.length - 1] = (byte) 0xF7;
        org.deluge.shadow.midi.MidiMsg msg = new org.deluge.shadow.midi.MidiMsg();
        msg.setData(pkt);
        out.send(msg);

        boolean replied = false;
        org.deluge.shadow.midi.MidiMsg rx = new org.deluge.shadow.midi.MidiMsg();
        long deadline = System.currentTimeMillis() + 1000;
        while (System.currentTimeMillis() < deadline) {
          if (in.recv(rx)) {
            byte[] d = rx.getData();
            if (d != null
                && d.length >= 4
                && (d[0] & 0xFF) == 0xF0
                && (d[2] & 0xFF) == 0x21
                && (d[3] & 0xFF) == 0x7B) {
              replied = true;
              break;
            }
          } else {
            try {
              Thread.sleep(10);
            } catch (InterruptedException ie) {
              break;
            }
          }
        }
        if (replied) {
          sb.append("✓ ").append(name).append(" — REPLIED\n");
          if (working == null) working = name;
        } else {
          sb.append("✗ ").append(name).append(" — no reply\n");
        }
      } finally {
        in.close();
        out.close();
      }
    }

    sb.append('\n');
    if (working != null) {
      sb.append("→ Select MIDI port \"")
          .append(working)
          .append("\" in Preferences — that cable answers.");
    } else {
      sb.append(
          "→ No cable answered. The Deluge isn't reachable: check the USB is attached to the"
              + " Crostini container (ChromeOS ▸ Settings ▸ USB) and that the Deluge is powered on.");
    }
    return sb.toString();
  }

  private void showSelfTestResult(String text) {
    System.out.println("[Deluge Self-Test]\n" + text);
    SwingUtilities.invokeLater(
        () ->
            JOptionPane.showMessageDialog(
                hardwareTree,
                text,
                "Deluge Connection Self-Test",
                JOptionPane.INFORMATION_MESSAGE));
  }

  public void refreshHardwareTree() {
    if (SwingDelugeApp.mainInstance == null) return;
    var fileSync = SwingDelugeApp.mainInstance.getMidiService().getFileSyncService();
    if (fileSync.isTransferActive()) {
      return;
    }

    songsNode.removeAllChildren();
    synthsNode.removeAllChildren();
    kitsNode.removeAllChildren();
    ((javax.swing.tree.DefaultTreeModel) hardwareTree.getModel()).reload();

    java.util.Map<String, DefaultMutableTreeNode> nodeFor =
        java.util.Map.of("/SONGS", songsNode, "/SYNTHS", synthsNode, "/KITS", kitsNode);
    fileSync.listDirs(
        java.util.List.of("/SONGS", "/SYNTHS", "/KITS"),
        new org.deluge.midi.DelugeFileSyncService.DirListCallback() {
          @Override
          public void onDir(String path, java.util.List<String> files) {
            DefaultMutableTreeNode node = nodeFor.get(path);
            if (node == null) return;
            SwingUtilities.invokeLater(
                () -> {
                  for (String f : files) {
                    node.add(new DefaultMutableTreeNode(f));
                  }
                  ((javax.swing.tree.DefaultTreeModel) hardwareTree.getModel()).reload(node);
                });
          }

          @Override
          public void onError(String path, Throwable t) {
            System.err.println("[Sidebar] Failed to fetch remote " + path + ": " + t.getMessage());
          }
        });
  }

  private void downloadAndLoadRemoteFile(String category, String name) {
    if (SwingDelugeApp.mainInstance == null) return;
    var fileSync = SwingDelugeApp.mainInstance.getMidiService().getFileSyncService();
    String remotePath = "/" + category + "/" + name;

    File destDir =
        switch (category) {
          case "SONGS" -> PreferencesManager.getSongsDir();
          case "KITS" -> PreferencesManager.getKitsDir();
          case "SYNTHS" -> PreferencesManager.getSynthsDir();
          default -> new File(PreferencesManager.getLibraryDir(), category);
        };
    File destFile = new File(destDir, name);

    JDialog progress = new JDialog(SwingDelugeApp.mainInstance, "Downloading", true);
    progress.setLayout(new BorderLayout(0, 8));
    ((JComponent) progress.getContentPane())
        .setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));

    JLabel statusLbl = new JLabel("Downloading " + name + " from Deluge…");
    statusLbl.setFont(new Font("SansSerif", Font.BOLD, 12));
    progress.add(statusLbl, BorderLayout.NORTH);

    JProgressBar bar = new JProgressBar(0, 100);
    bar.setIndeterminate(true);
    bar.setStringPainted(true);
    bar.setString("Connecting…");
    bar.setPreferredSize(new Dimension(360, 22));
    progress.add(bar, BorderLayout.CENTER);

    JLabel destLbl = new JLabel("Saving to: " + destFile.getAbsolutePath());
    destLbl.setFont(new Font("SansSerif", Font.PLAIN, 10));
    destLbl.setForeground(new Color(0x88, 0x88, 0x90));
    destLbl.setToolTipText(destFile.getAbsolutePath());
    progress.add(destLbl, BorderLayout.SOUTH);

    progress.pack();
    progress.setLocationRelativeTo(SwingDelugeApp.mainInstance);

    var progressCb =
        (org.deluge.midi.DelugeFileSyncService.ProgressCallback)
            (done, total) ->
                SwingUtilities.invokeLater(
                    () -> {
                      if (total > 0) {
                        bar.setIndeterminate(false);
                        int pct = (int) Math.min(100, (done * 100L) / total);
                        bar.setValue(pct);
                        bar.setString(String.format("%d%%  (%,d / %,d bytes)", pct, done, total));
                      }
                    });

    fileSync.downloadFileAsync(
        remotePath,
        new org.deluge.midi.DelugeFileSyncService.FileDownloadCallback() {
          @Override
          public void onSuccess(byte[] content) {
            try {
              destDir.mkdirs();
              java.nio.file.Files.write(destFile.toPath(), content);
            } catch (Exception saveEx) {
              System.err.println(
                  "[Sidebar] Warning: could not save downloaded file to "
                      + destFile.getAbsolutePath()
                      + ": "
                      + saveEx.getMessage());
            }
            SwingUtilities.invokeLater(
                () -> {
                  progress.dispose();
                  try (java.io.ByteArrayInputStream bis =
                      new java.io.ByteArrayInputStream(content)) {
                    if ("SONGS".equals(category)) {
                      org.deluge.model.ProjectModel loadedProject =
                          DelugeXmlParser.parseSong(bis, name);
                      java.io.File libraryDir = PreferencesManager.getLibraryDir();
                      java.util.ArrayList<String> missingFiles = new java.util.ArrayList<>();
                      int engineRow = 0;
                      for (org.deluge.model.TrackModel track : loadedProject.getTracks()) {
                        if (engineRow >= BridgeContract.TRACKS) break;
                        if (track instanceof org.deluge.model.KitTrackModel kit) {
                          java.util.List<org.deluge.model.Drum> sounds = kit.getDrums();
                          for (int i = 0; i < sounds.size(); i++) {
                            String sp =
                                ((org.deluge.model.SoundDrum) sounds.get(i)).getSamplePath();
                            if (sp != null && !sp.isEmpty()) {
                              java.io.File sf = new java.io.File(sp);
                              if (!sf.exists() && libraryDir != null) {
                                sf = new java.io.File(libraryDir, sp);
                              }
                              if (!sf.exists()) {
                                missingFiles.add(sp);
                              }
                            }
                            parent.bridge.setGlobalString(
                                "g_sample_" + (engineRow + i), sp != null ? sp : "");
                            parent.bridge.setMute(engineRow + i, false);
                            parent.bridge.setTrackType(engineRow + i, 0);
                          }
                        }
                        engineRow++;
                      }
                      if (parent.onSongLoaded != null) {
                        parent.onSongLoaded.accept(loadedProject, destFile);
                      }
                    } else if ("SYNTHS".equals(category)) {
                      org.deluge.model.SynthTrackModel synth =
                          DelugeXmlParser.parseSynth(bis, name);
                      if (parent.onTrackAdded != null) {
                        parent.onTrackAdded.accept(synth);
                      } else {
                        org.deluge.model.ProjectModel mockProj =
                            new org.deluge.model.ProjectModel();
                        mockProj.addTrack(synth);
                        if (parent.onSongLoaded != null) {
                          parent.onSongLoaded.accept(mockProj, destFile);
                        }
                      }
                      parent.bridge.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
                    } else if ("KITS".equals(category)) {
                      org.deluge.model.KitTrackModel kit = DelugeXmlParser.parseKit(bis, name);
                      int baseTrack = 0;
                      java.util.List<org.deluge.model.Drum> sounds = kit.getDrums();
                      for (int i = 0; i < sounds.size(); i++) {
                        String sp = ((org.deluge.model.SoundDrum) sounds.get(i)).getSamplePath();
                        parent.bridge.setGlobalString(
                            "g_sample_" + (baseTrack + i), sp != null ? sp : "");
                        parent.bridge.setSamplePath(baseTrack + i, sp != null ? sp : "");
                        parent.bridge.setMute(baseTrack + i, false);
                      }
                      if (parent.onTrackAdded != null) {
                        parent.onTrackAdded.accept(kit);
                      } else {
                        org.deluge.model.ProjectModel mockProj =
                            new org.deluge.model.ProjectModel();
                        mockProj.addTrack(kit);
                        if (parent.onSongLoaded != null) {
                          parent.onSongLoaded.accept(mockProj, destFile);
                        }
                      }
                      parent.bridge.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
                    }
                  } catch (Exception ex) {
                    ex.printStackTrace();
                    JOptionPane.showMessageDialog(
                        SwingDelugeApp.mainInstance,
                        "Failed to load/parse remote file:\n" + ex.getMessage(),
                        "Error",
                        JOptionPane.ERROR_MESSAGE);
                  }
                });
          }

          @Override
          public void onFailure(Throwable t) {
            t.printStackTrace();
            SwingUtilities.invokeLater(
                () -> {
                  progress.dispose();
                  JOptionPane.showMessageDialog(
                      SwingDelugeApp.mainInstance,
                      "Failed to download remote file:\n" + t.getMessage(),
                      "Error",
                      JOptionPane.ERROR_MESSAGE);
                });
          }
        },
        progressCb);

    progress.setVisible(true);
  }

  private void loadRemoteFolder(String remotePath) {
    if (SwingDelugeApp.mainInstance == null) return;
    var midiService = SwingDelugeApp.mainInstance.getMidiService();
    if (midiService == null) return;
    var fileSync = midiService.getFileSyncService();
    if (fileSync == null) return;
    if (fileSync.isTransferActive()) {
      return;
    }
    this.currentRemotePath = remotePath;
    currentFolderLabel.setText("📂 " + remotePath);

    // Unlike a one-shot request, listDirectory now retries/paginates over the fragile USB SysEx
    // link and can legitimately take several seconds on a lossy connection. Every other transfer
    // in this tab shows transferProgressBar while it works; this one didn't, so a slow-but-working
    // listing looked identical to a broken, permanently empty one.
    transferProgressBar.setVisible(true);
    transferProgressBar.setIndeterminate(true);
    transferProgressBar.setString("Loading " + remotePath + "…");

    fileSync.listDirectory(
        remotePath,
        new org.deluge.midi.DelugeFileSyncService.DirectoryListCallback() {
          @Override
          public void onSuccess(java.util.List<RemoteFileEntry> entries) {
            SwingUtilities.invokeLater(
                () -> {
                  transferProgressBar.setVisible(false);
                  currentRemoteEntries = entries;
                  remoteTableModel.setRowCount(0);
                  for (RemoteFileEntry entry : entries) {
                    String sizeStr = entry.isDirectory() ? "<DIR>" : formatFileSize(entry.size());
                    String dateStr = formatDate(entry.lastModifiedMillis());
                    remoteTableModel.addRow(new Object[] {entry.name(), sizeStr, dateStr});
                  }
                });
          }

          @Override
          public void onFailure(Throwable t) {
            System.err.println("[Sidebar] Failed to list remote directory: " + t.getMessage());
            SwingUtilities.invokeLater(() -> transferProgressBar.setVisible(false));
          }
        });
  }

  private void triggerLocalUpload() {
    if (SwingDelugeApp.mainInstance == null) return;
    var fileSync = SwingDelugeApp.mainInstance.getMidiService().getFileSyncService();
    if (fileSync.isTransferActive()) {
      JOptionPane.showMessageDialog(
          this, "A file transfer is already active.", "Warning", JOptionPane.WARNING_MESSAGE);
      return;
    }
    JFileChooser chooser = new JFileChooser();
    chooser.setDialogTitle("Select File to Upload");
    int result = chooser.showOpenDialog(this);
    if (result == JFileChooser.APPROVE_OPTION) {
      java.io.File localFile = chooser.getSelectedFile();
      try {
        byte[] content = java.nio.file.Files.readAllBytes(localFile.toPath());
        String remoteFilePath =
            "/".equals(currentRemotePath)
                ? "/" + localFile.getName()
                : currentRemotePath + "/" + localFile.getName();

        transferProgressBar.setVisible(true);
        transferProgressBar.setIndeterminate(true);
        transferProgressBar.setString("Uploading " + localFile.getName() + "...");

        fileSync.uploadFileAsync(
            remoteFilePath,
            content,
            new org.deluge.midi.DelugeFileSyncService.FileUploadCallback() {
              @Override
              public void onSuccess() {
                SwingUtilities.invokeLater(
                    () -> {
                      transferProgressBar.setVisible(false);
                      loadRemoteFolder(currentRemotePath);
                      JOptionPane.showMessageDialog(
                          HardwareSidebarTab.this,
                          "File uploaded successfully!",
                          "Success",
                          JOptionPane.INFORMATION_MESSAGE);
                    });
              }

              @Override
              public void onFailure(Throwable t) {
                SwingUtilities.invokeLater(
                    () -> {
                      transferProgressBar.setVisible(false);
                      JOptionPane.showMessageDialog(
                          HardwareSidebarTab.this,
                          "Upload failed: " + t.getMessage(),
                          "Error",
                          JOptionPane.ERROR_MESSAGE);
                    });
              }
            });
      } catch (Exception ex) {
        JOptionPane.showMessageDialog(
            this,
            "Failed to read local file: " + ex.getMessage(),
            "Error",
            JOptionPane.ERROR_MESSAGE);
      }
    }
  }

  private void triggerNewFolder() {
    if (SwingDelugeApp.mainInstance == null) return;
    var fileSync = SwingDelugeApp.mainInstance.getMidiService().getFileSyncService();
    if (fileSync.isTransferActive()) {
      JOptionPane.showMessageDialog(
          this, "A file transfer is already active.", "Warning", JOptionPane.WARNING_MESSAGE);
      return;
    }
    String name = JOptionPane.showInputDialog(this, "Enter folder name:");
    if (name != null && !name.trim().isEmpty()) {
      String remotePath =
          "/".equals(currentRemotePath) ? "/" + name.trim() : currentRemotePath + "/" + name.trim();

      transferProgressBar.setVisible(true);
      transferProgressBar.setIndeterminate(true);
      transferProgressBar.setString("Creating folder " + name.trim() + "...");

      fileSync.createDirectoryAsync(
          remotePath,
          System.currentTimeMillis(),
          new org.deluge.midi.DelugeFileSyncService.FileOpCallback() {
            @Override
            public void onSuccess() {
              SwingUtilities.invokeLater(
                  () -> {
                    transferProgressBar.setVisible(false);
                    loadRemoteFolder(currentRemotePath);
                  });
            }

            @Override
            public void onFailure(Throwable t) {
              SwingUtilities.invokeLater(
                  () -> {
                    transferProgressBar.setVisible(false);
                    JOptionPane.showMessageDialog(
                        HardwareSidebarTab.this,
                        "Failed to create folder: " + t.getMessage(),
                        "Error",
                        JOptionPane.ERROR_MESSAGE);
                  });
            }
          });
    }
  }

  private void triggerRename() {
    if (SwingDelugeApp.mainInstance == null) return;
    var fileSync = SwingDelugeApp.mainInstance.getMidiService().getFileSyncService();
    if (fileSync.isTransferActive()) {
      JOptionPane.showMessageDialog(
          this, "A file transfer is already active.", "Warning", JOptionPane.WARNING_MESSAGE);
      return;
    }
    int row = remoteTable.getSelectedRow();
    if (row >= 0 && row < currentRemoteEntries.size()) {
      RemoteFileEntry entry = currentRemoteEntries.get(row);
      String newName = JOptionPane.showInputDialog(this, "Enter new name:", entry.name());
      if (newName != null && !newName.trim().isEmpty()) {
        String from =
            "/".equals(currentRemotePath)
                ? "/" + entry.name()
                : currentRemotePath + "/" + entry.name();
        String to =
            "/".equals(currentRemotePath)
                ? "/" + newName.trim()
                : currentRemotePath + "/" + newName.trim();

        transferProgressBar.setVisible(true);
        transferProgressBar.setIndeterminate(true);
        transferProgressBar.setString("Renaming " + entry.name() + "...");

        fileSync.renameAsync(
            from,
            to,
            new org.deluge.midi.DelugeFileSyncService.FileOpCallback() {
              @Override
              public void onSuccess() {
                SwingUtilities.invokeLater(
                    () -> {
                      transferProgressBar.setVisible(false);
                      loadRemoteFolder(currentRemotePath);
                    });
              }

              @Override
              public void onFailure(Throwable t) {
                SwingUtilities.invokeLater(
                    () -> {
                      transferProgressBar.setVisible(false);
                      JOptionPane.showMessageDialog(
                          HardwareSidebarTab.this,
                          "Failed to rename: " + t.getMessage(),
                          "Error",
                          JOptionPane.ERROR_MESSAGE);
                    });
              }
            });
      }
    }
  }

  private void triggerDelete() {
    if (SwingDelugeApp.mainInstance == null) return;
    var fileSync = SwingDelugeApp.mainInstance.getMidiService().getFileSyncService();
    if (fileSync.isTransferActive()) {
      JOptionPane.showMessageDialog(
          this, "A file transfer is already active.", "Warning", JOptionPane.WARNING_MESSAGE);
      return;
    }
    int row = remoteTable.getSelectedRow();
    if (row >= 0 && row < currentRemoteEntries.size()) {
      RemoteFileEntry entry = currentRemoteEntries.get(row);
      int confirm =
          JOptionPane.showConfirmDialog(
              this,
              "Are you sure you want to delete " + entry.name() + "?",
              "Confirm Delete",
              JOptionPane.YES_NO_OPTION,
              JOptionPane.WARNING_MESSAGE);
      if (confirm == JOptionPane.YES_OPTION) {
        String path =
            "/".equals(currentRemotePath)
                ? "/" + entry.name()
                : currentRemotePath + "/" + entry.name();

        transferProgressBar.setVisible(true);
        transferProgressBar.setIndeterminate(true);
        transferProgressBar.setString("Deleting " + entry.name() + "...");

        fileSync.deleteAsync(
            path,
            new org.deluge.midi.DelugeFileSyncService.FileOpCallback() {
              @Override
              public void onSuccess() {
                SwingUtilities.invokeLater(
                    () -> {
                      transferProgressBar.setVisible(false);
                      loadRemoteFolder(currentRemotePath);
                    });
              }

              @Override
              public void onFailure(Throwable t) {
                SwingUtilities.invokeLater(
                    () -> {
                      transferProgressBar.setVisible(false);
                      JOptionPane.showMessageDialog(
                          HardwareSidebarTab.this,
                          "Failed to delete: " + t.getMessage(),
                          "Error",
                          JOptionPane.ERROR_MESSAGE);
                    });
              }
            });
      }
    }
  }

  private static String formatFileSize(long bytes) {
    if (bytes < 1024) return bytes + " B";
    int exp = (int) (Math.log(bytes) / Math.log(1024));
    char pre = "KMGTPE".charAt(exp - 1);
    return String.format("%.1f %cB", bytes / Math.pow(1024, exp), pre);
  }

  private static String formatDate(long millis) {
    if (millis <= 0) return "---";
    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");
    return sdf.format(new java.util.Date(millis));
  }

  private void uploadLocalFileToRemote(File localFile, String remotePath) {
    if (SwingDelugeApp.mainInstance == null) return;
    var fileSync = SwingDelugeApp.mainInstance.getMidiService().getFileSyncService();
    if (fileSync.isTransferActive()) {
      JOptionPane.showMessageDialog(
          this, "A file transfer is already active.", "Warning", JOptionPane.WARNING_MESSAGE);
      return;
    }
    try {
      byte[] content = java.nio.file.Files.readAllBytes(localFile.toPath());
      String remoteFilePath =
          "/".equals(remotePath)
              ? "/" + localFile.getName()
              : remotePath + "/" + localFile.getName();

      transferProgressBar.setVisible(true);
      transferProgressBar.setIndeterminate(true);
      transferProgressBar.setString("Uploading " + localFile.getName() + "...");

      fileSync.uploadFileAsync(
          remoteFilePath,
          content,
          new org.deluge.midi.DelugeFileSyncService.FileUploadCallback() {
            @Override
            public void onSuccess() {
              SwingUtilities.invokeLater(
                  () -> {
                    transferProgressBar.setVisible(false);
                    loadRemoteFolder(currentRemotePath);
                    JOptionPane.showMessageDialog(
                        HardwareSidebarTab.this,
                        "File uploaded successfully!",
                        "Success",
                        JOptionPane.INFORMATION_MESSAGE);
                  });
            }

            @Override
            public void onFailure(Throwable t) {
              SwingUtilities.invokeLater(
                  () -> {
                    transferProgressBar.setVisible(false);
                    JOptionPane.showMessageDialog(
                        HardwareSidebarTab.this,
                        "Upload failed: " + t.getMessage(),
                        "Error",
                        JOptionPane.ERROR_MESSAGE);
                  });
            }
          });
    } catch (Exception ex) {
      JOptionPane.showMessageDialog(
          this,
          "Failed to read local file: " + ex.getMessage(),
          "Error",
          JOptionPane.ERROR_MESSAGE);
    }
  }

  /** Copies the selected file's remote path onto the shared (cross-tab) clipboard. */
  private void performCopy() {
    int row = remoteTable.getSelectedRow();
    if (row >= 0 && row < currentRemoteEntries.size()) {
      RemoteFileEntry entry = currentRemoteEntries.get(row);
      if (!entry.isDirectory()) {
        parent.remoteClipboardPath =
            "/".equals(currentRemotePath)
                ? "/" + entry.name()
                : currentRemotePath + "/" + entry.name();
        parent.isRemoteSource = true;
        parent.localClipboardFile = null;
      }
    }
  }

  /**
   * Pastes whatever is on the shared clipboard (a remote path or a local file) into this folder.
   */
  private void performPaste() {
    if (parent.isRemoteSource && parent.remoteClipboardPath != null) {
      int slash = parent.remoteClipboardPath.lastIndexOf('/');
      String name = slash != -1 ? parent.remoteClipboardPath.substring(slash + 1) : "copy.XML";
      String to = "/".equals(currentRemotePath) ? "/" + name : currentRemotePath + "/" + name;
      if (to.equalsIgnoreCase(parent.remoteClipboardPath)) {
        int extIdx = to.lastIndexOf('.');
        if (extIdx != -1) {
          to = to.substring(0, extIdx) + "_copy" + to.substring(extIdx);
        } else {
          to = to + "_copy";
        }
      }
      copyRemoteFileToRemote(parent.remoteClipboardPath, to);
    } else if (!parent.isRemoteSource && parent.localClipboardFile != null) {
      uploadLocalFileToRemote(parent.localClipboardFile, currentRemotePath);
    }
  }

  private void copyRemoteFileToRemote(String fromPath, String toPath) {
    if (SwingDelugeApp.mainInstance == null) return;
    var fileSync = SwingDelugeApp.mainInstance.getMidiService().getFileSyncService();
    if (fileSync.isTransferActive()) {
      JOptionPane.showMessageDialog(
          this, "A file transfer is already active.", "Warning", JOptionPane.WARNING_MESSAGE);
      return;
    }

    transferProgressBar.setVisible(true);
    transferProgressBar.setIndeterminate(true);
    transferProgressBar.setString("Copying " + fromPath + " to " + toPath + "...");

    fileSync.copyAsync(
        fromPath,
        toPath,
        System.currentTimeMillis(),
        new org.deluge.midi.DelugeFileSyncService.FileOpCallback() {
          @Override
          public void onSuccess() {
            SwingUtilities.invokeLater(
                () -> {
                  transferProgressBar.setVisible(false);
                  loadRemoteFolder(currentRemotePath);
                  JOptionPane.showMessageDialog(
                      HardwareSidebarTab.this,
                      "File copied successfully!",
                      "Success",
                      JOptionPane.INFORMATION_MESSAGE);
                });
          }

          @Override
          public void onFailure(Throwable t) {
            SwingUtilities.invokeLater(
                () -> {
                  transferProgressBar.setVisible(false);
                  JOptionPane.showMessageDialog(
                      HardwareSidebarTab.this,
                      "Copy failed: " + t.getMessage(),
                      "Error",
                      JOptionPane.ERROR_MESSAGE);
                });
          }
        });
  }
}
