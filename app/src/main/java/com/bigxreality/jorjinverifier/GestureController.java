package com.bigxreality.jorjinverifier;

import android.util.Log;

import com.jorjin.jjsdk.tof.TofGestureEvent;

/**
 * Turns raw {@link TofGestureEvent}s into a debounced, display-ready gesture.
 *
 * <p>JJSDK already emits one event per edge of each gesture bit, so a hold produces exactly one
 * {@code ACTION_RECEIVED} followed by one {@code ACTION_CLEARED}.  The debounce here therefore
 * only guards against a bit that chatters, and it is deliberately keyed on the
 * (action, gesture) pair: keying on the gesture alone would swallow the {@code ACTION_CLEARED}
 * that immediately follows a short flick and, worse, hide a genuine repeat.
 *
 * <p>Every raw event is logged before the debounce runs, so "no gesture at all" and "gesture
 * received but filtered" can never be confused during on-device verification.
 */
final class GestureController {
    static final long DEBOUNCE_MS = 300L;
    private static final String LOG_TAG = "JorjinGesture";

    /** What the UI should do with one raw event. */
    interface Callback {
        /** Called on the SDK thread for accepted {@code ACTION_RECEIVED} events only. */
        void onGesture(String label, int gesture, long count);
    }

    private final Callback callback;
    private long totalRawEvents;
    private long acceptedEvents;
    private int lastGesture = Integer.MIN_VALUE;
    private int lastAction = Integer.MIN_VALUE;
    private long lastAcceptedAt;

    GestureController(Callback callback) {
        this.callback = callback;
    }

    /** @param nowMs a monotonic clock reading, injected so the debounce can be unit tested. */
    synchronized void onRawEvent(TofGestureEvent event, long nowMs) {
        if (event == null) return;
        totalRawEvents++;
        int gesture = event.getGesture();
        int action = event.getAction();
        String label = GestureLabels.from(gesture);
        Log.i(LOG_TAG, "JorjinGesture:"
                + " action=" + action + (action == TofGestureEvent.ACTION_RECEIVED
                        ? " (ACTION_RECEIVED)" : " (ACTION_CLEARED)")
                + " gesture=" + gesture
                + " label=" + GestureLabels.code(gesture)
                + " timestamp=" + event.getEventTime()
                + " raw=" + totalRawEvents);
        if (action != TofGestureEvent.ACTION_RECEIVED) {
            lastAction = action;
            lastGesture = gesture;
            return;
        }
        if (gesture == lastGesture && action == lastAction
                && nowMs - lastAcceptedAt < DEBOUNCE_MS) {
            Log.i(LOG_TAG, "JorjinGesture: 因 " + DEBOUNCE_MS + " ms 防重複而略過 " + label);
            return;
        }
        lastGesture = gesture;
        lastAction = action;
        lastAcceptedAt = nowMs;
        acceptedEvents++;
        callback.onGesture(label, gesture, acceptedEvents);
    }

    /** Total events handed over by the SDK, accepted or not - the "is the pipe alive" number. */
    synchronized long rawEventCount() {
        return totalRawEvents;
    }

    synchronized long gestureCount() {
        return acceptedEvents;
    }

    synchronized void reset() {
        totalRawEvents = 0;
        acceptedEvents = 0;
        lastGesture = Integer.MIN_VALUE;
        lastAction = Integer.MIN_VALUE;
        lastAcceptedAt = 0;
    }
}
