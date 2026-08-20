package com.bigxreality.jorjinverifier;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Locks in how JJSDK decides whether a ToF module may emit gestures, and how that differs from
 * the module's real version. Measured against a J-Reality unit reporting "1.1.2.jg7hc": the SDK
 * arms its gesture path (the build suffix inflates its folded value past the threshold) while
 * the firmware is genuinely older than v1.2.2 and never sets a gesture bit in any frame.
 */
public class FirmwareGateTest {
    @Test public void sdkGateArmsExactlyAt122() {
        assertFalse(JorjinHardwareManager.firmwareSupportsGestures("1.1.2"));
        assertTrue(JorjinHardwareManager.firmwareSupportsGestures("1.2.2"));
        assertTrue(JorjinHardwareManager.firmwareSupportsGestures("1.2.3"));
    }

    /** A non-numeric build suffix pushes the SDK's folded value past the threshold. */
    @Test public void sdkGateIsFooledByABuildSuffix() {
        assertTrue(JorjinHardwareManager.firmwareSupportsGestures("1.1.2.jg7hc"));
    }

    /** The honest check ignores the suffix, so the panel can report the real answer. */
    @Test public void numericCheckIgnoresTheBuildSuffix() {
        assertFalse(JorjinHardwareManager.firmwareNumericallyAtLeast122("1.1.2.jg7hc"));
        assertFalse(JorjinHardwareManager.firmwareNumericallyAtLeast122("1.1.9"));
        assertTrue(JorjinHardwareManager.firmwareNumericallyAtLeast122("1.2.2.jg7hc"));
        assertTrue(JorjinHardwareManager.firmwareNumericallyAtLeast122("1.3.0"));
        assertTrue(JorjinHardwareManager.firmwareNumericallyAtLeast122("2.0.0"));
    }

    @Test public void handlesMissingOrMalformedVersions() {
        assertFalse(JorjinHardwareManager.firmwareNumericallyAtLeast122(null));
        assertFalse(JorjinHardwareManager.firmwareNumericallyAtLeast122(""));
        assertFalse(JorjinHardwareManager.firmwareNumericallyAtLeast122("unknown"));
        assertFalse(JorjinHardwareManager.firmwareSupportsGestures(null));
    }
}
