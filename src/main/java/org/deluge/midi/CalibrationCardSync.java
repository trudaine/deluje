package org.deluge.midi;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

/**
 * Orchestrates the calibration hardware-capture file workflow over USB, on top of a {@link
 * CardTransport}. Eliminates the physical SD-card shuffle: push a generated calibration song + its
 * synth presets straight onto the card, then pull the resampled recording back — the parts of the
 * capture loop the firmware SysEx protocol actually supports.
 *
 * <p>NOTE: arming resample-record + pressing play is NOT scriptable — the firmware's HID SysEx is
 * display-out only (no input injection), so those two steps stay manual on the device between
 * {@link #pushCalibration} and {@link #pullRecording}. Automating them would require a new firmware
 * input-injection command.
 *
 * <p>Card layout (real Deluge): songs in {@code /SONGS}, synth presets in {@code /SYNTHS},
 * resampled output in {@code /SAMPLES/output_000.wav} (incrementing index per recording).
 */
public class CalibrationCardSync {
  public static final String SONGS_DIR = "/SONGS";
  public static final String SYNTHS_DIR = "/SYNTHS";
  public static final String SAMPLES_DIR = "/SAMPLES";

  private final CardTransport transport;

  public CalibrationCardSync(CardTransport transport) {
    this.transport = transport;
  }

  /** Progress hook (per-file). Default logs to stdout. */
  public interface Progress {
    void onFile(String remotePath, int index, int total);
  }

  private static final Progress STDOUT =
      (path, i, total) -> System.out.printf("  [%d/%d] %s%n", i, total, path);

  /**
   * Upload the song's synth presets to {@code /SYNTHS} and the song to {@code /SONGS}. Presets go
   * first so the song's by-name instrument bindings resolve on load (a missing preset makes the
   * hardware reject the song as {@code FILE_CORRUPTED}).
   *
   * @return the remote song path written (e.g. {@code "/SONGS/FM_CAL.XML"})
   */
  public String pushCalibration(File songXml, List<File> presetXmls) throws Exception {
    return pushCalibration(songXml, presetXmls, STDOUT);
  }

  public String pushCalibration(File songXml, List<File> presetXmls, Progress progress)
      throws Exception {
    int total = presetXmls.size() + 1;
    int i = 0;
    for (File preset : presetXmls) {
      String remote = SYNTHS_DIR + "/" + preset.getName();
      transport.upload(remote, Files.readAllBytes(preset.toPath()));
      progress.onFile(remote, ++i, total);
    }
    String songRemote = SONGS_DIR + "/" + songXml.getName();
    transport.upload(songRemote, Files.readAllBytes(songXml.toPath()));
    progress.onFile(songRemote, ++i, total);
    return songRemote;
  }

  /**
   * Download a resampled recording from {@code /SAMPLES} to a local file.
   *
   * @param remoteName file name under {@code /SAMPLES} (e.g. {@code "output_000.wav"})
   * @param localDest local path to write the bytes to
   */
  public void pullRecording(String remoteName, File localDest) throws Exception {
    byte[] data = transport.download(SAMPLES_DIR + "/" + remoteName);
    if (localDest.getParentFile() != null) {
      localDest.getParentFile().mkdirs();
    }
    Files.write(localDest.toPath(), data);
    System.out.printf(
        "  pulled %s -> %s (%d bytes)%n", SAMPLES_DIR + "/" + remoteName, localDest, data.length);
  }
}
