package com.jorjin.gesture.services.tof;

/**
 * Binding for libcalculate_gesture.so, the gesture engine shipped with Jorjin's own
 * J-Reality-Gesture app.
 *
 * The package and class name are fixed by JNI: the library exports exactly
 * Java_com_jorjin_gesture_services_tof_ParseRunnable_calculateGesture, so the binding has to
 * live at com.jorjin.gesture.services.tof.ParseRunnable for the runtime to find it. That is why
 * this one file sits outside the app's own package.
 *
 * Both arrays are frameCount * 64 entries: the module reports an 8x8 grid of zones, flattened
 * frame-major. Decompiling the vendor app's gesture thread shows it building the first array
 * from each zone's PEAK_RATE_PER_SPAD field and the second from the same grid's distance field,
 * which are exactly the peakSignalRate and medianRange arrays JJSDK already hands us.
 */
public final class ParseRunnable {
    static {
        System.loadLibrary("calculate_gesture");
    }

    /** Returns a gesture name such as UP, DOWN, LEFT, RIGHT, or null/empty when undecided. */
    public native String calculateGesture(int[] peakRatePerSpad, int[] distance);
}
