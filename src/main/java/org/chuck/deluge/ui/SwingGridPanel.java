package org.chuck.deluge.ui;

import java.awt.*;
import javax.swing.*;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;

/** Unified 18x8 Grid Panel handling both sequence matrix and clip launch arrangements. */
public class SwingGridPanel extends JPanel {
  private final ChuckVM vm;
  private final BridgeContract bridge;

  private org.chuck.deluge.model.ProjectModel projectModel;
  private java.util.function.BiConsumer<Integer, Integer> onEditRequest;
  private JButton[][] pads = new JButton[8][18];
  private org.rtmidijava.RtMidiOut finalMidiOut;


  public enum GridViewMode { CLIP, SONG, ARRANGEMENT }
  private GridViewMode viewMode = GridViewMode.SONG; 

  private Color[] trackColors = {
    new Color(0x00, 0xff, 0xcc), // Cyan
    new Color(0xff, 0x33, 0xcc), // Magenta
    new Color(0x33, 0xff, 0x33), // Lime Green
    new Color(0xff, 0x99, 0x33), // Orange
    new Color(0xcc, 0x33, 0xff), // Purple
    new Color(0xff, 0xff, 0x33), // Yellow
    new Color(0x33, 0x99, 0xff), // Blue
    new Color(0xff, 0x33, 0x33)  // Red
  };


  public SwingGridPanel(ChuckVM vm, BridgeContract bridge) {
    this.vm = vm;
    this.bridge = bridge;
    this.projectModel = new org.chuck.deluge.model.ProjectModel();

    setBackground(new Color(0x1a, 0x1a, 0x1a));
    setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
  }

  private int focusTrack = 0;
  public int getFocusTrack() {
     return focusTrack;
  }

  public void setViewMode(GridViewMode mode) {

    this.viewMode = mode;
    refresh();
  }

  public org.chuck.deluge.model.ProjectModel getProjectModel() {
     return projectModel;
  }

  public void setProjectModel(org.chuck.deluge.model.ProjectModel model) {

    this.projectModel = model;
    refresh();
  }

  public void setOnEditRequest(java.util.function.BiConsumer<Integer, Integer> callback) {
    this.onEditRequest = callback;
  }
  public void flashIsomorphicNote(int note) {
    int r = (note - 60) / 5;
    int c = (note - 60) % 5;
    if (r >= 0 && r < 8 && c >= 0 && c < 16) {
      if (pads[r][c] != null) {
         Color orig = pads[r][c].getBackground();
         pads[r][c].setBackground(Color.WHITE);
         Timer t = new Timer(200, ev -> pads[r][c].setBackground(orig));
         t.setRepeats(false);
         t.start();
      }
    }
  }

  public void updatePlayhead(int step) {
    Component[] rows = getComponents();
    for (int t = 0; t < Math.min(rows.length, 8); t++) {
      if (rows[t] instanceof JPanel rowPanel) {
        Component[] comps = rowPanel.getComponents();
        for (int c = 0; c < 8; c++) {
          if (c + 1 < comps.length && comps[c + 1] instanceof JButton pad) {
            boolean isTriggered = (bridge != null) && bridge.getStep(t * 8 + c, step % 16);
            if (isTriggered) {
              pad.setBackground(Color.WHITE);
            } else if (pad.getBackground().equals(Color.WHITE)) {
              pad.setBackground(new Color(0x00, 0xff, 0xcc)); 
            }
          }
        }
      }
    }
  }

  public void refresh() {
    removeAll();
    java.util.List<org.chuck.deluge.model.TrackModel> tracks = projectModel.getTracks();

    for (int t = 0; t < 8; t++) {
      JPanel rowPanel = new JPanel();
      rowPanel.setLayout(new BoxLayout(rowPanel, BoxLayout.X_AXIS));
      rowPanel.setBackground(new Color(0x22, 0x22, 0x22));

      final int currentTrack = t;
      if (t < tracks.size()) {
         String hex = tracks.get(t).getColourHex();
         if (hex != null && hex.startsWith("0x")) {
            try {
               int rgb = Integer.decode(hex.substring(0, 8)); // strip alpha if 8 chars
               trackColors[t] = new Color(rgb);
            } catch (Exception e) {}
         }
      }
      String trackName = (t < tracks.size()) ? tracks.get(t).getName() : "EMPTY " + (t + 1);
      if (viewMode == GridViewMode.CLIP && vm != null) {
         String samplePath = (String) vm.getGlobalObject("g_sample_" + (t));
         if (samplePath != null && !samplePath.isEmpty()) {
            int slash = samplePath.lastIndexOf('/');
            if (slash != -1) {
               trackName = samplePath.substring(slash + 1);
            } else {
               trackName = samplePath;
            }
         }
      }

      JLabel label = new JLabel(trackName);
      label.setPreferredSize(new Dimension(150, 30));
      label.setMinimumSize(new Dimension(150, 30));
      label.setMaximumSize(new Dimension(150, 30));

      label.setForeground(Color.LIGHT_GRAY);
      label.setCursor(new Cursor(Cursor.HAND_CURSOR));
      label.addMouseListener(new java.awt.event.MouseAdapter() {
        @Override
        public void mouseClicked(java.awt.event.MouseEvent e) {
          if (javax.swing.SwingUtilities.isRightMouseButton(e)) {
             Color chosen = JColorChooser.showDialog(SwingGridPanel.this, "Select Track Color", trackColors[currentTrack]);
             if (chosen != null) {
                trackColors[currentTrack] = chosen;
                refresh();
             }
             return;
          }
          if (onEditRequest != null) {
             onEditRequest.accept(currentTrack, 0);
          }
        }
      });

      rowPanel.add(label);
      rowPanel.add(Box.createHorizontalStrut(10));

      for (int c = 0; c < 18; c++) {
        final int slot = c;
        JButton clipBtn = new JButton();
        clipBtn.setPreferredSize(new Dimension(120, 120));
        clipBtn.setMinimumSize(new Dimension(120, 120));
        clipBtn.setMaximumSize(new Dimension(120, 120));
        
        pads[t][c] = clipBtn;

        if (viewMode == GridViewMode.CLIP) {
           clipBtn.setText("<html><font size='3'>Pi:" + (currentTrack) + "<br>Ve:0.8<br>Pr:1.0<br>Ga:1</font></html>");
        } else if (viewMode == GridViewMode.ARRANGEMENT) {
           String tn = (currentTrack < tracks.size()) ? tracks.get(currentTrack).getName() : "EMPTY";
           clipBtn.setText("<html><center><font size='3'>" + tn + "<br><b>Bar " + (c + 1) + "</b></font></center></html>");
        } else {
           if (t < tracks.size() && c < tracks.get(t).getClips().size()) {
              clipBtn.setText("<html><center><font size='3'>" + tracks.get(t).getClips().get(c).getName() + "</font></center></html>");
           } else {
              clipBtn.setText("PAD " + (c + 1));
           }
        }




        boolean hasClip = false;
        if (t < tracks.size()) {
          org.chuck.deluge.model.TrackModel track = tracks.get(t);
          if (c < track.getClips().size()) {
             hasClip = true;
          }
        }
        
        if (c == 16) {
           clipBtn.setText("MUTE");
           clipBtn.setBackground(bridge.getMute(currentTrack) ? Color.RED : new Color(0x33, 0x33, 0x33));
           clipBtn.addActionListener(e -> {
             if ((e.getModifiers() & java.awt.event.ActionEvent.SHIFT_MASK) != 0) {
                // Clear Sequence row
                 for (int s = 0; s < 16; s++) {
                    bridge.setStep(currentTrack, s, false);
                 }
                 refresh();
                 return;
              }
              boolean isMuted = bridge.getMute(currentTrack);
              bridge.setMute(currentTrack, !isMuted);

             clipBtn.setBackground(!isMuted ? Color.RED : new Color(0x33, 0x33, 0x33));
           });
        } else if (c == 17) {
            clipBtn.setText("EDIT");
            clipBtn.setBackground(focusTrack == currentTrack ? Color.GREEN : new Color(0x33, 0x33, 0x33));

           clipBtn.addActionListener(e -> {
             if ((e.getModifiers() & java.awt.event.ActionEvent.SHIFT_MASK) != 0) {
                // Delete track clips (or reset mock)
                return;
             }
              if (viewMode == GridViewMode.CLIP) {
                 focusTrack = currentTrack;
                 refresh(); // redraws backgrounds
                 return;
              }
              if (onEditRequest != null) {
                onEditRequest.accept(currentTrack, 0);
              }

           });
        }
 else {
          if (viewMode == GridViewMode.CLIP) {
             int currentStep = (int) vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
             int offset = (currentStep >= 0) ? (currentStep / 16) * 16 : 0;
             boolean stepState = bridge.getStep(currentTrack, offset + c);
             clipBtn.setBackground(stepState ? trackColors[currentTrack] : new Color(0x33, 0x33, 0x33));
          } else {
             if (hasClip) {
               clipBtn.setBackground(trackColors[currentTrack]);
             } else {
               clipBtn.setBackground(new Color(0x33, 0x33, 0x33));
             }
          }


          if (viewMode == GridViewMode.CLIP) {
             clipBtn.addMouseListener(new java.awt.event.MouseAdapter() {
               @Override
               public void mousePressed(java.awt.event.MouseEvent e) {
                 if (javax.swing.SwingUtilities.isRightMouseButton(e)) {
                    JDialog dialog = new JDialog((Frame)javax.swing.SwingUtilities.getWindowAncestor(SwingGridPanel.this), "Step Properties", true);
                    dialog.setSize(1600, 450);
                    dialog.setLocationRelativeTo(SwingGridPanel.this);
                    dialog.setLayout(new GridBagLayout());
                    
                    GridBagConstraints gc = new GridBagConstraints();
                    gc.fill = GridBagConstraints.HORIZONTAL;
                    gc.insets = new Insets(10, 15, 10, 15);
                    
                    Font labelFont = new Font("SansSerif", Font.BOLD, 18);
                    Dimension sliderDim = new Dimension(1200, 50);
                    Dimension spinDim = new Dimension(80, 40);
                    
                    gc.gridx = 0; gc.gridy = 0;
                    JLabel l1 = new JLabel("Velocity:"); l1.setFont(labelFont);
                    dialog.add(l1, gc);
                    gc.gridx = 1;
                    JSlider velSlider = new JSlider(0, 100, 80); velSlider.setPreferredSize(sliderDim);
                    dialog.add(velSlider, gc);
                    gc.gridx = 2;
                    JSpinner velSpin = new JSpinner(new SpinnerNumberModel(80, 0, 100, 1)); velSpin.setPreferredSize(spinDim);
                    dialog.add(velSpin, gc);

                    dialog.setVisible(true);
                 } else if (javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                     int currentStep = (int) vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
                     int offset = (currentStep >= 0) ? (currentStep / 16) * 16 : 0;
                     boolean isSynthMode = projectModel != null && 
                                           !projectModel.getTracks().isEmpty() && 
                                           projectModel.getTracks().get(0) instanceof org.chuck.deluge.model.SynthTrackModel;
                     
                      int trackType = bridge.getTrackType(currentTrack);
                      if (trackType == 2) {
                         boolean st = bridge.getStep(currentTrack, offset + slot);
                         bridge.setStep(currentTrack, offset + slot, !st);
                         if (!st) {
                            if (finalMidiOut != null) {
                               try {
                                  finalMidiOut.sendMessage(new byte[]{(byte)0x90, (byte)(60 + currentTrack), (byte)100});
                               } catch (Exception ex) {}
                            }
                         }
                         clipBtn.setBackground(!st ? trackColors[6] : new Color(0x33, 0x33, 0x33)); // Blue for MIDI Track
                      } else if (isSynthMode) {
                        boolean st = bridge.getStep(0, offset + slot);
                        bridge.setStep(0, offset + slot, !st);
                        if (!st) {
                           bridge.setPitch(0, offset + slot, (24 - 1) - currentTrack);
                           
                           // Audition Synth
                           try {
                              org.chuck.core.ChuckEvent noteEv = (org.chuck.core.ChuckEvent) vm.getGlobalObject("g_ck_noteOn");
                              if (noteEv != null) {
                                 org.chuck.core.ChuckArray pitchArr = (org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_PITCH);
                                 pitchArr.setInt(0, (long)((24 - 1) - currentTrack));

                                 noteEv.broadcast();
                              }
                           } catch (Exception ex) {}
                        }
                        clipBtn.setBackground(!st ? trackColors[0] : new Color(0x33, 0x33, 0x33));
                     } else {
                        boolean stepState = bridge.getStep(currentTrack, offset + slot);
                        bridge.setStep(currentTrack, offset + slot, !stepState);
                        clipBtn.setBackground(!stepState ? trackColors[currentTrack] : new Color(0x33, 0x33, 0x33));
                        if (!stepState) {
                           String sp = (String) vm.getGlobalObject("g_sample_" + currentTrack);
                           playWaveFile(sp);
                        }
                     }
                  }

               }
             });

          } else if (viewMode == GridViewMode.SONG) {
             clipBtn.addMouseListener(new java.awt.event.MouseAdapter() {
               @Override
               public void mousePressed(java.awt.event.MouseEvent e) {
                 if (javax.swing.SwingUtilities.isRightMouseButton(e)) {
                    JDialog dialog = new JDialog((Frame)javax.swing.SwingUtilities.getWindowAncestor(SwingGridPanel.this), "Track Inspector", true);
                    dialog.setSize(900, 550);
                    dialog.setLocationRelativeTo(SwingGridPanel.this);
                    
                    JTabbedPane tabs = new JTabbedPane();
                    tabs.setFont(new Font("SansSerif", Font.BOLD, 22));
                    
                    // Tab 1: Presets
                    JPanel p1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 30, 30));
                    p1.setBackground(new Color(0x2b, 0x2b, 0x2b));
                    JLabel lP = new JLabel("Hot-Swap Patch Preset:"); lP.setFont(new Font("SansSerif", Font.BOLD, 18)); lP.setForeground(Color.WHITE);
                    JComboBox<String> cb = new JComboBox<>(new String[]{"000 Rich Saw Bass", "017 Impact Saw Lead", "073 Piano", "Default Sine"});
                    cb.setFont(new Font("SansSerif", Font.PLAIN, 18));
                    cb.setPreferredSize(new Dimension(400, 45));
                    p1.add(lP); p1.add(cb);
                    tabs.addTab("PRESETS", p1);
                    
                    // Tab 2: Clipboard
                    JPanel p2 = new JPanel(new FlowLayout(FlowLayout.CENTER, 40, 50));
                    p2.setBackground(new Color(0x2b, 0x2b, 0x2b));
                    JButton cloneBtn = new JButton("Clone Clip Variant"); cloneBtn.setFont(new Font("SansSerif", Font.BOLD, 24)); cloneBtn.setPreferredSize(new Dimension(300, 80));
                    JButton clearBtn = new JButton("Export MIDI Sequence"); clearBtn.setFont(new Font("SansSerif", Font.BOLD, 24)); clearBtn.setPreferredSize(new Dimension(300, 80));
                    p2.add(cloneBtn); p2.add(clearBtn);
                    tabs.addTab("CLIPBOARD", p2);
                    
                    // Tab 3: Mixer
                    JPanel p3 = new JPanel(new GridBagLayout());
                    p3.setBackground(new Color(0x2b, 0x2b, 0x2b));
                    GridBagConstraints gcm = new GridBagConstraints();
                    gcm.fill = GridBagConstraints.HORIZONTAL;
                    gcm.insets = new Insets(25, 25, 25, 25);
                    
                    gcm.gridx = 0; gcm.gridy = 0;
                    JLabel vL = new JLabel("Channel Volume:"); vL.setFont(new Font("SansSerif", Font.BOLD, 20)); vL.setForeground(Color.WHITE);
                    p3.add(vL, gcm);
                    gcm.gridx = 1;
                    JSlider vS = new JSlider(0, 100, 80); vS.setPreferredSize(new Dimension(400, 50));
                    vS.addChangeListener(ev -> System.out.println("Track " + currentTrack + " Vol: " + vS.getValue()));

                    p3.add(vS, gcm);
                    
                    gcm.gridx = 0; gcm.gridy = 1;
                    JLabel pL = new JLabel("Channel Panning:"); pL.setFont(new Font("SansSerif", Font.BOLD, 20)); pL.setForeground(Color.WHITE);
                    p3.add(pL, gcm);
                    gcm.gridx = 1;
                    JSlider pS = new JSlider(0, 100, 50); pS.setPreferredSize(new Dimension(400, 50));
                    p3.add(pS, gcm);
                    tabs.addTab("MIXER", p3);

                    // Actions hooks
                    cloneBtn.addActionListener(ev -> {
                       if (currentTrack < tracks.size()) {
                          org.chuck.deluge.model.TrackModel tModel = tracks.get(currentTrack);
                          if (!tModel.getClips().isEmpty()) {
                             tModel.addClip(tModel.getClips().get(0)); // Mock clone
                          }
                       }
                       dialog.dispose();
                       refresh();
                    });
                    
                    cb.addActionListener(ev -> {
                       if (currentTrack < tracks.size()) {
                          tracks.get(currentTrack).setName((String)cb.getSelectedItem());
                       }
                       dialog.dispose();
                       refresh();
                    });


                    
                    dialog.add(tabs);
                    dialog.setVisible(true);
                 }
               }
             });
          } else if (viewMode == GridViewMode.ARRANGEMENT) {
             clipBtn.addMouseListener(new java.awt.event.MouseAdapter() {
               @Override
               public void mousePressed(java.awt.event.MouseEvent e) {
                 if (javax.swing.SwingUtilities.isRightMouseButton(e)) {
                    JDialog dialog = new JDialog((Frame)javax.swing.SwingUtilities.getWindowAncestor(SwingGridPanel.this), "Bar Automation", true);
                    dialog.setSize(600, 350);
                    dialog.setLocationRelativeTo(SwingGridPanel.this);
                    dialog.setLayout(new GridLayout(3, 1, 20, 20));
                    dialog.add(new JLabel("  Timeline Bar " + (slot + 1) + " Automation:"));
                    dialog.add(new JCheckBox("Enable Low-Pass Filter Sweep"));
                    dialog.add(new JCheckBox("Trigger Volume Fade-In"));
                    dialog.setVisible(true);
                 }
               }
             });
          }




          clipBtn.addActionListener(e -> {
            if (viewMode == GridViewMode.SONG) {
              if ((e.getModifiers() & java.awt.event.ActionEvent.SHIFT_MASK) != 0) {
                return;
              }
              clipBtn.setBackground(Color.ORANGE); 
              
              Timer timer = new Timer(100, null);
              final boolean[] flashState = {false};
              timer.addActionListener(ev -> {
                int step = (int) vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
                if (step == 0) {
                  clipBtn.setBackground(trackColors[currentTrack]); 
                  timer.stop();
                } else {
                  flashState[0] = !flashState[0];
                  clipBtn.setBackground(flashState[0] ? Color.ORANGE : Color.LIGHT_GRAY);
                }
              });
              timer.start();
            } else if (viewMode == GridViewMode.ARRANGEMENT) {
              // Toggle Linear arrangement playback bar state
              if (clipBtn.getBackground().equals(trackColors[currentTrack])) {
                 clipBtn.setBackground(new Color(0x33, 0x33, 0x33));
              } else {
                 clipBtn.setBackground(trackColors[currentTrack]);
              }
            }






          });
        }


        if (c == 16) {
           rowPanel.add(Box.createHorizontalStrut(20)); 
        }
        rowPanel.add(clipBtn);
        rowPanel.add(Box.createHorizontalStrut(5));
      }
      add(rowPanel);
    }
    
    if (viewMode == GridViewMode.CLIP) {
       class PianoRollComponent extends JComponent {
          public PianoRollComponent() {
             setPreferredSize(new Dimension(2600, 120));
             setMaximumSize(new Dimension(2600, 120));
          }
          @Override
          protected void paintComponent(Graphics g) {
             Graphics2D g2 = (Graphics2D) g;
             int gridX = 160; // Pad 1 starts here
             
             // 18 pads = 16 * 125 + 20(spacer) + 2 * 125 = 2270 pixels total
             double totalWidth = 18 * 125.0 + 20.0;
             double keyW = totalWidth / 28.0;
             int keyH = 110;
             
             // 28 White keys
             for (int i = 0; i < 28; i++) {
                int x = (int) (gridX + i * keyW);
                int nextX = (int) (gridX + (i + 1) * keyW);
                int kw = (nextX - x) - 2;
                
                g2.setColor(Color.WHITE);
                g2.fillRect(x, 0, kw, keyH);
                g2.setColor(Color.BLACK);
                g2.drawRect(x, 0, kw, keyH);
             }
             
             // Black keys
             int[] blackKeyOffsets = {0, 1, 3, 4, 5, 7, 8, 10, 11, 12, 14, 15, 17, 18, 19, 21, 22, 24, 25, 26};
             for (int offsetKey : blackKeyOffsets) {
                int x = (int) (gridX + offsetKey * keyW);
                int nextX = (int) (gridX + (offsetKey + 1) * keyW);
                int kw = nextX - x;
                int bx = x + kw - (int)(keyW / 3.0);
                
                g2.setColor(new Color(0x1a, 0x1a, 0x1a));
                g2.fillRect(bx, 0, (int)(keyW / 2.0), keyH / 2);
             }
             
             // Draw QWERTY assistants
             g2.setFont(new Font("SansSerif", Font.BOLD, 14));
             String[] whiteQwerty = {"Z", "X", "C", "V", "B", "N", "M"};
             for (int i = 0; i < 7; i++) {
                int x = (int) (gridX + i * keyW);
                g2.setColor(Color.GRAY);
                g2.drawString(whiteQwerty[i], x + 10, keyH - 15);
             }
             
             String[] blackQwerty = {"S", "D", "", "G", "H", "J"};
             for (int i = 0; i < blackKeyOffsets.length; i++) {
                if (i < 6 && !blackQwerty[i].isEmpty()) {
                   int offsetKey = blackKeyOffsets[i];
                   int x = (int) (gridX + offsetKey * keyW);
                   int nextX = (int) (gridX + (offsetKey + 1) * keyW);
                   int kw = nextX - x;
                   int bx = x + kw - (int)(keyW / 3.0);
                   g2.setColor(Color.WHITE);
                   g2.drawString(blackQwerty[i], bx + 2, (keyH / 2) - 5);
                }
             }

          }
       }
       add(Box.createVerticalStrut(10));
       add(new PianoRollComponent());
    }

    
    revalidate();
    repaint();

     org.rtmidijava.RtMidiOut midiOut = null;
     try {
        midiOut = org.rtmidijava.RtMidiFactory.createDefaultOut();
        if (midiOut.getPortCount() > 0) {
           midiOut.openPort(0, "DelugeOut");
        }

     } catch (Exception ex) {}

     final int[] lastCol = {-1};
     this.finalMidiOut = midiOut;


     Timer playheadTimer = new Timer(100, e -> {
        int currentStep = (int) vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
        if (currentStep >= 0) {
           int activeCol = (currentStep % 16); 
           
           if (activeCol != lastCol[0]) {
              lastCol[0] = activeCol;
                for (int t = 0; t < 8; t++) {
                   if (bridge.getStep(t, activeCol)) {
                      if (finalMidiOut != null) {
                         try {
                            int trackType = bridge.getTrackType(t);
                             if (trackType == 2) {
                                finalMidiOut.sendMessage(new byte[]{(byte)0x90, (byte)(60 + t), (byte)100});
                                final int trk = t;
                                if (pads[trk][16] != null) {
                                   pads[trk][16].setBackground(Color.YELLOW);
                                   Timer flashOff = new Timer(60, ev -> pads[trk][16].setBackground(new Color(0x33, 0x99, 0xff)));
                                   flashOff.setRepeats(false);
                                   flashOff.start();
                                }

                            } else {
                               finalMidiOut.sendMessage(new byte[]{(byte)0x90, (byte)(36 + t * 2), (byte)100});
                            }
                         } catch (Exception ex) {}
                      }
                   }
                }

               // Sidechain Compressor Ducking tied to Track 0 (Kick)
               if (bridge.getStep(0, activeCol)) {
                  for (int t = 1; t < 8; t++) {
                     final int trackIdx = t;
                     bridge.setTrackLevel(trackIdx, 0.15); // Duck
                     
                     Timer duckRelease = new Timer(120, ev -> {
                        bridge.setTrackLevel(trackIdx, 0.70); // Release / Restore
                     });
                     duckRelease.setRepeats(false);
                     duckRelease.start();
                  }
               }
            }

           for (int t = 0; t < 8; t++) {
              for (int c = 0; c < 16; c++) {
                 if (pads[t][c] != null) {
                    if (c == activeCol) {
                       pads[t][c].setBorder(BorderFactory.createLineBorder(Color.YELLOW, 4));
                    } else {
                       pads[t][c].setBorder(UIManager.getBorder("Button.border"));
                    }
                 }
              }
           }
        }
     });
     playheadTimer.start();

  }

  private void playWaveFile(String path) {
     if (path == null || path.isEmpty()) return;
     new Thread(() -> {
        try {
           java.io.File file = new java.io.File(path);
           if (file.exists()) {
              javax.sound.sampled.AudioInputStream stream = javax.sound.sampled.AudioSystem.getAudioInputStream(file);
              javax.sound.sampled.Clip clip = javax.sound.sampled.AudioSystem.getClip();
              clip.open(stream);
              clip.start();
           }
        } catch (Exception e) {}
     }).start();
  }
}

