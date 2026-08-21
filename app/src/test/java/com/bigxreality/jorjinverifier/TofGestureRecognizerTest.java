package com.bigxreality.jorjinverifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jorjin.jjsdk.tof.TofGestureEvent;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

/**
 * Drives the depth-frame recogniser with synthetic 8x8 frames. This is the half of the gesture
 * pipeline that can be verified without hardware: the thresholds and the state machine.
 * Which physical direction a rising column index corresponds to still needs a real unit.
 */
public class TofGestureRecognizerTest {
    private final List<Integer> received = new ArrayList<>();
    private TofGestureRecognizer recognizer;
    private long clock;

    @Before public void setUp() {
        received.clear();
        clock = 1000L;
        recognizer = new TofGestureRecognizer((gesture, action, eventTime) -> {
            if (action == TofGestureEvent.ACTION_RECEIVED) received.add(gesture);
        });
    }

    /** One frame with a 3x3 blob of hand centred on (row, col) at the given distance. */
    private static float[] hand(float row, float col, float rangeMm) {
        float[] frame = new float[TofGestureRecognizer.ZONES];
        for (int r = Math.round(row) - 1; r <= Math.round(row) + 1; r++) {
            for (int c = Math.round(col) - 1; c <= Math.round(col) + 1; c++) {
                if (r < 0 || r >= TofGestureRecognizer.GRID) continue;
                if (c < 0 || c >= TofGestureRecognizer.GRID) continue;
                frame[r * TofGestureRecognizer.GRID + c] = rangeMm;
            }
        }
        return frame;
    }

    private void frame(float[] data, long stepMs) {
        clock += stepMs;
        recognizer.onFrame(data, clock);
    }

    /** Sweeps the hand from one position to another, then lets it leave the field of view. */
    private void swipe(float fromRow, float fromCol, float toRow, float toCol,
                       float fromMm, float toMm, int steps, long stepMs) {
        for (int i = 0; i <= steps; i++) {
            float t = (float) i / steps;
            frame(hand(fromRow + (toRow - fromRow) * t,
                    fromCol + (toCol - fromCol) * t,
                    fromMm + (toMm - fromMm) * t), stepMs);
        }
        release();
    }

    private void release() {
        frame(new float[TofGestureRecognizer.ZONES], TofGestureRecognizer.RELEASE_MS + 1);
    }

    @Test public void reportsPresenceAsSoonAsAHandAppears() {
        frame(hand(3, 3, 200f), 16);
        assertEquals(1, received.size());
        assertEquals(TofGestureEvent.PRESENCE, (int) received.get(0));
        assertTrue(recognizer.isHandPresent());
    }

    @Test public void recognisesHorizontalSwipes() {
        swipe(3, 1, 3, 6, 200f, 200f, 8, 16);
        assertEquals(TofGestureEvent.GESTURE_RIGHT, lastGesture());

        setUp();
        swipe(3, 6, 3, 1, 200f, 200f, 8, 16);
        assertEquals(TofGestureEvent.GESTURE_LEFT, lastGesture());
    }

    @Test public void recognisesVerticalSwipes() {
        swipe(1, 3, 6, 3, 200f, 200f, 8, 16);
        assertEquals(TofGestureEvent.GESTURE_DOWN, lastGesture());

        setUp();
        swipe(6, 3, 1, 3, 200f, 200f, 8, 16);
        assertEquals(TofGestureEvent.GESTURE_UP, lastGesture());
    }

    /** Closing distance is the hand coming towards the sensor. */
    @Test public void recognisesPushAndPull() {
        swipe(3, 3, 3, 3, 300f, 80f, 8, 16);
        assertEquals(TofGestureEvent.GESTURE_PUSH, lastGesture());

        setUp();
        swipe(3, 3, 3, 3, 80f, 300f, 8, 16);
        assertEquals(TofGestureEvent.GESTURE_PULL, lastGesture());
    }

    /** Holding still is how a button is pressed; it must fire while the hand is still there. */
    @Test public void holdingStillSelectsWithoutWaitingForTheHandToLeave() {
        long steps = TofGestureRecognizer.DWELL_MS / 16 + 4;
        for (int i = 0; i < steps; i++) frame(hand(3, 3, 200f), 16);
        assertTrue("停留未觸發選取", received.contains(TofGestureRecognizer.GESTURE_SELECT));
    }

    /** One press per hold: a hand parked in front of the sensor must not repeat-fire. */
    @Test public void aSingleHoldSelectsOnlyOnce() {
        for (int i = 0; i < 200; i++) frame(hand(3, 3, 200f), 16);
        int selects = 0;
        for (int gesture : received) if (gesture == TofGestureRecognizer.GESTURE_SELECT) selects++;
        assertEquals(1, selects);
    }

    /** A hold that already pressed must not also report HALT on the way out. */
    @Test public void aHoldThatSelectedDoesNotAlsoReportHalt() {
        long steps = TofGestureRecognizer.DWELL_MS / 16 + 4;
        for (int i = 0; i < steps; i++) frame(hand(3, 3, 200f), 16);
        release();
        assertEquals(TofGestureRecognizer.GESTURE_SELECT, lastGesture());
    }

    /** Moving the hand restarts the countdown, so a swipe never presses anything. */
    @Test public void movingResetsTheHoldCountdown() {
        for (int i = 0; i < 20; i++) frame(hand(3, 3, 200f), 16);
        for (int i = 0; i < 20; i++) frame(hand(3, 6, 200f), 16);
        for (int i = 0; i < 20; i++) frame(hand(3, 3, 200f), 16);
        for (int gesture : received) {
            assertTrue("移動中不應觸發選取",
                    gesture != TofGestureRecognizer.GESTURE_SELECT);
        }
    }

    /** A brief touch that goes nowhere is presence only, never an invented direction. */
    @Test public void aBriefStationaryTouchProducesNoDirection() {
        frame(hand(3, 3, 200f), 16);
        frame(hand(3, 3, 200f), 16);
        release();
        assertEquals(1, received.size());
        assertEquals(TofGestureEvent.PRESENCE, (int) received.get(0));
    }

    /** A dropped frame mid-swipe must not cut the swipe into two shorter ones. */
    @Test public void survivesASingleDroppedFrame() {
        frame(hand(3, 1, 200f), 16);
        frame(new float[TofGestureRecognizer.ZONES], 16);
        frame(hand(3, 4, 200f), 16);
        frame(hand(3, 6, 200f), 16);
        release();
        assertEquals(TofGestureEvent.GESTURE_RIGHT, lastGesture());
    }

    /** Out-of-range returns and empty zones are not a hand. */
    @Test public void ignoresNoiseAndOutOfRangeZones() {
        float[] noise = new float[TofGestureRecognizer.ZONES];
        noise[0] = 5f;                                    // closer than the cover glass
        noise[9] = TofGestureRecognizer.MAX_RANGE_MM + 50; // beyond gesture reach
        frame(noise, 16);
        assertTrue(received.isEmpty());
    }

    private int lastGesture() {
        assertTrue("沒有收到任何手勢", !received.isEmpty());
        return received.get(received.size() - 1);
    }
}
