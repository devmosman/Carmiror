package com.mosman.carmirror.phone;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

public class MainActivity extends Activity {
    private static final int REQUEST_CAPTURE = 1;
    private static final int REQUEST_NOTIFICATIONS = 2;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView statusView;
    private Button startButton;
    private Button touchButton;

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
        column.addView(touchButton, buttonParams());

        TextView help = new TextView(this);
        help.setTextSize(14);
        help.setPadding(0, dp(20), 0, 0);
        help.setLineSpacing(dp(3), 1f);
        help.setText("1. Turn on this phone's Wi-Fi hotspot and connect the car to it.\n"
                + "2. Tap Start mirroring and allow screen sharing (choose the whole screen).\n"
                + "3. Open CarMirror Car on the car screen. It finds this phone by itself.\n\n"
                + "Touch control from the car needs the CarMirror touch control service "
                + "switched on under Accessibility. If Android greys it out, open this app's "
                + "App info page, tap the three dots and choose Allow restricted settings.");
        column.addView(help);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(column);
        setContentView(scroll);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresher);
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
        sb.append("Touch control: ").append(touch ? "enabled" : "disabled").append('\n');
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
