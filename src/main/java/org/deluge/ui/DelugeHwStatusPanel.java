package org.deluge.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.deluge.midi.DelugeSysExManager;
import org.deluge.midi.MidiService;

/**
 * Premium, retro-styled glowing hardware connection status panel. Displays real-time connection
 * status to the physical Deluge. Runs a background heartbeat ping every 4 seconds to automatically
 * detect USB unplug/replug events.
 */
public class DelugeHwStatusPanel extends JPanel {
  private final MidiService midiService;
  private final JLabel label;
  private final LedIndicator led;
  private boolean connected = false;

  private long lastPingTime = 0;
  private long lastReplyTime = 0;

  public DelugeHwStatusPanel(MidiService midiService) {
    this.midiService = midiService;
    setLayout(new FlowLayout(FlowLayout.LEFT, 5, 0));
    setOpaque(false);
    setToolTipText("Physical Deluge USB MIDI Connection. Click to ping / reconnect.");

    led = new LedIndicator();
    add(led);

    label = new JLabel("DELUGE OFF");
    label.setForeground(new Color(0x88, 0x88, 0x90));
    label.setFont(new Font("SansSerif", Font.BOLD, 10));
    add(label);

    // Click to manually trigger a connection test; right-click for card push/pull actions.
    addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent e) {
            if (SwingUtilities.isLeftMouseButton(e)) {
              // A manual click is also a "reconnect" gesture: if the output port never opened
              // (e.g. the Deluge wasn't enumerable at startup), re-run the MIDI open before
              // pinging.
              if (!midiService.isOutputConnected()) {
                midiService.reconnect();
              }
              triggerPingTest();
              if (SwingDelugeApp.mainInstance != null) {
                SwingDelugeApp.mainInstance.openHardwareExplorer();
              }
            }
          }

          @Override
          public void mousePressed(MouseEvent e) {
            maybeShowCardMenu(e);
          }

          @Override
          public void mouseReleased(MouseEvent e) {
            maybeShowCardMenu(e);
          }
        });

    // Start a recurring background heartbeat timer (every 4 seconds)
    Timer heartbeatTimer = new Timer(4000, e -> triggerPingTest());
    heartbeatTimer.start();

    // Start a recurring background OLED keep-alive timer (every 1.5 seconds)
    Timer oledKeepAliveTimer =
        new Timer(
            1500,
            e -> {
              if (connected && midiService.getSysExManager().isOledStreamingEnabled()) {
                midiService.getSysExManager().startOledStreaming();
              }
            });
    oledKeepAliveTimer.start();

    // Trigger initial check immediately
    triggerPingTest();
  }

  /** Show the card push/pull popup on a platform popup-trigger (right-click). */
  private void maybeShowCardMenu(MouseEvent e) {
    if (!e.isPopupTrigger()) {
      return;
    }
    javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
    javax.swing.JMenuItem push = new javax.swing.JMenuItem("Push Song to Card…");
    push.addActionListener(a -> pushSongToCard());
    javax.swing.JMenuItem pull = new javax.swing.JMenuItem("Pull Recording from Card…");
    pull.addActionListener(a -> pullRecordingFromCard());
    boolean online = midiService.isOutputConnected();
    push.setEnabled(online);
    pull.setEnabled(online);
    menu.add(push);
    menu.add(pull);
    menu.show(e.getComponent(), e.getX(), e.getY());
  }

  /**
   * Pick a song XML and upload it to the card's /SONGS over USB (presets assumed already there).
   */
  private void pushSongToCard() {
    javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
    fc.setDialogTitle("Push Song to Deluge Card");
    fc.setFileFilter(
        new javax.swing.filechooser.FileNameExtensionFilter("Deluge song XML", "xml", "XML"));
    if (fc.showOpenDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) {
      return;
    }
    java.io.File song = fc.getSelectedFile();
    var sync =
        new org.deluge.midi.CalibrationCardSync(
            new org.deluge.midi.DelugeFileSyncTransport(midiService.getFileSyncService()));
    runCardOp(
        "Pushing " + song.getName() + " to /SONGS…",
        () -> {
          String remote = sync.pushSong(song);
          return "Pushed to " + remote;
        });
  }

  /** Download /SAMPLES/output_000.wav off the card to a chosen local file. */
  private void pullRecordingFromCard() {
    javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
    fc.setDialogTitle("Pull Recording from Deluge Card");
    fc.setSelectedFile(new java.io.File("output_000.wav"));
    if (fc.showSaveDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) {
      return;
    }
    java.io.File dest = fc.getSelectedFile();
    var sync =
        new org.deluge.midi.CalibrationCardSync(
            new org.deluge.midi.DelugeFileSyncTransport(midiService.getFileSyncService()));
    runCardOp(
        "Pulling /SAMPLES/output_000.wav…",
        () -> {
          sync.pullRecording("output_000.wav", dest);
          return "Saved to " + dest.getAbsolutePath();
        });
  }

  /** Run a blocking card op on a background thread with a simple result dialog. */
  private void runCardOp(String status, java.util.concurrent.Callable<String> op) {
    label.setText(status);
    Thread t =
        new Thread(
            () -> {
              String msg;
              int type = javax.swing.JOptionPane.INFORMATION_MESSAGE;
              try {
                msg = op.call();
              } catch (Exception ex) {
                msg = "Failed: " + ex.getMessage();
                type = javax.swing.JOptionPane.ERROR_MESSAGE;
              }
              final String fmsg = msg;
              final int ftype = type;
              SwingUtilities.invokeLater(
                  () -> {
                    javax.swing.JOptionPane.showMessageDialog(this, fmsg, "Deluge Card", ftype);
                    triggerPingTest();
                  });
            },
            "DelugeCardOp");
    t.setDaemon(true);
    t.start();
  }

  /** Run a live ping test to the physical Deluge. */
  public void triggerPingTest() {
    if (!midiService.getSysExManager().isOledStreamingEnabled()) {
      // Skip heartbeat pings during active file transfers to keep the MIDI channel 100% quiet
      return;
    }
    DelugeSysExManager mgr = midiService.getSysExManager();
    long now = System.currentTimeMillis();
    lastPingTime = now;

    mgr.sendRequest(
        "{\"ping\":{}}",
        (json, bin) -> {
          lastReplyTime = System.currentTimeMillis();
          SwingUtilities.invokeLater(() -> setConnected(true));
        });

    // Set a timeout check after 1.5 seconds
    Timer timeoutTimer =
        new Timer(
            1500,
            ev -> {
              if (lastReplyTime < lastPingTime) {
                SwingUtilities.invokeLater(() -> setConnected(false));
              }
            });
    timeoutTimer.setRepeats(false);
    timeoutTimer.start();
  }

  private void setConnected(boolean state) {
    boolean transitioned = (state && !this.connected);
    this.connected = state;
    led.setState(state);
    if (state) {
      label.setText("DELUGE ON");
      label.setForeground(new Color(0x33, 0xFF, 0x33)); // Glowing green
      if (transitioned && midiService.getSysExManager().isOledStreamingEnabled()) {
        midiService.getSysExManager().startOledStreaming();
      }
    } else {
      label.setText("DELUGE OFF");
      label.setForeground(new Color(0x88, 0x88, 0x90)); // Muted grey
    }
  }

  public boolean isConnected() {
    return connected;
  }

  /** Small custom vector LED indicator dot. */
  private static class LedIndicator extends JComponent {
    private boolean active = false;

    public LedIndicator() {
      setPreferredSize(new Dimension(10, 10));
    }

    public void setState(boolean state) {
      this.active = state;
      repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      Graphics2D g2 = (Graphics2D) g;
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

      // Glowing green when active, retro red when offline
      Color c = active ? new Color(0x33, 0xFF, 0x33) : new Color(0xFF, 0x33, 0x33);
      g2.setColor(c);
      g2.fillOval(1, 1, 8, 8);

      // Glossy 3D specular highlight
      g2.setColor(new Color(255, 255, 255, 180));
      g2.fillOval(3, 3, 2, 2);
    }
  }
}
