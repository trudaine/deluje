package org.chuck.deluge.ui.netbeans;

import java.io.InputStream;
import java.util.List;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.ClipModel;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.TrackModel;
import org.chuck.deluge.xml.DelugeXmlParser;

/**
 * Central ViewModel that coordinates the state between ChuckVM and the data Model. Adapted to the
 * monolithic DelugeEngineDSL (4 Kit slots, 4 Synth slots).
 */
public class MainViewModel extends BaseViewModel {
  private final ChuckVM vm;
  private final BridgeContract bridge;
  private ProjectModel projectModel;

  private int currentStep = -1;
  private int focusedTrack = 0;
  private int selectedMode = 0;
  private boolean shiftDown = false;
  private boolean playing = false;
  private boolean recording = false;
  private float bpm = 120.0f;
  private float swing = 0.5f;

  private javax.swing.Timer updateTimer;
  private float[] visBuffer;
  private double compressorGr;
  private double[] vuLevels = new double[8];

  public MainViewModel(ChuckVM vm, BridgeContract bridge) {
    this.vm = vm;
    this.bridge = bridge;
    this.projectModel = new ProjectModel();
    startPolling();
  }

  private void startPolling() {
    updateTimer =
        new javax.swing.Timer(
            30,
            e -> {
              if (vm == null) return;

              // 1. Update Playhead
              int step = (int) vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
              setCurrentStep(step);

              // 2. Update Visualizer Data
              if (vm.getDacChannel(0) != null) {
                float[] newData = vm.getDacChannel(0).getVisBuffer();
                if (newData != null) {
                  setVisBuffer(newData);
                }
              }

              // 3. Update VU Levels (First 8 slots)
              if (bridge != null) {
                double[] newVuLevels = new double[8];
                for (int i = 0; i < 8; i++) {
                  newVuLevels[i] = bridge.getTrackLevel(i);
                }
                setVuLevels(newVuLevels);
              }
            });
    updateTimer.start();
  }

  public void loadPreset(String resourcePath) {
    System.out.println("MainViewModel: Loading Preset: " + resourcePath);
    java.net.URL url = getClass().getResource(resourcePath);
    if (url == null) {
      url = getClass().getResource(resourcePath.replace(".XML", ".xml"));
    }

    if (url == null) {
      System.err.println("MainViewModel: Resource NOT FOUND in classpath: " + resourcePath);
      return;
    }

    try (InputStream is = url.openStream()) {
      String filename = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);

      if (resourcePath.toUpperCase().contains("/KITS/")) {
        org.chuck.deluge.model.KitTrackModel kit = DelugeXmlParser.parseKit(is, filename);
        ProjectModel newModel = new ProjectModel();
        newModel.addTrack(kit);
        setProjectModel(newModel);
        syncModelToEngine();

      } else if (resourcePath.toUpperCase().contains("/SONGS/")) {
        ProjectModel loadedProject = DelugeXmlParser.parseSong(is, filename);
        setProjectModel(loadedProject);
        syncModelToEngine();

      } else if (resourcePath.toUpperCase().contains("/SYNTHS/")) {
        org.chuck.deluge.model.SynthTrackModel synth = DelugeXmlParser.parseSynth(is, filename);
        ProjectModel newModel = new ProjectModel();
        newModel.addTrack(synth);
        setProjectModel(newModel);
        syncModelToEngine();
      }

      if (vm != null) vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);

    } catch (Exception e) {
      System.err.println("MainViewModel: Error loading preset: " + e.getMessage());
      e.printStackTrace();
    }
  }

  private void syncModelToEngine() {
    if (projectModel == null || bridge == null || vm == null) return;
    System.out.println("MainViewModel: Syncing model to DelugeEngineDSL...");

    bridge.clearAllSteps();

    List<TrackModel> tracks = projectModel.getTracks();
    if (tracks.isEmpty()) return;

    // Slot mapping for DelugeEngineDSL:
    // Track 0 (Kit) -> Engine slots 0, 1, 2, 3 (First 4 sounds)
    // Track 1 (Synth) -> Engine slots 4, 5, 6, 7 (Piano roll voices)

    // 1. Sync Track 0 (Kit)
    TrackModel track0 = tracks.get(0);
    if (track0 instanceof org.chuck.deluge.model.KitTrackModel kit) {
      List<org.chuck.deluge.model.KitTrackModel.KitSound> sounds = kit.getSounds();
      ClipModel activeClip = kit.getActiveClip();
      for (int i = 0; i < Math.min(4, sounds.size()); i++) {
        vm.setGlobalString("g_sample_" + i, sounds.get(i).getSamplePath());
        if (activeClip != null && i < activeClip.getRowCount()) {
          for (int s = 0; s < 16; s++) {
            bridge.setStep(i, s, activeClip.getStep(i, s).active());
          }
        }
      }
    }

    // 2. Sync Track 1 (Synth)
    if (tracks.size() > 1) {
      TrackModel track1 = tracks.get(1);
      ClipModel activeClip = track1.getActiveClip();
      if (activeClip != null) {
        // Map first 4 voices
        for (int i = 0; i < 4; i++) {
          int targetIdx = 4 + i;
          if (i < activeClip.getRowCount()) {
            for (int s = 0; s < 16; s++) {
              bridge.setStep(targetIdx, s, activeClip.getStep(i, s).active());
            }
          }
        }
      }
    }

    System.out.println("MainViewModel: Engine sync complete.");
  }

  public void togglePlayback() {
    setPlaying(!playing);
    if (vm != null) {
      vm.setGlobalInt(BridgeContract.G_PLAY, playing ? 1L : 0L);
    }
  }

  public void toggleRecording() {
    setRecording(!recording);
  }

  public boolean isShiftDown() {
    return shiftDown;
  }

  public void setShiftDown(boolean down) {
    boolean old = this.shiftDown;
    this.shiftDown = down;
    firePropertyChange("shiftDown", old, down);
  }

  public boolean isPlaying() {
    return playing;
  }

  public void setPlaying(boolean p) {
    boolean old = this.playing;
    this.playing = p;
    if (vm != null) vm.setGlobalInt(BridgeContract.G_PLAY, p ? 1L : 0L);
    firePropertyChange("playing", old, p);
  }

  public boolean isRecording() {
    return recording;
  }

  public void setRecording(boolean r) {
    boolean old = this.recording;
    this.recording = r;
    firePropertyChange("recording", old, r);
  }

  public float getBpm() {
    return bpm;
  }

  public void setBpm(float b) {
    float old = this.bpm;
    this.bpm = b;
    if (vm != null) vm.setGlobalFloat(BridgeContract.G_BPM, (double) b);
    firePropertyChange("bpm", old, b);
  }

  public float getSwing() {
    return swing;
  }

  public void setSwing(float s) {
    float old = this.swing;
    this.swing = s;
    if (vm != null) vm.setGlobalFloat(BridgeContract.G_SWING, (double) s);
    firePropertyChange("swing", old, s);
  }

  public double[] getVuLevels() {
    return vuLevels;
  }

  private void setVuLevels(double[] levels) {
    double[] old = this.vuLevels;
    this.vuLevels = levels;
    firePropertyChange("vuLevels", old, levels);
  }

  public double getCompressorGr() {
    return compressorGr;
  }

  public void setCompressorGr(double gr) {
    double old = this.compressorGr;
    this.compressorGr = gr;
    firePropertyChange("compressorGr", old, gr);
  }

  public float[] getVisBuffer() {
    return visBuffer;
  }

  private void setVisBuffer(float[] buf) {
    float[] old = this.visBuffer;
    this.visBuffer = buf;
    firePropertyChange("visBuffer", old, buf);
  }

  public ChuckVM getVm() {
    return vm;
  }

  public BridgeContract getBridge() {
    return bridge;
  }

  public ProjectModel getProjectModel() {
    return projectModel;
  }

  public void setProjectModel(ProjectModel projectModel) {
    ProjectModel old = this.projectModel;
    this.projectModel = projectModel;
    firePropertyChange("projectModel", old, projectModel);
  }

  public int getFocusedTrack() {
    return focusedTrack;
  }

  public void setFocusedTrack(int trackIdx) {
    int old = this.focusedTrack;
    this.focusedTrack = trackIdx;
    firePropertyChange("focusedTrack", old, trackIdx);
  }

  public int getSelectedMode() {
    return selectedMode;
  }

  public void setSelectedMode(int mode) {
    int old = this.selectedMode;
    this.selectedMode = mode;
    firePropertyChange("selectedMode", old, mode);
  }

  public int getCurrentStep() {
    return currentStep;
  }

  public void setCurrentStep(int step) {
    int old = this.currentStep;
    this.currentStep = step;
    firePropertyChange("currentStep", old, step);
  }

  public void previewRow(int row) {
    if (vm == null || bridge == null) return;

    // Map grid row R to engine slot
    // If Kit focused: Row 0-3 -> Slot 0-3
    // If Synth focused: Row 0-3 -> Slot 4-7
    int engineSlotIdx = (focusedTrack == 0) ? row : (4 + row);

    if (engineSlotIdx >= 8) return; // Monolithic engine limit

    System.out.println("MainViewModel: Auditioning Engine Slot " + engineSlotIdx);
    vm.setGlobalInt(BridgeContract.G_PREVIEW_TRACK, (long) engineSlotIdx);
    vm.broadcastGlobalEvent(BridgeContract.E_PREVIEW);
  }

  public void triggerNote(int midiNote) {
    if (vm == null) return;
    try {
      org.chuck.core.ChuckEvent noteEv =
          (org.chuck.core.ChuckEvent) vm.getGlobalObject("g_ck_noteOn");
      if (noteEv != null) {
        org.chuck.core.ChuckArray pitchArr =
            (org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_PITCH);
        if (pitchArr != null) {
          pitchArr.setInt(0, (long) (midiNote - 60));
          noteEv.broadcast();
        }
      }
    } catch (Exception e) {
    }
  }
}
