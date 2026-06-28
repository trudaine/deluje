import wave
import struct
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
        sampwidth = params.sampwidth
        framerate = params.framerate
        num_frames = params.nframes
        
        if sampwidth != 2:
            print("Error: Only 16-bit WAV files are supported.")
            return False
            
        frames_bytes = w.readframes(num_frames)
        
    # Unpack frames
    # 16-bit signed integer is 'h' in struct
    num_samples = num_frames * num_channels
    samples = struct.unpack(f"<{num_samples}h", frames_bytes)
    
    # Convert to frames of max absolute value across channels to detect onset
    frame_maxes = []
    for i in range(0, num_samples, num_channels):
        frame_samples = samples[i:i+num_channels]
        frame_maxes.append(max(abs(s) for s in frame_samples))
        
    # Find peak to set threshold
    peak = max(frame_maxes)
    if peak == 0:
        print("Error: File is completely silent.")
        return False
        
    threshold = peak * threshold_ratio
    
    # Find onset (first frame exceeding threshold)
    onset_frame = -1
    for i, val in enumerate(frame_maxes):
        if val > threshold:
            onset_frame = i
            break
            
    if onset_frame == -1:
        print("Error: Could not detect onset.")
        return False
        
    print(f"Detected onset at frame {onset_frame} ({onset_frame / framerate:.3f} seconds)")
    print(f"Target onset is frame {target_sample} ({target_sample / framerate:.3f} seconds)")
    
    # Align
    diff = target_sample - onset_frame
    if diff > 0:
        # Pad with silence (zero frames) at the beginning
        print(f"Padding beginning with {diff} frames of silence...")
        padded_samples = [0] * (diff * num_channels) + list(samples)
    elif diff < 0:
        # Trim beginning
        trim_frames = -diff
        print(f"Trimming {trim_frames} frames from the beginning...")
        padded_samples = list(samples[trim_frames * num_channels:])
    else:
        print("File is already perfectly aligned!")
        padded_samples = list(samples)
        
    # Write back
    print(f"Writing aligned file to {output_path}...")
    # Ensure output directory exists
    output_dir = os.path.dirname(output_path)
    if output_dir:
        os.makedirs(output_dir, exist_ok=True)
        
    with wave.open(output_path, 'wb') as w:
        w.setparams(params)
        # We might have changed the number of frames
        new_num_frames = len(padded_samples) // num_channels
        w.setnframes(new_num_frames)
        w.writeframes(struct.pack(f"<{len(padded_samples)}h", *padded_samples))
        
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
