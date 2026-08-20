package com.jorjin.jjsdk.tof;

/**
 * Test-only factory. {@link TofGestureEvent}'s constructor is package private inside the vendor
 * AAR, so this shim lives in the same package to let unit tests build real events instead of
 * mocking the SDK type.
 */
public final class TofGestureEvents {
    private TofGestureEvents() { }

    public static TofGestureEvent create(long eventTime, int action, int gesture) {
        return new TofGestureEvent(eventTime, action, gesture);
    }
}
