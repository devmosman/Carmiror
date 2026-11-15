package com.mosman.carmirror.car;

/**
 * Wire format shared by the phone and car apps. Keep both copies identical.
 *
 * Every message is: 1 byte type, 4 byte big-endian payload length, payload.
 *   CONFIG (phone to car): int width, int height
 *   VIDEO  (phone to car): byte flags, long ptsUs, H.264 bytes
 *   TOUCH  (car to phone): byte action, float x, float y  (x and y are 0..1 of the picture)
 *   KEY    (car to phone): byte key
 *   CAPS   (phone to car): byte flags (bit 0: touch control is available),
 *                          byte rotation (Surface.ROTATION_* of the phone screen)
 *   REQUEST_KEY (car to phone): empty, asks for a fresh key frame
 */
final class Protocol {
    static final int TCP_PORT = 47800;
    static final int UDP_PORT = 47801;
    static final String BEACON = "CARMIRROR1";

    static final byte MSG_CONFIG = 1;
    static final byte MSG_VIDEO = 2;
    static final byte MSG_TOUCH = 3;
    static final byte MSG_KEY = 4;
    static final byte MSG_CAPS = 5;
    static final byte MSG_REQUEST_KEY = 6;

    static final byte FLAG_CODEC_CONFIG = 1;
    static final byte FLAG_KEYFRAME = 2;

    static final byte TOUCH_DOWN = 0;
    static final byte TOUCH_MOVE = 1;
    static final byte TOUCH_UP = 2;

    static final byte KEY_BACK = 1;
    static final byte KEY_HOME = 2;
    static final byte KEY_RECENTS = 3;

    private Protocol() {
    }
}
