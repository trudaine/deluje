import os
import json
import subprocess
import sys

# Resolve project and temp directories relative to this script's directory!
script_dir = os.path.dirname(os.path.abspath(__file__))
project_root = os.path.abspath(os.path.join(script_dir, "../../../"))
temp_dir = os.path.join(project_root, "target", "recorder")
timeline_path = os.path.join(temp_dir, "narration_timeline.json")
output_video = os.path.join(project_root, "target", "Swing_Sequencer_Bootcamp.mp4")

print("=== STARTING VIDEO AND AUDIO COMPILATION PIPELINE ===")
print(f"Project Root: {project_root}")
print(f"Temp Directory: {temp_dir}")
print(f"Output Video: {output_video}")

if not os.path.exists(timeline_path):
    print(f"Error: timeline file not found at {timeline_path}")
    sys.exit(1)

with open(timeline_path, "r") as f:
    timeline = json.load(f)

# 1. Generate Voiceover WAV files using macOS 'say' CLI
print("\n[1/3] Synthesizing voiceovers using macOS 'say'...")
narr_files = []
for idx, item in enumerate(timeline):
    timestamp_ms = item["timestamp_ms"]
    text = item["text"]
    narr_wav = os.path.join(temp_dir, f"narr_{idx}.wav")
    
    print(f"  - [{timestamp_ms}ms] Speaking: \"{text}\"")
    # Generate speech file using say (outputting to AIFF first, then convert to WAV)
    temp_aiff = os.path.join(temp_dir, f"narr_{idx}.aiff")
    subprocess.run(["say", "-v", "Flo (English (US))", "-o", temp_aiff, text])
    # Convert AIFF to 44.1kHz stereo WAV for easy mixing
    subprocess.run([
        "ffmpeg", "-y", "-i", temp_aiff, 
        "-acodec", "pcm_s16le", "-ar", "44100", "-ac", "2", narr_wav
    ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    
    # Remove temp AIFF
    if os.path.exists(temp_aiff):
        os.remove(temp_aiff)
        
    narr_files.append((timestamp_ms, narr_wav))

# 2. Build FFmpeg Filter Complex to delay and mix narration tracks with master track
print("\n[2/3] Building FFmpeg audio mix and video compilation filter...")
# Inputs:
# - i=0: video frame sequence
# - i=1: master audio WAV
# - i=2..N: narration WAVs
ffmpeg_cmd = [
    "ffmpeg", "-y",
    "-r", "30",
    "-f", "image2",
    "-i", os.path.join(temp_dir, "frames", "frame_%04d.png"),
    "-i", os.path.join(temp_dir, "audio_master.wav")
]

# Add narration inputs
for _, narr_wav in narr_files:
    ffmpeg_cmd.extend(["-i", narr_wav])

# Build filter complex for audio delay, volume scaling, and mixing
# We scale master synth (input 1) to 0.5, narration to 0.8, delay each narration,
# and mix them using amix with normalize=0 to prevent 1/N volume crushing!
filter_parts = []
mix_inputs = []

# Scale master audio (synth) to 0.5
filter_parts.append("[1:a]volume=0.5[master_scaled]")
mix_inputs.append("[master_scaled]")

for idx, (delay_ms, _) in enumerate(narr_files):
    # Narration inputs start at index 2 (so input 2 is index 0 in narr_files)
    input_idx = 2 + idx
    scaled_label = f"[narr_scaled_{idx}]"
    delayed_label = f"[delay_narr_{idx}]"
    
    # Scale narration track to 0.8
    filter_parts.append(f"[{input_idx}:a]volume=0.8{scaled_label}")
    # Delay filter: adelay=delay_ms|delay_ms (left and right channels)
    filter_parts.append(f"{scaled_label}adelay={delay_ms}|{delay_ms}{delayed_label}")
    mix_inputs.append(delayed_label)

# Join all filters, then mix them together with the master track
# amix=inputs=N:duration=first:normalize=0 (no automatic volume division!)
filter_complex = "; ".join(filter_parts)
if filter_parts:
    filter_complex += "; "
    filter_complex += "".join(mix_inputs) + f"amix=inputs={len(mix_inputs)}:duration=first:normalize=0[mixed_audio]"
    audio_map = "[mixed_audio]"
else:
    audio_map = "1:a" # No narration, fallback to master audio only

ffmpeg_cmd.extend([
    "-filter_complex", filter_complex,
    "-map", "0:v",
    "-map", audio_map,
    "-c:v", "libx264",
    "-pix_fmt", "yuv420p",
    "-crf", "18",
    "-preset", "veryfast",
    output_video
])

# 3. Execute FFmpeg
print("\n[3/3] Compiling HD MP4 video with FFmpeg...")
print(f"  Command: {' '.join(ffmpeg_cmd)}")
result = subprocess.run(ffmpeg_cmd)

if result.returncode == 0:
    print(f"\n🎉 SUCCESS! YouTube-ready video generated successfully at: {output_video}")
else:
    print(f"\n❌ Error: FFmpeg compilation failed with exit code {result.returncode}")
