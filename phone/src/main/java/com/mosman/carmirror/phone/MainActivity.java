package com.mosman.carmirror.phone;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

public class MainActivity extends Activity {
    private static final int REQUEST_CAPTURE = 1;
    private static final int REQUEST_NOTIFICATIONS = 2;
    private static final String ACTION_USB_PERMISSION = "com.mosman.carmirror.phone.USB_PERMISSION";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView statusView;
    private Button startButton;
    private Button touchButton;
    private boolean touchSupported;
    private boolean usbPermissionAsked;

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        touchSupported = getResources().getBoolean(R.bool.touch_supported);
        int pad = dp(20);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("CarMirror");
        title.setTextSize(28);
        title.setTextColor(Color.WHITE);
        column.addView(title);

        statusView = new TextView(this);
        statusView.setTextSize(16);
        statusView.setPadding(0, dp(16), 0, dp(16));
        statusView.setLineSpacing(dp(4), 1f);
        column.addView(statusView);

        startButton = new Button(this);
        startButton.setOnClickListener(v -> toggleMirroring());
        column.addView(startButton, buttonParams());

        touchButton = new Button(this);
        touchButton.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        if (touchSupported) {
            column.addView(touchButton, buttonParams());
        }

        TextView qualityLabel = new TextView(this);
        qualityLabel.setText("Picture quality and smoothness");
        qualityLabel.setTextSize(14);
        qualityLabel.setPadding(0, dp(20), 0, 0);
        column.addView(qualityLabel);

        SharedPreferences prefs = getSharedPreferences(MirrorService.PREFS, MODE_PRIVATE);
        Spinner quality = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, MirrorService.QUALITY_LABELS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        quality.setAdapter(adapter);
        quality.setSelection(Math.max(0, Math.min(MirrorService.QUALITY_LABELS.length - 1,
                prefs.getInt(MirrorService.PREF_QUALITY, MirrorService.DEFAULT_QUALITY))));
        quality.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (prefs.getInt(MirrorService.PREF_QUALITY, MirrorService.DEFAULT_QUALITY) == position) {
                    return;
                }
                prefs.edit().putInt(MirrorService.PREF_QUALITY, position).apply();
                // Takes effect straight away if mirroring is on.
                MirrorService service = MirrorService.instance;
                if (service != null) {
                    service.applySettings();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        column.addView(quality, buttonParams());

        Spinner fps = new Spinner(this);
        ArrayAdapter<String> fpsAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, MirrorService.FPS_LABELS);
        fpsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        fps.setAdapter(fpsAdapter);
        fps.setSelection(Math.max(0, Math.min(MirrorService.FPS_LABELS.length - 1,
                prefs.getInt(MirrorService.PREF_FPS, MirrorService.DEFAULT_FPS))));
        fps.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (prefs.getInt(MirrorService.PREF_FPS, MirrorService.DEFAULT_FPS) == position) {
                    return;
                }
                prefs.edit().putInt(MirrorService.PREF_FPS, position).apply();
                MirrorService service = MirrorService.instance;
                if (service != null) {
                    service.applySettings();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        column.addView(fps, buttonParams());

        TextView help = new TextView(this);
        help.setTextSize(14);
        help.setPadding(0, dp(20), 0, 0);
        help.setLineSpacing(dp(3), 1f);
        String steps = "1. Cable: plug the phone into the car's USB port, unlock the phone and "
                + "open CarMirror Car on the car screen. Accept the prompts on both screens.\n"
                + "   Wi-Fi: turn on this phone's hotspot and connect the car to it.\n"
                + "2. Tap Start mirroring and allow screen sharing (choose the whole screen).\n"
                + "3. The picture appears on the car screen.\n\n"
                + "Over the cable you can also control the phone from the car screen. ";
        if (touchSupported) {
            help.setText(steps
                    + "Over Wi-Fi, touch control needs the CarMirror touch control service "
                    + "switched on under Accessibility. If Android greys it out, open this app's "
                    + "App info page, tap the three dots and choose Allow restricted settings.");
        } else {
            help.setText(steps + "Over Wi-Fi this build is view-only.");
        }
        column.addView(help);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(column);
        setContentView(scroll);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
        if (savedInstanceState == null) {
            handleIntent(getIntent());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    /** The car connected over the cable, or the user just allowed the cable link. */
    private void handleIntent(Intent intent) {
        String action = intent == null ? null : intent.getAction();
        boolean attached = UsbManager.ACTION_USB_ACCESSORY_ATTACHED.equals(action);
        if (!attached && !ACTION_USB_PERMISSION.equals(action)) {
            return;
        }
        MirrorService service = MirrorService.instance;
        if (service != null) {
            service.tryOpenAccessory();
        } else if (attached && !MirrorService.running) {
            // Go straight to the screen-sharing prompt.
            toggleMirroring();
        }
    }

    /** If the cable is connected but CarMirror was not yet allowed to use it, ask once. */
    private void requestCablePermission() {
        if (usbPermissionAsked) {
            return;
        }
        UsbManager usb = (UsbManager) getSystemService(USB_SERVICE);
        UsbAccessory[] accessories = usb == null ? null : usb.getAccessoryList();
        if (accessories == null) {
            return;
        }
        for (UsbAccessory accessory : accessories) {
            if (!usb.hasPermission(accessory)) {
                usbPermissionAsked = true;
                Intent intent = new Intent(this, MainActivity.class).setAction(ACTION_USB_PERMISSION);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                usb.requestPermission(accessory, PendingIntent.getActivity(this, 0, intent, flags));
                return;
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresher);
        requestCablePermission();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresher);
        super.onPause();
    }

    private void toggleMirroring() {
        if (MirrorService.running) {
            stopService(new Intent(this, MirrorService.class));
            handler.postDelayed(this::refresh, 300);
        } else {
            MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CAPTURE && resultCode == RESULT_OK && data != null) {
            Intent service = new Intent(this, MirrorService.class);
            service.putExtra(MirrorService.EXTRA_RESULT_CODE, resultCode);
            service.putExtra(MirrorService.EXTRA_RESULT_DATA, data);
            startForegroundService(service);
            handler.postDelayed(this::refresh, 500);
        }
    }

    private void refresh() {
        boolean running = MirrorService.running;
        String car = MirrorService.clientInfo;
        boolean touch = TouchService.isEnabled();

        StringBuilder sb = new StringBuilder();
        sb.append("Mirroring: ").append(running ? "on" : "off").append('\n');
        sb.append("Car: ").append(car != null ? "connected (" + car + ")" : "not connected").append('\n');
        sb.append("Touch control: ")
                .append(!touchSupported ? "not in this build" : touch ? "enabled" : "disabled")
                .append('\n');
        sb.append("This phone's address: ").append(localAddresses());
        statusView.setText(sb);

        startButton.setText(running ? "Stop mirroring" : "Start mirroring");
        touchButton.setText(touch ? "Touch control settings" : "Enable touch control");
    }

    private static String localAddresses() {
        StringBuilder sb = new StringBuilder();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address) {
                        if (sb.length() > 0) {
                            sb.append(", ");
                        }
                        sb.append(address.getHostAddress());
                    }
                }
            }
        } catch (SocketException ignored) {
        }
        return sb.length() > 0 ? sb.toString() : "no network";
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        params.topMargin = dp(8);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
