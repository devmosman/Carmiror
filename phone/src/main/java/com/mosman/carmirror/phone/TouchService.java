package com.mosman.carmirror.phone;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;

/**
 * Replays taps and swipes made on the car screen. Android only lets an accessibility
 * service inject touches into other apps, which is why this has to be enabled by hand
 * in Settings. It subscribes to no events and reads nothing from the screen.
 *
 * A gesture is replayed when the finger lifts, with the same path and duration.
 */
public class TouchService extends AccessibilityService {
    private static volatile TouchService instance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Path path;
    private long downTime;

    static boolean isEnabled() {
        return instance != null;
    }

    static void remoteTouch(byte action, float x, float y) {
        TouchService service = instance;
        if (service != null) {
            service.mainHandler.post(() -> service.handleTouch(action, x, y));
        }
    }

    static void remoteKey(byte key) {
        TouchService service = instance;
        if (service != null) {
            service.mainHandler.post(() -> service.handleKey(key));
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    private void handleTouch(byte action, float nx, float ny) {
        DisplayMetrics metrics = new DisplayMetrics();
        DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        dm.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics);
        float x = clamp(nx) * (metrics.widthPixels - 1);
        float y = clamp(ny) * (metrics.heightPixels - 1);

        if (action == Protocol.TOUCH_DOWN) {
            path = new Path();
            path.moveTo(x, y);
            downTime = SystemClock.uptimeMillis();
        } else if (action == Protocol.TOUCH_MOVE) {
            if (path != null) {
                path.lineTo(x, y);
            }
        } else if (action == Protocol.TOUCH_UP) {
            if (path == null) {
                return;
            }
            path.lineTo(x, y);
            long duration = SystemClock.uptimeMillis() - downTime;
            duration = Math.max(40, Math.min(duration, 8000));
            try {
                GestureDescription gesture = new GestureDescription.Builder()
                        .addStroke(new GestureDescription.StrokeDescription(path, 0, duration))
                        .build();
                dispatchGesture(gesture, null, null);
            } catch (RuntimeException ignored) {
            }
            path = null;
        }
    }

    private void handleKey(byte key) {
        if (key == Protocol.KEY_BACK) {
            performGlobalAction(GLOBAL_ACTION_BACK);
        } else if (key == Protocol.KEY_HOME) {
            performGlobalAction(GLOBAL_ACTION_HOME);
        } else if (key == Protocol.KEY_RECENTS) {
            performGlobalAction(GLOBAL_ACTION_RECENTS);
        }
    }

    private static float clamp(float v) {
        if (Float.isNaN(v)) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f, v));
    }
}
