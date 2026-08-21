package com.bigxreality.jorjinverifier;

import com.jorjin.jjsdk.tof.TofGestureEvent;

/** Pure gesture-to-display mapping, separated so it can be unit tested. */
final class GestureLabels {
    private GestureLabels() { }

    static String from(int gesture) {
        switch (gesture) {
            case TofGestureEvent.GESTURE_UP: return "向上 UP";
            case TofGestureEvent.GESTURE_DOWN: return "向下 DOWN";
            case TofGestureEvent.GESTURE_LEFT: return "向左 LEFT";
            case TofGestureEvent.GESTURE_RIGHT: return "向右 RIGHT";
            case TofGestureEvent.GESTURE_PULL: return "拉回 PULL";
            case TofGestureEvent.GESTURE_PUSH: return "推進 PUSH";
            case TofGestureEvent.GESTURE_HALT: return "停止 HALT";
            case TofGestureEvent.PRESENCE: return "接近 PRESENCE";
            case TofGestureRecognizer.GESTURE_SELECT: return "選取 SELECT";
            default: return "未知手勢 " + gesture;
        }
    }

    /** Bare uppercase code used by the JorjinGesture logcat lines and by acceptance testing. */
    static String code(int gesture) {
        switch (gesture) {
            case TofGestureEvent.GESTURE_UP: return "UP";
            case TofGestureEvent.GESTURE_DOWN: return "DOWN";
            case TofGestureEvent.GESTURE_LEFT: return "LEFT";
            case TofGestureEvent.GESTURE_RIGHT: return "RIGHT";
            case TofGestureEvent.GESTURE_PULL: return "PULL";
            case TofGestureEvent.GESTURE_PUSH: return "PUSH";
            case TofGestureEvent.GESTURE_HALT: return "HALT";
            case TofGestureEvent.PRESENCE: return "PRESENCE";
            case TofGestureRecognizer.GESTURE_SELECT: return "SELECT";
            default: return "UNKNOWN_" + gesture;
        }
    }
}
