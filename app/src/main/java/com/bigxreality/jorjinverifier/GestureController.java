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

    /** Where a gesture came from; both sources share this controller's debounce and log. */
    enum Source {
        /** The module's own gesture engine, via JJSDK's TofGestureEvent. Needs firmware 1.2.2+. */
        FIRMWARE("韌體"),
        /** Computed here from the ToF depth frames. Works on any firmware. */
        DEPTH_FRAME("深度幀");

        final String label;

        Source(String label) {
            this.label = label;
        }
    }

    /** What the UI should do with one raw event. */
    interface Callback {
        /** Called on the SDK thread for accepted {@code ACTION_RECEIVED} events only. */
        void onGesture(String label, int gesture, Source source, long count);
    }

    private final Callback callback;
    private long totalRawEvents;
    private long acceptedEvents;
    private int lastGesture = Integer.MIN_VALUE;
    private int lastAction = Integer.MIN_VALUE;
    private Source lastSource;
    private long lastAcceptedAt;

    GestureController(Callback callback) {
        this.callback = callback;
    }

    /** @param nowMs a monotonic clock reading, injected so the debounce can be unit tested. */
    synchronized void onRawEvent(TofGestureEvent event, long nowMs) {
        if (event == null) return;
        onRawEvent(event.getAction(), event.getGesture(), event.getEventTime(), nowMs,
                Source.FIRMWARE);
    }

    /**
     * Primitive form shared by both sources. The depth-frame recogniser cannot build a
     * {@link TofGestureEvent} - its constructor is package private inside the vendor AAR - and
     * routing both through one method keeps a single debounce, count and log format.
     */
    synchronized void onRawEvent(int action, int gesture, long eventTimeNanos, long nowMs,
                                 Source source) {
        totalRawEvents++;
        String label = GestureLabels.from(gesture);
        Log.i(LOG_TAG, "JorjinGesture:"
                + " action=" + action + (action == TofGestureEvent.ACTION_RECEIVED
                        ? " (ACTION_RECEIVED)" : " (ACTION_CLEARED)")
                + " gesture=" + gesture
                + " label=" + GestureLabels.code(gesture)
                + " source=" + source.name()
                + " timestamp=" + eventTimeNanos
                + " raw=" + totalRawEvents);
        if (action != TofGestureEvent.ACTION_RECEIVED) {
            lastAction = action;
            lastGesture = gesture;
            lastSource = source;
            return;
        }
        if (gesture == lastGesture && action == lastAction && source == lastSource
                && nowMs - lastAcceptedAt < DEBOUNCE_MS) {
            Log.i(LOG_TAG, "JorjinGesture: 因 " + DEBOUNCE_MS + " ms 防重複而略過 " + label);
            return;
        }
        lastGesture = gesture;
        lastAction = action;
        lastSource = source;
        lastAcceptedAt = nowMs;
        acceptedEvents++;
        callback.onGesture(label, gesture, source, acceptedEvents);
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
        lastSource = null;
        lastAcceptedAt = 0;
    }
}
