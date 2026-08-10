package com.bigxreality.jorjinverifier;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

/**
 * Shared USB device resolution. JJSDK reaches the glasses over libusb/UVC through usbfs, so an
 * Android USB device grant is required before either the camera or the ToF sensor produces
 * anything - the CAMERA runtime permission does not cover that path.
 */
final class GlassesUsb {
    /** USB video class (UVC); the glasses' RGB camera enumerates under it. */
    private static final int UVC_INTERFACE_CLASS = 14;

    private GlassesUsb() { }

    /**
     * Prefers a device exposing a UVC video interface; the glasses enumerate as one. Falls back
     * to the first attached device so a single wrong guess cannot silently block verification.
     */
    static UsbDevice findDevice(UsbManager manager) {
        if (manager == null) return null;
        UsbDevice fallback = null;
        for (UsbDevice device : manager.getDeviceList().values()) {
            if (hasVideoInterface(device)) return device;
            if (fallback == null) fallback = device;
        }
        return fallback;
    }

    private static boolean hasVideoInterface(UsbDevice device) {
        for (int index = 0; index < device.getInterfaceCount(); index++) {
            if (device.getInterface(index).getInterfaceClass() == UVC_INTERFACE_CLASS) return true;
        }
        return false;
    }
}
