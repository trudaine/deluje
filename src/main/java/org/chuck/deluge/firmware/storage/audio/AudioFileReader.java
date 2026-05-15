package org.chuck.deluge.firmware.storage.audio;

import org.chuck.deluge.firmware.model.sample.Sample;
import java.io.*;
import java.nio.*;

public class AudioFileReader {
    public static Sample readSample(String path) throws IOException {
        File file = new File(path);
        if (!file.exists()) return null;

        Sample sample = new Sample();
        sample.fileName = file.getName();

        try (FileInputStream fis = new FileInputStream(file)) {
            DataInputStream dis = new java.io.DataInputStream(fis);
            
            // RIFF header
            int riff = Integer.reverseBytes(dis.readInt());
            if (riff != 0x46464952) { // "RIFF" in little-endian as seen by big-endian read
                 System.out.println("Invalid RIFF: " + Integer.toHexString(riff));
                 return null;
            }
            dis.readInt(); // size
            int wave = Integer.reverseBytes(dis.readInt());
            if (wave != 0x45564157) { // "WAVE"
                 System.out.println("Invalid WAVE: " + Integer.toHexString(wave));
                 return null;
            }

            while (fis.available() >= 8) {
                int chunkId = dis.readInt(); // Big-endian read of little-endian data
                int chunkLen = Integer.reverseBytes(dis.readInt());
                
                if (chunkId == 0x666d7420) { // 'fmt ' (little-endian on disk, big-endian in int)
                    short format = Short.reverseBytes(dis.readShort());
                    sample.numChannels = Short.reverseBytes(dis.readShort());
                    sample.sampleRate = Integer.reverseBytes(dis.readInt());
                    dis.readInt(); // byte rate
                    dis.readShort(); // block align
                    sample.byteDepth = Short.reverseBytes(dis.readShort()) / 8;
                    if (chunkLen > 16) dis.skipBytes(chunkLen - 16);
                } else if (chunkId == 0x64617461) { // 'data'
                    byte[] rawData = new byte[chunkLen];
                    dis.readFully(rawData);
                    ByteBuffer bb = ByteBuffer.wrap(rawData).order(ByteOrder.LITTLE_ENDIAN);
                    int numSamples = chunkLen / (sample.numChannels * sample.byteDepth);
                    sample.data = new float[numSamples * sample.numChannels];
                    for (int i = 0; i < sample.data.length; i++) {
                        if (sample.byteDepth == 2) sample.data[i] = bb.getShort() / 32768.0f;
                        else if (sample.byteDepth == 1) sample.data[i] = (bb.get() & 0xFF) / 128.0f - 1.0f;
                    }
                } else if (chunkId == 0x736d706c) { // 'smpl'
                    dis.skipBytes(12);
                    int midiNote = Integer.reverseBytes(dis.readInt());
                    int midiPitchFraction = Integer.reverseBytes(dis.readInt());
                    if (midiNote < 128) {
                        sample.midiNoteFromFile = midiNote + (midiPitchFraction / 4294967296.0f);
                    }
                    dis.skipBytes(12);
                    int numLoops = Integer.reverseBytes(dis.readInt());
                    dis.readInt(); // sampler data
                    if (numLoops > 0) {
                        dis.readInt(); // loop id
                        dis.readInt(); // type
                        sample.fileLoopStartSamples = Integer.reverseBytes(dis.readInt());
                        sample.fileLoopEndSamples = Integer.reverseBytes(dis.readInt());
                        dis.skipBytes(chunkLen - 44);
                    } else {
                        dis.skipBytes(chunkLen - 36);
                    }
                } else if (chunkId == 0x696e7374) { // 'inst'
                    int midiNote = dis.readByte() & 0xFF;
                    int fineTune = dis.readByte();
                    if (midiNote < 128) {
                        sample.midiNoteFromFile = midiNote - fineTune * 0.01f;
                    }
                    dis.skipBytes(chunkLen - 2);
                } else {
                    dis.skipBytes(chunkLen);
                }
                
                if (chunkLen % 2 != 0 && fis.available() > 0) dis.readByte(); // padding
            }
        }
        return sample;
    }
}
