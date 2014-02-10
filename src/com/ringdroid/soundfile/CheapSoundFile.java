/*
 * Copyright (C) 2008 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ringdroid.soundfile;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
/**
 * CheapSoundFile is the parent class of several subclasses that each
 * do a "cheap" scan of various sound file formats, parsing as little
 * as possible in order to understand the high-level frame structure
 * and get a rough estimate of the volume level of each frame.  Each
 * subclass is able to:
 *  - open a sound file
 *  - return the sample rate and number of frames
 *  - return an approximation of the volume level of each frame
 *  - write a new sound file with a subset of the frames
 *
 * A frame should represent no less than 1 ms and no more than 100 ms of
 * audio.  This is compatible with the native frame sizes of most audio
 * file formats already, but if not, this class should expose virtual
 * frames in that size range.
 */
public class CheapSoundFile {
    public interface ProgressListener {
        /**
         * Will be called by the CheapSoundFile subclass periodically
         * with values between 0.0 and 1.0.  Return true to continue
         * loading the file, and false to cancel.
         */
        boolean reportProgress(double fractionComplete);
    }

    public interface Factory {
        public CheapSoundFile create();
        public String[] getSupportedExtensions();
    }

    static Factory[] sSubclassFactories = new Factory[] {
        CheapAAC.getFactory(),
        CheapAMR.getFactory(),
        CheapMP3.getFactory(),
        CheapWAV.getFactory(),
    };

    static ArrayList<String> sSupportedExtensions = new ArrayList<String>();
    static HashMap<String, Factory> sExtensionMap =
        new HashMap<String, Factory>();

    static {
        for (Factory f : sSubclassFactories) {
            for (String extension : f.getSupportedExtensions()) {
                sSupportedExtensions.add(extension);
                sExtensionMap.put(extension, f);
            }
        }
    }

	/**
	 * Static method to create the appropriate CheapSoundFile subclass
	 * given a filename.
	 *
	 * TODO: make this more modular rather than hardcoding the logic
	 */
    public static CheapSoundFile create(String fileName,
                                        ProgressListener progressListener)
        throws java.io.FileNotFoundException,
               java.io.IOException {
        File f = new File(fileName);
        if (!f.exists()) {
            throw new java.io.FileNotFoundException(fileName);
        }
        String name = f.getName().toLowerCase();
        String[] components = name.split("\\.");
        if (components.length < 2) {
            return null;
        }
        Factory factory = sExtensionMap.get(components[components.length - 1]);
        if (factory == null) {
            return null;
        }
        CheapSoundFile soundFile = factory.create();
        soundFile.setProgressListener(progressListener);
        soundFile.ReadFile(f);
        return soundFile;
    }

    public static boolean isFilenameSupported(String filename) {
        String[] components = filename.toLowerCase().split("\\.");
        if (components.length < 2) {
            return false;
        }
        return sExtensionMap.containsKey(components[components.length - 1]);
    }

	/**
	 * Return the filename extensions that are recognized by one of
	 * our subclasses.
	 */
    public static String[] getSupportedExtensions() {
        return sSupportedExtensions.toArray(
            new String[sSupportedExtensions.size()]);
    }

    protected ProgressListener mProgressListener = null;
    protected File mInputFile = null;

    protected CheapSoundFile() {
    }

    public void ReadFile(File inputFile)
        throws java.io.FileNotFoundException,
               java.io.IOException {
        mInputFile = inputFile;
    }

    public void setProgressListener(ProgressListener progressListener) {
        mProgressListener = progressListener;
    }

    public int getNumFrames() {
        return 0;
    }

    public int getSamplesPerFrame() {
        return 0;
    }

    public int[] getFrameOffsets() {
        return null;
    }

    public int[] getFrameLens() {
        return null;
    }

    public int[] getFrameGains() {
        return null;
    }

    public int getFileSizeBytes() {
        return 0;
    }

    public int getAvgBitrateKbps() {
        return 0;
    }

    public int getSampleRate() {
        return 0;
    }

    public int getChannels() {
        return 0;
    }

    public String getFiletype() {
        return "Unknown";
    }

    /**
     * If and only if this particular file format supports seeking
     * directly into the middle of the file without reading the rest of
     * the header, this returns the byte offset of the given frame,
     * otherwise returns -1.
     */
    public int getSeekableFrameOffset(int frame) {
        return -1;
    }

    private static final char[] HEX_CHARS = {
        '0', '1', '2', '3', '4', '5', '6', '7',
        '8', '9', 'a', 'b', 'c', 'd', 'e', 'f' };
    public static String bytesToHex (byte hash[]) {
        char buf[] = new char[hash.length * 2];
        for (int i = 0, x = 0; i < hash.length; i++) {
            buf[x++] = HEX_CHARS[(hash[i] >>> 4) & 0xf];
            buf[x++] = HEX_CHARS[hash[i] & 0xf];
        }
        return new String(buf);
    }

    public String computeMd5OfFirst10Frames()
            throws java.io.FileNotFoundException,
                   java.io.IOException,
                   java.security.NoSuchAlgorithmException {
        int[] frameOffsets = getFrameOffsets();
        int[] frameLens = getFrameLens();
        int numFrames = frameLens.length;
        if (numFrames > 10) {
            numFrames = 10;
        }

        MessageDigest digest = java.security.MessageDigest.getInstance("MD5");
        FileInputStream in = new FileInputStream(mInputFile);
        int pos = 0;
        for (int i = 0; i < numFrames; i++) {
            int skip = frameOffsets[i] - pos;
            int len = frameLens[i];
            if (skip > 0) {
                in.skip(skip);
                pos += skip;
            }
            byte[] buffer = new byte[len];
            in.read(buffer, 0, len);
            digest.update(buffer);
            pos += len;
        }
        in.close();
        byte[] hash = digest.digest();
        return bytesToHex(hash);
    }

    public void WriteFile(File outputFile, int startFrame, int numFrames)
            throws java.io.IOException {
    }
    
    public int getAverageGain(int startFrame, int numFrames) {
    	return 0;
    }
    
    public int getMaxGain(int startFrame, int numFrames) {
    	
    	int gains[] = getFrameGains();
    	if (gains == null)
    		return 0;
    	int result = Integer.MIN_VALUE;
    	int endFrame = startFrame + numFrames;
    	for (int i = startFrame; i < endFrame; i++)
    		result = (result < gains[i]) ? gains[i] : result;
    	return result;
    }
    
    private final static int SILENCE_GAIN = 2;
    private final static int SILENCE_DURAION_MS = 100;
    public ArrayList<Integer> getWords(int startFrame, int numFrames) {
    	int gains[] = getFrameGains();
    	int minSilence = (SILENCE_DURAION_MS * getSampleRate()/
    			getSamplesPerFrame())/1000;
    	ArrayList<Integer> silences = new ArrayList<Integer>(2*numFrames/minSilence+4);
    	int i = startFrame;
    	int find = 0;
    	int endFrame = startFrame + numFrames;
    	silences.add(startFrame);
    	silences.add(startFrame);
    	do {
    		if (find == 0) {
    			if (gains[i] < SILENCE_GAIN) {
    				int j = i;
    				for (j = i; j < endFrame; j++) {
    					if (gains[j] > SILENCE_GAIN)
    						break;
    				}
    				if (j - i > minSilence) {
    					silences.add(i);
    					silences.add(j-1);
    				}
    				i = j;
    			} else {
    				i++;
    			}
    		}
    	} while (i < endFrame);
    	silences.add(endFrame);
    	silences.add(endFrame);
    	ArrayList<Integer> words = new ArrayList<Integer>(silences.size()+2);
    	int count = silences.size() - 1;
    	int k;
    	k = 1;
    	do {
    		int end = silences.get(k+1);
    		int sum = 0;
    		for (int l = silences.get(k); l < end; l++) {
    			sum += gains[l];
    			if (sum > minSilence*SILENCE_GAIN*5) {
    				words.add(silences.get(k));
    				words.add(end);
    				break;
    			}
    		}
    		k += 2;
    	} while (k < count);
    	return words;
    }
    
    public int getBeat(int startFrame, int numFrames) {
    	int gains[] = getFrameGains();
    	if (gains == null || gains.length < 10)
    		return 0;
    	int frames = gains.length;
//    	ArrayList<Integer> peaks = new ArrayList<Integer>(frames/2);
//    	for (int i = 1; i < frames-1; i++) {
//    		if (gains[i] > gains[i-1] && gains[i] > gains[i+1])
//    			peaks.add(i);
//    	}
    	int maxGain = getMaxGain(startFrame, numFrames);
    	
    	return 0;
    }
};
