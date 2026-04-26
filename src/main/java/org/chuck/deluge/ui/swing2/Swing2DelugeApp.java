package org.chuck.deluge.ui.swing2;

import java.awt.*;
import javax.swing.*;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.midi.MidiService;
import org.chuck.deluge.model.ProjectModel;

/**
 * The revised Swing2 production application frame. Connects with the models through standard MVC
 * patterns.
 */
public class Swing2DelugeApp extends JFrame {
  private final ChuckVM vm;
  private final BridgeContract bridge;
  private final MidiService midi;
  private ProjectModel model;

  public Swing2DelugeApp(ChuckVM vm, BridgeContract bridge, MidiService midi) {
    this.vm = vm;
    this.bridge = bridge;
    this.midi = midi;
    this.model = ProjectModel.createDefaultProject();

    setTitle("Deluge Emulator Workstation [Swing2 Edition]");
    setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    setSize(1280, 800);
    setLocationRelativeTo(null);

    initUI();
  }

  private void initUI() {
    setLayout(new BorderLayout());

    // Top strip
    JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
    JButton playBtn = new JButton("▶ PLAY");
    JButton stopBtn = new JButton("■ STOP");
    playBtn.addActionListener(
        e -> {
          if (vm != null) vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
        });
    stopBtn.addActionListener(
        e -> {
          if (vm != null) vm.setGlobalInt(BridgeContract.G_PLAY, 0L);
        });
    topPanel.add(playBtn);
    topPanel.add(stopBtn);
    JButton loadBtn = new JButton("📂 LOAD XML");

    loadBtn.addActionListener(
        e -> {
          JFileChooser chooser = new JFileChooser();
          if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
              java.io.File f = chooser.getSelectedFile();
              org.chuck.deluge.model.ProjectModel pm =
                  org.chuck.deluge.xml.DelugeXmlParser.parseSong(
                      new java.io.FileInputStream(f), f.getName());
              this.model = pm;
              // We will notify observers here
            } catch (Exception ex) {
              ex.printStackTrace();
            }
          }
        });
    topPanel.add(loadBtn);

    JButton prefBtn = new JButton("⚙ PREFERENCES");

    prefBtn.addActionListener(
        e -> {
          Swing2PreferencesDialog dlg = new Swing2PreferencesDialog(this);
          dlg.setVisible(true);
        });
    topPanel.add(prefBtn);

    JButton songModeBtn = new JButton("SONG VIEW");
    JButton clipModeBtn = new JButton("CLIP VIEW");
    topPanel.add(songModeBtn);
    topPanel.add(clipModeBtn);

    JLabel bpmLbl = new JLabel("BPM:");
    JSlider bpmSlider = new JSlider(60, 200, 120);
    bpmSlider.addChangeListener(
        e -> {
          if (vm != null) vm.setGlobalFloat(BridgeContract.G_BPM, bpmSlider.getValue());
        });
    topPanel.add(bpmLbl);
    topPanel.add(bpmSlider);

    JLabel swingLbl = new JLabel("SWING:");
    JSlider swingSlider = new JSlider(0, 100, 50);
    swingSlider.addChangeListener(
        e -> {
          if (vm != null) vm.setGlobalFloat(BridgeContract.G_SWING, swingSlider.getValue() / 100.0);
        });
    topPanel.add(swingLbl);
    topPanel.add(swingSlider);

    add(topPanel, BorderLayout.NORTH);

    // Center workspace
    CardLayout cardLayout = new CardLayout();
    JPanel centerCardPanel = new JPanel(cardLayout);

    Swing2SongModePanel songPanel = new Swing2SongModePanel(bridge);

    Swing2GridPanel clipPanel = new Swing2GridPanel(vm, bridge);

    songPanel.setProjectModel(this.model);
    centerCardPanel.add(songPanel, "SONG");
    centerCardPanel.add(new JScrollPane(clipPanel), "CLIP");

    songPanel.setOnEditRequest(
        (trackId, clipId) -> {
          if (trackId < model.getTracks().size()) {
            org.chuck.deluge.model.TrackModel track = model.getTracks().get(trackId);
            if (clipId < track.getClips().size()) {
              clipPanel.setClipModel(track.getClips().get(clipId));
            }
          }
          cardLayout.show(centerCardPanel, "CLIP");
        });
    songModeBtn.addActionListener(e -> cardLayout.show(centerCardPanel, "SONG"));
    clipModeBtn.addActionListener(e -> cardLayout.show(centerCardPanel, "CLIP"));

    Swing2ProjectSidebarPanel sidebarPanel = new Swing2ProjectSidebarPanel(vm, bridge);
    sidebarPanel.setProjectModel(this.model);
    sidebarPanel.setOnSongLoaded(
        loaded -> {
          this.model = loaded;
          if (vm != null) vm.setGlobalFloat(BridgeContract.G_BPM, loaded.getBpm());
          songPanel.setProjectModel(loaded);

          clipPanel.setClipModel(loaded.getTracks().get(0).getClips().get(0));
          int trkIdx = 0;

          for (org.chuck.deluge.model.TrackModel track : loaded.getTracks()) {
            if (track.getClips().size() > 0) {
              org.chuck.deluge.model.ClipModel c = track.getClips().get(0);
              for (int r = 0; r < 8; r++) {
                for (int s = 0; s < 16; s++) {
                  bridge.setStep(trkIdx * 8 + r, s, c.getStep(r, s).active());
                  bridge.setMute(trkIdx * 8 + r, false);
                }
                if (track instanceof org.chuck.deluge.model.KitTrackModel kit) {
                  java.util.List<org.chuck.deluge.model.KitTrackModel.KitSound> sounds =
                      kit.getSounds();
                  if (r < sounds.size()) {
                    String sp = sounds.get(r).getSamplePath();
                    if (sp != null) {
                      java.io.InputStream resIs = getClass().getResourceAsStream(sp);
                      if (resIs == null) {
                        resIs = getClass().getResourceAsStream(sp.replace(".wav", ".WAV"));
                      }
                      try {
                        if (resIs != null) {
                          java.io.File tempFile =
                              new java.io.File(
                                  System.getProperty("user.home")
                                      + "/.gemini/jetski/scratch/"
                                      + new java.io.File(sp).getName());
                          java.nio.file.Files.copy(
                              resIs,
                              tempFile.toPath(),
                              java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                          sp = tempFile.getAbsolutePath();
                        }
                      } catch (Exception ex) {
                      } finally {
                        try {
                          if (resIs != null) resIs.close();
                        } catch (Exception ex) {
                        }
                      }
                    }
                    if (vm != null) vm.setGlobalString("g_sample_" + (trkIdx * 8 + r), sp);
                  }
                }
              }
            }
            trkIdx++;
          }
          if (vm != null) vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
          cardLayout.show(centerCardPanel, "SONG");
        });
    String res = org.chuck.deluge.project.PreferencesManager.get("screen.resolution", "FHD");
    if ("FHD".equals(res) || "4K".equals(res)) {
      JFrame sidebarFrame = new JFrame("Presets Explorer");
      sidebarFrame.setSize(400, 800);
      sidebarFrame.add(sidebarPanel);
      sidebarFrame.setLocation(this.getX() - 410, this.getY());
      sidebarFrame.setVisible(true);
    } else {
      add(sidebarPanel, BorderLayout.WEST);
    }

    if (model.getTracks().size() > 0 && model.getTracks().get(0).getClips().size() > 0) {
      clipPanel.setClipModel(model.getTracks().get(0).getClips().get(0));

      if (vm != null) vm.setGlobalFloat(BridgeContract.G_BPM, model.getBpm());

      int trkIdx = 0;
      for (org.chuck.deluge.model.TrackModel track : model.getTracks()) {
        if (track.getClips().size() > 0) {
          org.chuck.deluge.model.ClipModel c = track.getClips().get(0);
          for (int r = 0; r < 8; r++) {
            for (int s = 0; s < 16; s++) {
              bridge.setStep(trkIdx * 8 + r, s, c.getStep(r, s).active());
            }
          }
        }
        trkIdx++;
      }
    }

    add(centerCardPanel, BorderLayout.CENTER);

    JPanel masterFxPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 5));
    masterFxPanel.setBackground(new Color(0x25, 0x25, 0x25));
    masterFxPanel.setBorder(
        BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(Color.GRAY), "MASTER FX", 0, 0, null, Color.WHITE));

    JLabel transLabel = new JLabel("Transpose:");
    transLabel.setForeground(Color.WHITE);
    JSlider transSlider = new JSlider(-24, 24, 0);
    masterFxPanel.add(transLabel);
    masterFxPanel.add(transSlider);

    add(masterFxPanel, BorderLayout.SOUTH);

    javax.swing.Timer playheadTimer =
        new javax.swing.Timer(
            33,
            e -> {
              if (vm != null) {
                int step = (int) vm.getGlobalInt(org.chuck.deluge.BridgeContract.G_CURRENT_STEP);
                if (step >= 0) {
                  clipPanel.updatePlayhead(step);
                  songPanel.updatePlayhead(step);
                }
              }
            });
    playheadTimer.start();

    cardLayout.show(centerCardPanel, "CLIP");
  }
}
