import wave
import sys
import os

def align_wav(input_path, output_path, target_sample=57856, threshold_ratio=0.05):
    print(f"Reading {input_path}...")
    if not os.path.exists(input_path):
        print(f"Error: Input file {input_path} does not exist.")
        return False

    with wave.open(input_path, 'rb') as w:
        params = w.getparams()
        num_channels = params.nchannels
        sampwidth = params.sampwidth  # bytes per sample (2=16-bit, 3=24-bit, 4=32-bit)
        framerate = params.framerate
        num_frames = params.nframes
        frames_bytes = w.readframes(num_frames)

    # Work on RAW frame bytes so any bit depth (16/24/32) is preserved exactly. Decode samples only
    # for onset detection. The Deluge resamples 24-bit mono, so 16-bit-only would corrupt the data.
    frame_size = num_channels * sampwidth

    # Per-frame max abs amplitude across channels (signed decode of each channel's sampwidth bytes).
    frame_maxes = []
    for f in range(num_frames):
        base = f * frame_size
        m = 0
        for c in range(num_channels):
            off = base + c * sampwidth
            v = int.from_bytes(frames_bytes[off:off + sampwidth], 'little', signed=True)
            m = max(m, abs(v))
        frame_maxes.append(m)

    peak = max(frame_maxes) if frame_maxes else 0
    if peak == 0:
        print("Error: File is completely silent.")
        return False

    threshold = peak * threshold_ratio
    onset_frame = next((i for i, v in enumerate(frame_maxes) if v > threshold), -1)
    if onset_frame == -1:
        print("Error: Could not detect onset.")
        return False

    print(f"Format: {num_channels}ch {sampwidth * 8}-bit {framerate}Hz, {num_frames} frames")
    print(f"Detected onset at frame {onset_frame} ({onset_frame / framerate:.3f} seconds)")
    print(f"Target onset is frame {target_sample} ({target_sample / framerate:.3f} seconds)")

    diff = target_sample - onset_frame
    if diff > 0:
        print(f"Padding beginning with {diff} frames of silence...")
        out_bytes = (b"\x00" * (diff * frame_size)) + frames_bytes
    elif diff < 0:
        print(f"Trimming {-diff} frames from the beginning...")
        out_bytes = frames_bytes[(-diff) * frame_size:]
    else:
        print("File is already perfectly aligned!")
        out_bytes = frames_bytes

    print(f"Writing aligned file to {output_path}...")
    output_dir = os.path.dirname(output_path)
    if output_dir:
        os.makedirs(output_dir, exist_ok=True)

    with wave.open(output_path, 'wb') as w:
        w.setnchannels(num_channels)
        w.setsampwidth(sampwidth)
        w.setframerate(framerate)
        w.writeframes(out_bytes)

    print("Alignment complete!")
    return True

if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("Usage: python align_recording.py <input_wav> <output_wav> [target_sample]")
        print("Default target_sample is 57856 (block 452 at 44100Hz)")
        sys.exit(1)
        
    input_wav = sys.argv[1]
    output_wav = sys.argv[2]
    target = 57856
    if len(sys.argv) > 3:
        target = int(sys.argv[3])
        
    align_wav(input_wav, output_wav, target)
