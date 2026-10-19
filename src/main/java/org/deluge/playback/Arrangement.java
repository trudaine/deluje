package org.deluge.playback;

import org.deluge.model.ProjectModel;
import org.deluge.modulation.params.ParamManager;

public class Arrangement {
  public ParamManager paramManager = new ParamManager(); // Arrangement-level automation
  private ProjectModel project;
  public int lastProcessedPos = 0;
  public int swungTicksTilNextEvent = Integer.MAX_VALUE;

  public Arrangement() {}

  public Arrangement(ProjectModel project) {
    setProject(project);
  }

  public void setProject(ProjectModel project) {
    this.project = project;
    if (project != null) {
      this.paramManager = project.getSongParamManager();
    }
  }

  public ProjectModel getProject() {
    return project;
  }

  public void advance(int numTicks) {
    doTickForward(numTicks);
  }

  public void doTickForward(int posIncrement) {
    lastProcessedPos += posIncrement;
    swungTicksTilNextEvent = Integer.MAX_VALUE;

    ParamManager activeParamManager =
        project != null ? project.getSongParamManager() : paramManager;

    // 1. Process arrangement-level automation (C arrangement.cpp:120-129, 338-344, song.cpp:5822)
    if (activeParamManager.mightContainAutomation()) {
      activeParamManager.processCurrentPos(
          posIncrement, lastProcessedPos, Integer.MAX_VALUE, false, false, false);
      if (project != null) {
        project.applyAutomatedSongParams();
      }
      swungTicksTilNextEvent =
          Math.min(swungTicksTilNextEvent, activeParamManager.ticksTilNextEvent);
      if (project != null) {
        project.setSwungTicksTilNextEvent(swungTicksTilNextEvent);
      }
    } else if (project != null) {
      project.setSwungTicksTilNextEvent(Integer.MAX_VALUE);
    }
  }

  public void resetPlayPos(int newPos) {
    lastProcessedPos = newPos;
    ParamManager activeParamManager =
        project != null ? project.getSongParamManager() : paramManager;
    // C arrangement.cpp:359-365, song.cpp:5822
    if (activeParamManager.mightContainAutomation()) {
      activeParamManager.setPlayPos(newPos, Integer.MAX_VALUE, false);
      if (project != null) {
        project.applyAutomatedSongParams();
        project.setSwungTicksTilNextEvent(activeParamManager.ticksTilNextEvent);
      }
    }
  }
}
