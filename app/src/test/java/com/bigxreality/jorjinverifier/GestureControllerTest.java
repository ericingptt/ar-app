package com.bigxreality.jorjinverifier;

import static org.junit.Assert.assertEquals;

import com.jorjin.jjsdk.tof.TofGestureEvent;
import com.jorjin.jjsdk.tof.TofGestureEvents;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

public class GestureControllerTest {
    private final List<String> delivered = new ArrayList<>();
    private GestureController controller;

    @Before public void setUp() {
        delivered.clear();
        controller = new GestureController((label, gesture, count) ->
                delivered.add(label + "#" + count));
    }

    private void send(int action, int gesture, long nowMs) {
        controller.onRawEvent(TofGestureEvents.create(nowMs * 1_000_000L, action, gesture), nowMs);
    }

    @Test public void deliversReceivedGestures() {
        send(TofGestureEvent.ACTION_RECEIVED, TofGestureEvent.GESTURE_PUSH, 0);
        assertEquals(1, delivered.size());
        assertEquals("推進 PUSH#1", delivered.get(0));
        assertEquals(1, controller.gestureCount());
    }

    @Test public void ignoresClearedActionButStillCountsItAsRaw() {
        send(TofGestureEvent.ACTION_CLEARED, TofGestureEvent.GESTURE_PUSH, 0);
        assertEquals(0, delivered.size());
        assertEquals(0, controller.gestureCount());
        assertEquals(1, controller.rawEventCount());
    }

    @Test public void debouncesOnlyTheSameGestureWithinTheWindow() {
        send(TofGestureEvent.ACTION_RECEIVED, TofGestureEvent.GESTURE_PUSH, 0);
        send(TofGestureEvent.ACTION_RECEIVED, TofGestureEvent.GESTURE_PUSH, 100);
        assertEquals(1, delivered.size());
        send(TofGestureEvent.ACTION_RECEIVED, TofGestureEvent.GESTURE_PUSH,
                GestureController.DEBOUNCE_MS);
        assertEquals(2, delivered.size());
    }

    @Test public void aDifferentGestureIsNeverDebouncedAway() {
        send(TofGestureEvent.ACTION_RECEIVED, TofGestureEvent.GESTURE_PUSH, 0);
        send(TofGestureEvent.ACTION_RECEIVED, TofGestureEvent.GESTURE_LEFT, 10);
        send(TofGestureEvent.ACTION_RECEIVED, TofGestureEvent.PRESENCE, 20);
        assertEquals(3, delivered.size());
        assertEquals("接近 PRESENCE#3", delivered.get(2));
    }

    /** A cleared event between two identical gestures must not hide the second one. */
    @Test public void clearedEventDoesNotBlockAFollowUpGesture() {
        send(TofGestureEvent.ACTION_RECEIVED, TofGestureEvent.GESTURE_UP, 0);
        send(TofGestureEvent.ACTION_CLEARED, TofGestureEvent.GESTURE_UP, 50);
        send(TofGestureEvent.ACTION_RECEIVED, TofGestureEvent.GESTURE_UP, 100);
        assertEquals(2, delivered.size());
    }
}
