# Live DSP-Tap Hardware Capture & Parity Calibration

Developer workflow for capturing raw audio from a physical Deluge's DSP rendering buffer over
USB and calibrating the Java engine against it. (Moved out of the user guidebook 2026-07-23 —
this is developer tooling, not a user-facing feature; the user-facing Card Sync half stayed in
guidebook §14.8.)

## Live DSP-Tap Hardware Capture

For absolute audio-fidelity debugging, you can capture raw, sample-accurate, 32-bit
floating-point audio data directly from the physical Deluge's hardware DSP rendering buffer over
USB. This allows you to compare the physical hardware's audio output side-by-side with the Java
emulation:

* **Requirements**: Connect the physical Deluge via USB and ensure it is running the custom C++
  firmware compiled from the `feat/dsp-buffer-dump` branch.
* **Arming the Capture**: Run the hardware harness (`HardwareDspTapTest`) from your terminal:

  ```bash
  mvn test -Dtest=HardwareDspTapTest -Pslow-tests -Dgpg.skip=true
  ```

* **Triggering**: The harness communicates with the Deluge via SysEx, sends a MIDI note-on
  trigger, arms the capture on the hardware at note-onset to capture the exact attack transient,
  and reads back the 4096-sample rendering buffer chunk-by-chunk.
* **Analysis**: The captured samples are stored locally to let you run spectral analysis and
  time-resolved cosine comparisons to calibrate envelopes, filters, and modulation indices.

## Example: Debugging & Calibrating an FM Bell Patch

How to run a live debugging session to calibrate the FM synthesis engine (e.g., `068 FM Bells 1`)
to match the hardware:

1. **Connect the Deluge**: Enable USB MIDI on your hardware running the debug firmware.
2. **Synchronize the Patch**: Right-click the status panel and select **"Pull Calibration
   Files"**. This pulls the XML preset onto your computer.
3. **Capture Hardware Output**: Run the tap harness with `-Dtap.onset=true`. Strike the note pad
   on the physical Deluge. The harness arms, captures the sound, and saves the raw DSP float
   buffer to `target/tap_capture.txt`.
4. **Compare & Calibrate**: Run the FM Index Sweep test suite:

   ```bash
   mvn test -Pslow-tests -Dtest=FmIndexSweepTest
   ```

   This renders the patch inside the Java engine at different modulation index multiplier scales
   and compares each mathematically (log-spectral cosine similarity) against the hardware capture.
5. **Adjust Engine Parameters**: Use the sweep results to identify whether the mismatch is a
   constant scaling difference (e.g., index multiplier needs to be scaled by 0.5) or an envelope
   decay issue, and adjust your synthesis coefficients in `org.deluge.firmware2` to achieve
   perfect parity.
