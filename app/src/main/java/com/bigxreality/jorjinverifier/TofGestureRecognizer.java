package com.bigxreality.jorjinverifier;

import com.jorjin.jjsdk.tof.TofGestureEvent;

/**
 * Recognises gestures from the ToF module's raw depth frames, the way the vendor Gesture app
 * does, so gestures work on firmware that predates the module's built-in gesture engine.
 *
 * <h2>Why this exists</h2>
 * JJSDK's {@code TofGestureEvent} path reads a gesture bitfield the <em>firmware</em> fills in.
 * A module running firmware older than v1.2.2 never sets those bits, so that path can never
 * produce an event no matter how healthy the USB link is. The vendor's own app sidesteps this
 * entirely: it ships {@code libcalculate_gesture.so}, whose only JNI entry point is
 * {@code ParseRunnable.calculateGesture}, and computes gestures host-side from the same 8x8
 * depth grid. JJSDK hands us that grid through {@code TofIncomingFrameListener}, so the same
 * approach is open to us - still the glasses' native ToF sensor, still through JJSDK.
 *
 * <h2>How it works</h2>
 * Each frame carries 64 zones laid out as an 8x8 grid ({@code index = row * 8 + col}); JJSDK
 * has already zeroed the zones whose VL53L5CX target status was not valid. Zones with a
 * plausible range form the hand; their centroid and mean distance are tracked from the moment
 * the hand appears until it leaves. The dominant axis of that travel decides the gesture:
 * horizontal to LEFT/RIGHT, vertical to UP/DOWN, distance to PUSH/PULL, and a hand that stays
 * put long enough to HALT.
 *
 * <p>Thresholds are deliberately constants in one place: they are the part that needs tuning
 * against a real unit, and the axis orientation in particular needs on-device confirmation.
 */
final class TofGestureRecognizer {
    /** Zones per side of the sensor's square grid. */
    static final int GRID = 8;
    static final int ZONES = GRID * GRID;

    /** Ignore anything closer than the cover glass or beyond comfortable gesture reach. */
    static final float MIN_RANGE_MM = 20f;
    static final float MAX_RANGE_MM = 450f;
    /** Fewer lit zones than this is noise, not a hand. */
    static final int MIN_ACTIVE_ZONES = 3;
    /** Centroid travel, in zones, before a swipe counts as directional. */
    static final float MIN_ZONE_TRAVEL = 1.5f;
    /** Mean-distance travel, in mm, before it counts as PUSH or PULL. */
    static final float MIN_RANGE_TRAVEL_MM = 60f;
    /** A hand held still at least this long reads as HALT. */
    static final long HALT_MIN_MS = 600L;
    /** Slower than this is not a swipe; it falls through to HALT or nothing. */
    static final long MAX_SWIPE_MS = 1600L;
    /** A hand must be gone this long before the next gesture can start. */
    static final long RELEASE_MS = 80L;

    /**
     * Sensor orientation. Increasing column index is taken as RIGHT and increasing row index as
     * DOWN. If a real unit reports swipes mirrored, flip these two rather than the maths.
     */
    static final boolean COLUMN_INCREASES_RIGHT = true;
    static final boolean ROW_INCREASES_DOWN = true;

    interface Callback {
        /** @param action one of {@link TofGestureEvent}'s ACTION_* constants. */
        void onRecognised(int gesture, int action, long eventTimeNanos);
    }

    private final Callback callback;

    private boolean tracking;
    private float startRow, startCol, startRange;
    private float lastRow, lastCol, lastRange;
    private long startMs;
    private long lastSeenMs;

    TofGestureRecognizer(Callback callback) {
        this.callback = callback;
    }

    /**
     * @param medianRange the frame's 64 zone distances in mm, zero where the zone had no valid
     *                    target; JJSDK reuses its buffer, so this is only read, never retained.
     * @param nowMs       monotonic clock reading, injected so the state machine can be tested.
     */
    synchronized void onFrame(float[] medianRange, long nowMs) {
        if (medianRange == null || medianRange.length < ZONES) return;

        int active = 0;
        float rowSum = 0f, colSum = 0f, rangeSum = 0f;
        for (int i = 0; i < ZONES; i++) {
            float range = medianRange[i];
            if (range < MIN_RANGE_MM || range > MAX_RANGE_MM) continue;
            active++;
            rowSum += i / GRID;
            colSum += i % GRID;
            rangeSum += range;
        }

        if (active < MIN_ACTIVE_ZONES) {
            // The hand has to be absent for a moment before a gesture is called complete;
            // a single dropped frame mid-swipe must not chop the swipe in two.
            if (tracking && nowMs - lastSeenMs >= RELEASE_MS) finish(nowMs);
            return;
        }

        float row = rowSum / active;
        float col = colSum / active;
        float range = rangeSum / active;
        lastSeenMs = nowMs;

        if (!tracking) {
            tracking = true;
            startRow = lastRow = row;
            startCol = lastCol = col;
            startRange = lastRange = range;
            startMs = nowMs;
            emit(TofGestureEvent.PRESENCE, TofGestureEvent.ACTION_RECEIVED, nowMs);
            return;
        }
        lastRow = row;
        lastCol = col;
        lastRange = range;
    }

    /** Classifies the completed movement and reports it, then returns to idle. */
    private void finish(long nowMs) {
        long duration = lastSeenMs - startMs;
        int gesture = classify(duration);
        tracking = false;
        if (gesture != -1) emit(gesture, TofGestureEvent.ACTION_RECEIVED, nowMs);
        emit(TofGestureEvent.PRESENCE, TofGestureEvent.ACTION_CLEARED, nowMs);
    }

    /** @return a {@link TofGestureEvent} gesture constant, or -1 when nothing qualifies. */
    private int classify(long duration) {
        float dRow = lastRow - startRow;
        float dCol = lastCol - startCol;
        float dRange = lastRange - startRange;

        if (duration <= MAX_SWIPE_MS) {
            // Score each axis against its own threshold so they can be compared directly, and
            // let the largest win; a diagonal wave should still resolve to one gesture.
            float horizontal = Math.abs(dCol) / MIN_ZONE_TRAVEL;
            float vertical = Math.abs(dRow) / MIN_ZONE_TRAVEL;
            float depth = Math.abs(dRange) / MIN_RANGE_TRAVEL_MM;
            if (horizontal >= 1f && horizontal >= vertical && horizontal >= depth) {
                boolean right = dCol > 0 == COLUMN_INCREASES_RIGHT;
                return right ? TofGestureEvent.GESTURE_RIGHT : TofGestureEvent.GESTURE_LEFT;
            }
            if (vertical >= 1f && vertical >= depth) {
                boolean down = dRow > 0 == ROW_INCREASES_DOWN;
                return down ? TofGestureEvent.GESTURE_DOWN : TofGestureEvent.GESTURE_UP;
            }
            if (depth >= 1f) {
                // Closing distance is the hand coming towards the sensor.
                return dRange < 0 ? TofGestureEvent.GESTURE_PUSH : TofGestureEvent.GESTURE_PULL;
            }
        }
        // Stayed within every threshold: a deliberate hold, or too slow to be a swipe.
        return duration >= HALT_MIN_MS ? TofGestureEvent.GESTURE_HALT : -1;
    }

    private void emit(int gesture, int action, long nowMs) {
        callback.onRecognised(gesture, action, nowMs * 1_000_000L);
    }

    synchronized void reset() {
        tracking = false;
        startMs = 0;
        lastSeenMs = 0;
    }

    /** Exposed for the diagnostics panel: whether a hand is in front of the sensor right now. */
    synchronized boolean isHandPresent() {
        return tracking;
    }
}
