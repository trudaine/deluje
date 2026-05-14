package org.chuck.deluge.firmware.gui.ui.browser;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class FileBrowser {
  private File currentDir;
  private final List<File> recentFiles = new ArrayList<>();

  public FileBrowser(String startDir) {
    this.currentDir = new File(startDir);
  }

  public File[] listFiles() {
    return currentDir.listFiles();
  }

  public void selectFile(File f) {
    if (f.isDirectory()) {
      currentDir = f;
    } else {
      recentFiles.add(0, f);
      if (recentFiles.size() > 10) recentFiles.remove(10);
    }
  }
}
