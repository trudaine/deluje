package org.chuck.deluge.engine.dsp;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;

/**
 * Pure Java implementation of a WAV recorder (WvOut).
 */
public class NativeWavExporter {
    private final float sampleRate;
    private File outputFile;
    private boolean active = false;
    private java.io.ByteArrayOutputStream buffer;

    public NativeWavExporter(float sampleRate) {
        this.sampleRate = sampleRate;
    }

    public void start(String path) {
        this.outputFile = new File(path);
        this.buffer = new java.io.ByteArrayOutputStream();
        this.active = true;
        System.out.println("[NativeWavExporter] Recording to: " + path);
    }

    public void record(float left, float right) {
        if (!active) return;
        
        short sL = (short) (Math.max(-1f, Math.min(1f, left)) * 32767f);
        short sR = (short) (Math.max(-1f, Math.min(1f, right)) * 32767f);
        
        byte[] bytes = new byte[4];
        bytes[0] = (byte) (sL & 0xFF);
        bytes[1] = (byte) ((sL >> 8) & 0xFF);
        bytes[2] = (byte) (sR & 0xFF);
        bytes[3] = (byte) ((sR >> 8) & 0xFF);
        
        buffer.writeBytes(bytes);
    }

    public void stop() {
        if (!active) return;
        active = false;
        try {
            byte[] data = buffer.toByteArray();
            AudioFormat format = new AudioFormat(sampleRate, 16, 2, true, false);
            AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(data), format, data.length / 4);
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, outputFile);
            ais.close();
            System.out.println("[NativeWavExporter] Saved " + data.length / 4 + " frames to " + outputFile.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("[NativeWavExporter] Error saving WAV: " + e.getMessage());
        }
    }

    public boolean isActive() {
        return active;
    }
}
