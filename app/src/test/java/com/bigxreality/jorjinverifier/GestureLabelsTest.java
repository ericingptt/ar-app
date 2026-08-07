package com.bigxreality.jorjinverifier;

import static org.junit.Assert.assertEquals;

import com.jorjin.jjsdk.tof.TofGestureEvent;
import org.junit.Test;

public class GestureLabelsTest {
    @Test public void mapsEverySupportedGesture() {
        assertEquals("向上 UP", GestureLabels.from(TofGestureEvent.GESTURE_UP));
        assertEquals("向下 DOWN", GestureLabels.from(TofGestureEvent.GESTURE_DOWN));
        assertEquals("向左 LEFT", GestureLabels.from(TofGestureEvent.GESTURE_LEFT));
        assertEquals("向右 RIGHT", GestureLabels.from(TofGestureEvent.GESTURE_RIGHT));
        assertEquals("拉回 PULL", GestureLabels.from(TofGestureEvent.GESTURE_PULL));
        assertEquals("推進 PUSH", GestureLabels.from(TofGestureEvent.GESTURE_PUSH));
        assertEquals("停止 HALT", GestureLabels.from(TofGestureEvent.GESTURE_HALT));
        assertEquals("接近 PRESENCE", GestureLabels.from(TofGestureEvent.PRESENCE));
    }

    @Test public void describesUnknownGesture() {
        assertEquals("未知手勢 999", GestureLabels.from(999));
    }
}
