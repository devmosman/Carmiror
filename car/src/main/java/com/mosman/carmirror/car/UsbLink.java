package com.mosman.carmirror.car;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.SystemClock;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Cable link to the phone using the Android Open Accessory (AOA) protocol, the same
 * mechanism Android Auto uses. The car is the USB host:
 *
 *   1. switchToAccessory() asks a normal phone to reconnect in "accessory mode".
 *   2. open() claims the accessory interface and exposes it as a byte stream.
 *   3. Touches and Back/Home/Recents are sent as USB HID input (AOA version 2), so the
 *      phone treats the car screen like an attached touch screen. That needs no
 *      accessibility service on the phone.
 */
final class UsbLink implements Closeable {
    // Must match phone/src/main/res/xml/accessory_filter.xml.
    private static final String MANUFACTURER = "Mosman";
    private static final String MODEL = "CarMirror";
    private static final String DESCRIPTION = "CarMirror car screen";
    private static final String VERSION = "1";
    private static final String URI = "https://github.com/devmosman/Carmiror";
    private static final String SERIAL = "0001";

    private static final int VID_GOOGLE = 0x18D1;
    private static final int PID_ACCESSORY_FIRST = 0x2D00;
    private static final int PID_ACCESSORY_LAST = 0x2D05;

    private static final int REQ_GET_PROTOCOL = 51;
    private static final int REQ_SEND_STRING = 52;
    private static final int REQ_START = 53;
    private static final int REQ_REGISTER_HID = 54;
    private static final int REQ_UNREGISTER_HID = 55;
    private static final int REQ_SET_HID_REPORT_DESC = 56;
    private static final int REQ_SEND_HID_EVENT = 57;

    private static final int DIR_IN = 0xC0;   // vendor request, device to host
    private static final int DIR_OUT = 0x40;  // vendor request, host to device
    private static final int CHUNK = 16384;   // accessory transfers are at most this long

    private static final int HID_TOUCH = 1;
    private static final int HID_KEYS = 2;

    static final int KEY_USAGE_BACK = 0x0224;     // Consumer: AC Back
    static final int KEY_USAGE_HOME = 0x0223;     // Consumer: AC Home
    static final int KEY_USAGE_RECENTS = 0x029F;  // Consumer: AC Desktop Show All Windows

    /** Single-finger touch screen: tip switch, in range, then absolute X and Y (0..32767). */
    private static final byte[] TOUCH_DESCRIPTOR = bytes(
            0x05, 0x0D,        // Usage Page (Digitizer)
            0x09, 0x04,        // Usage (Touch Screen)
            0xA1, 0x01,        // Collection (Application)
            0x09, 0x22,        //   Usage (Finger)
            0xA1, 0x02,        //   Collection (Logical)
            0x09, 0x42,        //     Usage (Tip Switch)
            0x15, 0x00,        //     Logical Minimum (0)
            0x25, 0x01,        //     Logical Maximum (1)
            0x75, 0x01,        //     Report Size (1)
            0x95, 0x01,        //     Report Count (1)
            0x81, 0x02,        //     Input (Data, Variable, Absolute)
            0x09, 0x32,        //     Usage (In Range)
            0x81, 0x02,        //     Input (Data, Variable, Absolute)
            0x95, 0x06,        //     Report Count (6)
            0x81, 0x03,        //     Input (Constant) padding
            0x05, 0x01,        //     Usage Page (Generic Desktop)
            0x26, 0xFF, 0x7F,  //     Logical Maximum (32767)
            0x75, 0x10,        //     Report Size (16)
            0x95, 0x01,        //     Report Count (1)
            0x09, 0x30,        //     Usage (X)
            0x81, 0x02,        //     Input (Data, Variable, Absolute)
            0x09, 0x31,        //     Usage (Y)
            0x81, 0x02,        //     Input (Data, Variable, Absolute)
            0xC0,              //   End Collection
            0xC0);             // End Collection

    /** Consumer control: one 16-bit usage code per report, 0 means released. */
    private static final byte[] KEYS_DESCRIPTOR = bytes(
            0x05, 0x0C,        // Usage Page (Consumer)
            0x09, 0x01,        // Usage (Consumer Control)
            0xA1, 0x01,        // Collection (Application)
            0x15, 0x00,        //   Logical Minimum (0)
            0x26, 0xFF, 0x03,  //   Logical Maximum (1023)
            0x19, 0x00,        //   Usage Minimum (0)
            0x2A, 0xFF, 0x03,  //   Usage Maximum (1023)
            0x75, 0x10,        //   Report Size (16)
            0x95, 0x01,        //   Report Count (1)
            0x81, 0x00,        //   Input (Data, Array)
            0xC0);             // End Collection

    private final UsbManager manager;
    private final String deviceName;
    private final UsbDeviceConnection connection;
    private final UsbInterface usbInterface;
    private final UsbEndpoint endpointIn;
    private final UsbEndpoint endpointOut;
    private final InputStream input = new In();
    private final OutputStream output = new Out();
    private volatile boolean closed;

    /** True when the phone accepted the HID touch screen, so touches can go over the cable. */
    final boolean hidReady;

    private UsbLink(UsbManager manager, UsbDevice device, UsbDeviceConnection connection,
                    UsbInterface usbInterface, UsbEndpoint in, UsbEndpoint out) {
        this.manager = manager;
        this.deviceName = device.getDeviceName();
        this.connection = connection;
        this.usbInterface = usbInterface;
        this.endpointIn = in;
        this.endpointOut = out;
        this.hidReady = registerHid();
    }

    // ---------------------------------------------------------------- finding the phone

    /** A phone that is already in accessory mode. */
    static boolean isAccessory(UsbDevice device) {
        return device.getVendorId() == VID_GOOGLE
                && device.getProductId() >= PID_ACCESSORY_FIRST
                && device.getProductId() <= PID_ACCESSORY_LAST;
    }

    /**
     * Guesses whether a USB device is a phone, so the car's own USB parts (modem, camera,
     * Bluetooth) and USB sticks are left alone. Phones show a photo-transfer (PTP/MTP) or
     * debugging interface, or come from a known phone maker.
     */
    static boolean looksLikePhone(UsbDevice device) {
        if (device.getDeviceClass() == UsbConstants.USB_CLASS_HUB) {
            return false;
        }
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            int cls = intf.getInterfaceClass();
            if (cls == UsbConstants.USB_CLASS_STILL_IMAGE) {
                return true;
            }
            if (cls == UsbConstants.USB_CLASS_VENDOR_SPEC
                    && intf.getInterfaceSubclass() == 0x42 && intf.getInterfaceProtocol() == 1) {
                return true; // adb
            }
        }
        switch (device.getVendorId()) {
            case 0x04E8: // Samsung
            case 0x18D1: // Google
            case 0x2717: // Xiaomi
            case 0x22D9: // Oppo, Realme
            case 0x2A70: // OnePlus
            case 0x2D95: // Vivo
            case 0x22B8: // Motorola
            case 0x0FCE: // Sony
            case 0x1004: // LG
            case 0x0BB4: // HTC
                return true;
            default:
                return false;
        }
    }

    /**
     * Asks a phone to drop off the bus and come back in accessory mode. Returns false if
     * the device does not speak the accessory protocol.
     */
    static boolean switchToAccessory(UsbManager manager, UsbDevice device) {
        UsbDeviceConnection c = manager.openDevice(device);
        if (c == null) {
            return false;
        }
        try {
            if (protocolVersion(c) < 1) {
                return false;
            }
            String[] strings = {MANUFACTURER, MODEL, DESCRIPTION, VERSION, URI, SERIAL};
            for (int i = 0; i < strings.length; i++) {
                byte[] data = (strings[i] + "\0").getBytes(StandardCharsets.UTF_8);
                if (c.controlTransfer(DIR_OUT, REQ_SEND_STRING, 0, i, data, data.length, 1000) < 0) {
                    return false;
                }
            }
            return c.controlTransfer(DIR_OUT, REQ_START, 0, 0, null, 0, 1000) >= 0;
        } finally {
            c.close();
        }
    }

    private static int protocolVersion(UsbDeviceConnection c) {
        byte[] version = new byte[2];
        int n = c.controlTransfer(DIR_IN, REQ_GET_PROTOCOL, 0, 0, version, 2, 1000);
        if (n < 2) {
            return -1;
        }
        return ((version[1] & 0xff) << 8) | (version[0] & 0xff);
    }

    /** Opens a phone that is in accessory mode. */
    static UsbLink open(UsbManager manager, UsbDevice device) throws IOException {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            UsbEndpoint in = null;
            UsbEndpoint out = null;
            for (int e = 0; e < intf.getEndpointCount(); e++) {
                UsbEndpoint endpoint = intf.getEndpoint(e);
                if (endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    continue;
                }
                if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) {
                    in = endpoint;
                } else {
                    out = endpoint;
                }
            }
            if (in == null || out == null) {
                continue;
            }
            UsbDeviceConnection connection = manager.openDevice(device);
            if (connection == null) {
                throw new IOException("Could not open the USB device");
            }
            if (!connection.claimInterface(intf, true)) {
                connection.close();
                throw new IOException("Could not claim the accessory interface");
            }
            return new UsbLink(manager, device, connection, intf, in, out);
        }
        throw new IOException("No accessory interface on the USB device");
    }

    InputStream input() {
        return input;
    }

    OutputStream output() {
        return output;
    }

    // ---------------------------------------------------------------- HID input

    private boolean registerHid() {
        if (protocolVersion(connection) < 2) {
            return false;
        }
        return registerHid(HID_TOUCH, TOUCH_DESCRIPTOR) && registerHid(HID_KEYS, KEYS_DESCRIPTOR);
    }

    private boolean registerHid(int id, byte[] descriptor) {
        if (connection.controlTransfer(DIR_OUT, REQ_REGISTER_HID, id, descriptor.length, null, 0, 1000) < 0) {
            return false;
        }
        return connection.controlTransfer(DIR_OUT, REQ_SET_HID_REPORT_DESC, id, 0,
                descriptor, descriptor.length, 1000) >= 0;
    }

    /** x and y are 0..1 across the phone screen in its natural (upright) orientation. */
    void touch(boolean down, float x, float y) {
        if (closed || !hidReady) {
            return;
        }
        int px = Math.round(clamp(x) * 32767);
        int py = Math.round(clamp(y) * 32767);
        byte[] report = {
                (byte) (down ? 0x03 : 0x00),
                (byte) (px & 0xff), (byte) ((px >> 8) & 0xff),
                (byte) (py & 0xff), (byte) ((py >> 8) & 0xff)};
        connection.controlTransfer(DIR_OUT, REQ_SEND_HID_EVENT, HID_TOUCH, 0, report, report.length, 200);
    }

    /** Presses and releases one consumer-control key. */
    void key(int usage) {
        if (closed || !hidReady) {
            return;
        }
        byte[] press = {(byte) (usage & 0xff), (byte) ((usage >> 8) & 0xff)};
        byte[] release = {0, 0};
        connection.controlTransfer(DIR_OUT, REQ_SEND_HID_EVENT, HID_KEYS, 0, press, press.length, 200);
        connection.controlTransfer(DIR_OUT, REQ_SEND_HID_EVENT, HID_KEYS, 0, release, release.length, 200);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (hidReady) {
                connection.controlTransfer(DIR_OUT, REQ_UNREGISTER_HID, HID_TOUCH, 0, null, 0, 200);
                connection.controlTransfer(DIR_OUT, REQ_UNREGISTER_HID, HID_KEYS, 0, null, 0, 200);
            }
            connection.releaseInterface(usbInterface);
        } catch (RuntimeException ignored) {
        }
        connection.close();
    }

    private boolean stillAttached() {
        return manager.getDeviceList().containsKey(deviceName);
    }

    private static float clamp(float v) {
        if (Float.isNaN(v)) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f, v));
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (byte) values[i];
        }
        return result;
    }

    // ---------------------------------------------------------------- byte streams

    private final class In extends InputStream {
        private final byte[] buffer = new byte[CHUNK];
        private int position;
        private int length;

        @Override
        public int read() throws IOException {
            if (position == length) {
                fill();
            }
            return buffer[position++] & 0xff;
        }

        @Override
        public int read(byte[] target, int offset, int count) throws IOException {
            if (count == 0) {
                return 0;
            }
            if (position == length) {
                fill();
            }
            int n = Math.min(count, length - position);
            System.arraycopy(buffer, position, target, offset, n);
            position += n;
            return n;
        }

        /**
         * Blocks until the phone sends something. A read that returns nothing is normal
         * while the phone app is not streaming yet, so it only counts as a failure when
         * the phone has been unplugged or the transfer keeps failing instantly.
         */
        private void fill() throws IOException {
            int quickFailures = 0;
            while (true) {
                if (closed) {
                    throw new IOException("USB link closed");
                }
                long start = SystemClock.uptimeMillis();
                int n = connection.bulkTransfer(endpointIn, buffer, buffer.length, 1000);
                if (n > 0) {
                    position = 0;
                    length = n;
                    return;
                }
                if (n < 0) {
                    if (!stillAttached()) {
                        throw new IOException("Phone unplugged");
                    }
                    if (SystemClock.uptimeMillis() - start < 100) {
                        if (++quickFailures > 20) {
                            throw new IOException("USB read keeps failing");
                        }
                        SystemClock.sleep(50);
                    } else {
                        quickFailures = 0;
                    }
                }
            }
        }
    }

    private final class Out extends OutputStream {
        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] data, int offset, int count) throws IOException {
            while (count > 0) {
                if (closed) {
                    throw new IOException("USB link closed");
                }
                int n = connection.bulkTransfer(endpointOut, data, offset, Math.min(count, CHUNK), 1000);
                if (n < 0) {
                    throw new IOException("USB write failed");
                }
                offset += n;
                count -= n;
            }
        }
    }
}
