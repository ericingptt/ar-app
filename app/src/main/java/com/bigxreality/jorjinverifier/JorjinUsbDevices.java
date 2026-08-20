package com.bigxreality.jorjinverifier;

/**
 * USB identification rules for the J-Reality glasses, mirroring what JJSDK v1.3.3 itself does.
 *
 * <p>The SDK's internal lookup tables ({@code c.a} for the RGB camera, {@code c.j} for the
 * display/sensor MCU and {@code c.k} for the ToF module) are keyed by
 * {@code vendorId & 0xFF | (productId & 0xFF) << 16} - only the low byte of each id is
 * significant.  The concrete pairs below were cross-checked against the vendor Gesture app's
 * {@code res/xml/device_filter.xml}, which lists the very same ToF descriptors.
 *
 * <p>The important consequence, and the reason gestures never arrived: the ToF module is a
 * <em>separate USB device</em> from the RGB camera - a CDC-ACM (virtual COM port) node, not the
 * UVC video node.  Holding a USB grant for the camera therefore says nothing about the ToF.
 */
final class JorjinUsbDevices {
    /** USB video class; the RGB camera enumerates one of these interfaces. */
    static final int CLASS_VIDEO = 14;
    /** USB communications class; the ToF module exposes a CDC-ACM control interface. */
    static final int CLASS_CDC_CONTROL = 2;
    /** USB CDC data class; the ToF module's bulk endpoints live on this interface. */
    static final int CLASS_CDC_DATA = 10;

    /** JJSDK {@code c.k}: "Jorjin ToF" - STMicroelectronics virtual COM port. */
    private static final int TOF_KEY_LEGACY = key(0x0483, 0x5740);
    /** JJSDK {@code c.k}: "Jorjin ToF with new VID". */
    private static final int TOF_KEY_NEW_VID = key(0x350E, 0x3723);
    /**
     * Present in the vendor Gesture app's device filter but missing from JJSDK 1.3.3's table.
     * Matching it lets us at least report the module instead of silently ignoring it; the SDK
     * still will not drive it, which the diagnostics panel makes visible.
     */
    private static final int TOF_KEY_UNSUPPORTED_BY_SDK = key(0x350E, 0x3501);
    /** DFU (firmware update) mode of the same ToF MCU - not usable for gestures. */
    private static final int TOF_KEY_DFU = key(0x0483, 0xDF11);

    /** JJSDK {@code c.a}: the "Jorjin" RGB camera bridge. */
    private static final int CAMERA_KEY_JORJIN = key(0x00BA, 0x005A);
    /** JJSDK {@code c.a}: "EPSON BT-35E". */
    private static final int CAMERA_KEY_EPSON = key(0x000F, 0x00AC);

    private JorjinUsbDevices() { }

    /** The exact key JJSDK builds; only the low byte of each id participates. */
    static int key(int vendorId, int productId) {
        return (vendorId & 0xFF) | ((productId & 0xFF) << 16);
    }

    /** True when JJSDK's own ToF table would recognise this device and drive it. */
    static boolean isSdkSupportedTof(int vendorId, int productId) {
        int key = key(vendorId, productId);
        return key == TOF_KEY_LEGACY || key == TOF_KEY_NEW_VID;
    }

    /** True for anything that is a ToF module, including variants JJSDK 1.3.3 cannot drive. */
    static boolean isTofCandidate(int vendorId, int productId) {
        return isSdkSupportedTof(vendorId, productId)
                || key(vendorId, productId) == TOF_KEY_UNSUPPORTED_BY_SDK;
    }

    /** True while the ToF MCU sits in firmware-update mode; it emits no gestures there. */
    static boolean isTofInDfuMode(int vendorId, int productId) {
        return key(vendorId, productId) == TOF_KEY_DFU;
    }

    /** True when JJSDK's camera table would recognise this device. */
    static boolean isSdkSupportedCamera(int vendorId, int productId) {
        int key = key(vendorId, productId);
        return key == CAMERA_KEY_JORJIN || key == CAMERA_KEY_EPSON;
    }

    /** Human readable four-hex-digit ids for the logcat dump. */
    static String formatIds(int vendorId, int productId) {
        return String.format("VID=0x%04X PID=0x%04X", vendorId, productId);
    }
}
