package com.bigxreality.jorjinverifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class JorjinUsbDevicesTest {
    /** The exact keys observed in JJSDK 1.3.3's c.k table. */
    @Test public void reproducesTheSdkLookupKeys() {
        assertEquals(4194435, JorjinUsbDevices.key(0x0483, 0x5740));
        assertEquals(2293774, JorjinUsbDevices.key(0x350E, 0x3723));
    }

    @Test public void recognisesBothToFVariantsTheSdkCanDrive() {
        assertTrue(JorjinUsbDevices.isSdkSupportedTof(0x0483, 0x5740));
        assertTrue(JorjinUsbDevices.isSdkSupportedTof(0x350E, 0x3723));
    }

    /** Listed in the vendor Gesture app's device filter but absent from JJSDK 1.3.3's table. */
    @Test public void flagsTheToFVariantTheSdkCannotDrive() {
        assertFalse(JorjinUsbDevices.isSdkSupportedTof(0x350E, 0x3501));
        assertTrue(JorjinUsbDevices.isTofCandidate(0x350E, 0x3501));
    }

    @Test public void detectsFirmwareUpdateMode() {
        assertTrue(JorjinUsbDevices.isTofInDfuMode(0x0483, 0xDF11));
        assertFalse(JorjinUsbDevices.isTofCandidate(0x0483, 0xDF11));
    }

    /** The camera is a different USB device; a camera id must never be taken for the ToF. */
    @Test public void keepsCameraAndToFApart() {
        assertTrue(JorjinUsbDevices.isSdkSupportedCamera(0x00BA, 0x005A));
        assertFalse(JorjinUsbDevices.isTofCandidate(0x00BA, 0x005A));
        assertFalse(JorjinUsbDevices.isSdkSupportedCamera(0x0483, 0x5740));
    }

    @Test public void formatsIdsForLogcat() {
        assertEquals("VID=0x0483 PID=0x5740", JorjinUsbDevices.formatIds(0x0483, 0x5740));
    }
}
