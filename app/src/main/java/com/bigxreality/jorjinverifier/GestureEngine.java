package com.bigxreality.jorjinverifier;

import android.util.Log;

import com.jorjin.jjsdk.tof.TofFrameData;

import java.util.ArrayList;
import java.util.List;

/**
 * Recognises gestures from raw ToF ranging, the way Jorjin's own gesture app does.
 *
 * JJSDK's TofGestureEvent never fires on this hardware because the module's built-in parser is
 * not producing gesture packets. The vendor app does not rely on it either: it caches frames
 * while a hand is present and, once the hand leaves, hands the whole burst to
 * libcalculate_gesture.so. Directional gestures come from that library; push and pull are
 * decided from the change in average distance, which is what the vendor's parse loop does with
 * a threshold of 500.
 *
 * Sensitivity lives here rather than in the module's firmware, so it can actually be tuned.
 */
final class GestureEngine {
    private static final String TAG = "JorjinGesture";
    private static final int ZONES = 64;                 // the module reports an 8x8 grid
    private static final int MIN_FRAMES = 2;             // vendor discards bursts shorter than this
    private static final int MAX_FRAMES = 60;            // bounds memory if a hand never leaves
    /** Distance change that counts as a deliberate push or pull, in the module's own units. */
    private final int pushPullDelta;
    /** A zone closer than this counts as a hand. Same units as medianRange. */
    private final float presenceRange;

    private final com.jorjin.gesture.services.tof.ParseRunnable engine =
            new com.jorjin.gesture.services.tof.ParseRunnable();
    private final List<float[]> rangeFrames = new ArrayList<>();
    private final List<float[]> signalFrames = new ArrayList<>();
    private boolean present;
    private int initialAvg;

    GestureEngine(int pushPullDelta, float presenceRange) {
        this.pushPullDelta = pushPullDelta;
        this.presenceRange = presenceRange;
    }

    /**
     * Feeds one frame. Returns a gesture name once a burst completes, otherwise null, so the
     * caller can simply forward whatever comes back.
     */
    synchronized String offer(TofFrameData frame) {
        if (frame == null || frame.medianRange == null) return null;
        float[] range = frame.medianRange;
        float[] signal = frame.peakSignalRate;
        boolean handNow = isHand(range);

        if (handNow) {
            if (!present) {
                present = true;
                initialAvg = average(range);
                rangeFrames.clear();
                signalFrames.clear();
            }
            if (rangeFrames.size() < MAX_FRAMES) {
                rangeFrames.add(range.clone());
                signalFrames.add(signal == null ? new float[range.length] : signal.clone());
            }
            return null;
        }

        if (!present) return null;
        present = false;
        return finish();
    }

    private String finish() {
        int frames = rangeFrames.size();
        if (frames < MIN_FRAMES) {
            rangeFrames.clear();
            signalFrames.clear();
            return null;
        }
        // Push and pull are decided from how the average distance moved across the burst; the
        // library is only asked about direction.
        int endAvg = average(rangeFrames.get(frames - 1));
        int delta = endAvg - initialAvg;
        String gesture = null;
        if (delta > pushPullDelta) gesture = "PUSH";
        else if (-delta > pushPullDelta) gesture = "PULL";
        else gesture = callEngine(frames);

        rangeFrames.clear();
        signalFrames.clear();
        return gesture == null || gesture.trim().isEmpty() ? null : gesture.trim();
    }

    private String callEngine(int frames) {
        int[] peak = new int[frames * ZONES];
        int[] distance = new int[frames * ZONES];
        for (int i = 0; i < frames; i++) {
            float[] range = rangeFrames.get(i);
            float[] signal = signalFrames.get(i);
            for (int z = 0; z < ZONES; z++) {
                int at = i * ZONES + z;
                peak[at] = z < signal.length ? Math.round(signal[z]) : 0;
                distance[at] = z < range.length ? Math.round(range[z]) : 0;
            }
        }
        try {
            return engine.calculateGesture(peak, distance);
        } catch (Throwable error) {
            Log.e(TAG, "calculateGesture 失敗", error);
            return null;
        }
    }

    private boolean isHand(float[] range) {
        for (float value : range) {
            if (value > 0f && value < presenceRange) return true;
        }
        return false;
    }

    private static int average(float[] range) {
        long total = 0;
        int count = 0;
        for (float value : range) {
            if (value > 0f) { total += Math.round(value); count++; }
        }
        return count == 0 ? 0 : (int) (total / count);
    }
}
