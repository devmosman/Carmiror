package com.mosman.carmirror.car;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Shows the phone's screen full height on the car display and sends touches back.
 * The left bar holds Back, Home and Recents for the phone, plus settings.
 */
public class MainActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "CarMirror";
    private static final String PREF_HOST = "manualHost";
    private static final int MAX_MESSAGE = 8 * 1024 * 1024;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private FrameLayout videoArea;
    private SurfaceView surfaceView;
    private TextView statusView;

    private HandlerThread sendThread;
    private Handler sendHandler;
    private WifiManager.MulticastLock multicastLock;

    private volatile Surface surface;
    private volatile boolean active;
    private volatile String discoveredHost;
    private volatile Socket currentSocket;
    private volatile DataOutputStream out;
    private volatile MediaCodec decoder;
    private Thread netThread;
    private Thread discoveryThread;
    private Thread renderThread;
    private volatile DatagramSocket discoverySocket;

    private int videoWidth;
    private int videoHeight;
    private long lastMoveSent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("carmirror", MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(Color.BLACK);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(Color.rgb(24, 24, 24));
        bar.addView(barButton("◀", v -> sendKey(Protocol.KEY_BACK)), barParams());
        bar.addView(barButton("●", v -> sendKey(Protocol.KEY_HOME)), barParams());
        bar.addView(barButton("■", v -> sendKey(Protocol.KEY_RECENTS)), barParams());
        bar.addView(barButton("⚙", v -> showSettings()), barParams());
        root.addView(bar, new LinearLayout.LayoutParams(dp(84), ViewGroup.LayoutParams.MATCH_PARENT));

        videoArea = new FrameLayout(this);
        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(this);
        surfaceView.setOnTouchListener(this::onVideoTouch);
        videoArea.addView(surfaceView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        statusView = new TextView(this);
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(22);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(dp(32), dp(32), dp(32), dp(32));
        videoArea.addView(statusView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        videoArea.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) {
                ui.post(this::layoutVideo);
            }
        });
        root.addView(videoArea, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        setContentView(root);

        sendThread = new HandlerThread("cm-send");
        sendThread.start();
        sendHandler = new Handler(sendThread.getLooper());

        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            multicastLock = wifi.createMulticastLock("carmirror");
            multicastLock.setReferenceCounted(false);
        }
        showStatus(searchingText());
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
        }
    }

    @Override
    protected void onDestroy() {
        stopNetwork();
        sendThread.quitSafely();
        super.onDestroy();
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    // ---------------------------------------------------------------- surface

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surface = holder.getSurface();
        startNetwork();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        // The decoder draws into this surface, so everything stops before it goes away.
        stopNetwork();
        surface = null;
    }

    /** Sizes the picture to fit the free area without stretching it. */
    private void layoutVideo() {
        int areaW = videoArea.getWidth();
        int areaH = videoArea.getHeight();
        if (areaW == 0 || areaH == 0 || videoWidth == 0 || videoHeight == 0) {
            return;
        }
        float scale = Math.min((float) areaW / videoWidth, (float) areaH / videoHeight);
        int w = Math.max(1, Math.round(videoWidth * scale));
        int h = Math.max(1, Math.round(videoHeight * scale));
        ViewGroup.LayoutParams current = surfaceView.getLayoutParams();
        if (current.width != w || current.height != h) {
            surfaceView.setLayoutParams(new FrameLayout.LayoutParams(w, h, Gravity.CENTER));
        }
    }

    // ---------------------------------------------------------------- network

    private void startNetwork() {
        if (active) {
            return;
        }
        active = true;
        if (multicastLock != null) {
            multicastLock.acquire();
        }
        netThread = new Thread(this::networkLoop, "cm-net");
        netThread.start();
        discoveryThread = new Thread(this::discoveryLoop, "cm-discovery");
        discoveryThread.start();
    }

    private void stopNetwork() {
        if (!active) {
            return;
        }
        active = false;
        closeQuietly(currentSocket);
        DatagramSocket ds = discoverySocket;
        if (ds != null) {
            ds.close();
        }
        join(netThread);
        join(discoveryThread);
        netThread = null;
        discoveryThread = null;
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }
    }

    private void networkLoop() {
        while (active) {
            for (String host : candidateHosts()) {
                if (!active) {
                    break;
                }
                Socket socket = new Socket();
                try {
                    socket.connect(new InetSocketAddress(host, Protocol.TCP_PORT), 1500);
                    socket.setTcpNoDelay(true);
                    currentSocket = socket;
                    runSession(socket);
                } catch (IOException | RuntimeException e) {
                    Log.d(TAG, "Session with " + host + " ended: " + e);
                } finally {
                    out = null;
                    currentSocket = null;
                    closeQuietly(socket);
                    releaseDecoder();
                }
            }
            if (active) {
                showStatus(searchingText());
                // Short naps so a stop request is noticed quickly.
                for (int i = 0; i < 10 && active; i++) {
                    SystemClock.sleep(100);
                }
            }
        }
    }

    private void runSession(Socket socket) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 256 * 1024));
        out = new DataOutputStream(socket.getOutputStream());
        showStatus("Connected. Waiting for picture…");

        while (active) {
            int type = in.readByte();
            int length = in.readInt();
            if (length < 0 || length > MAX_MESSAGE) {
                throw new IOException("Bad message length " + length);
            }
            if (type == Protocol.MSG_CONFIG && length == 8) {
                int w = in.readInt();
                int h = in.readInt();
                configureDecoder(w, h);
            } else if (type == Protocol.MSG_VIDEO && length >= 9) {
                int flags = in.readByte();
                long pts = in.readLong();
                byte[] data = new byte[length - 9];
                in.readFully(data);
                feedDecoder(data, flags, pts);
            } else {
                in.readFully(new byte[length]);
            }
        }
    }

    /** Hosts to try, in order: typed address, phone found by broadcast, Wi-Fi gateway (phone hotspot). */
    private Set<String> candidateHosts() {
        Set<String> hosts = new LinkedHashSet<>();
        String manual = prefs.getString(PREF_HOST, "");
        if (manual != null && !manual.trim().isEmpty()) {
            hosts.add(manual.trim());
        }
        String found = discoveredHost;
        if (found != null) {
            hosts.add(found);
        }
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            DhcpInfo dhcp = wifi == null ? null : wifi.getDhcpInfo();
            if (dhcp != null && dhcp.gateway != 0) {
                int g = dhcp.gateway;
                hosts.add((g & 0xff) + "." + ((g >> 8) & 0xff) + "." + ((g >> 16) & 0xff) + "." + ((g >> 24) & 0xff));
            }
        } catch (RuntimeException ignored) {
        }
        return hosts;
    }

    private void discoveryLoop() {
        byte[] buffer = new byte[64];
        while (active) {
            try (DatagramSocket socket = new DatagramSocket((java.net.SocketAddress) null)) {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(Protocol.UDP_PORT));
                socket.setSoTimeout(2000);
                discoverySocket = socket;
                while (active) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    try {
                        socket.receive(packet);
                    } catch (java.net.SocketTimeoutException e) {
                        continue;
                    }
                    String text = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.US_ASCII);
                    if (text.startsWith(Protocol.BEACON)) {
                        discoveredHost = packet.getAddress().getHostAddress();
                    }
                }
            } catch (IOException e) {
                SystemClock.sleep(1000);
            } finally {
                discoverySocket = null;
            }
        }
    }

    // ---------------------------------------------------------------- decode

    private void configureDecoder(int w, int h) throws IOException {
        releaseDecoder();
        Surface target = surface;
        if (target == null || !target.isValid() || w <= 0 || h <= 0) {
            throw new IOException("No surface to draw on");
        }
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Math.max(w * h, 512 * 1024));
        MediaCodec codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        try {
            codec.configure(format, target, null, 0);
            codec.start();
        } catch (RuntimeException e) {
            codec.release();
            throw e;
        }
        decoder = codec;
        renderThread = new Thread(() -> renderLoop(codec), "cm-render");
        renderThread.start();

        ui.post(() -> {
            videoWidth = w;
            videoHeight = h;
            layoutVideo();
            statusView.setVisibility(View.GONE);
        });
    }

    private void feedDecoder(byte[] data, int flags, long pts) {
        MediaCodec codec = decoder;
        if (codec == null || data.length == 0) {
            return;
        }
        // Wait up to ~200 ms for a free input buffer, then drop the frame.
        for (int attempt = 0; attempt < 10 && active; attempt++) {
            int index = codec.dequeueInputBuffer(20_000);
            if (index < 0) {
                continue;
            }
            ByteBuffer buffer = codec.getInputBuffer(index);
            if (buffer == null || buffer.capacity() < data.length) {
                codec.queueInputBuffer(index, 0, 0, pts, 0);
                return;
            }
            buffer.clear();
            buffer.put(data);
            int codecFlags = (flags & Protocol.FLAG_CODEC_CONFIG) != 0 ? MediaCodec.BUFFER_FLAG_CODEC_CONFIG : 0;
            codec.queueInputBuffer(index, 0, data.length, pts, codecFlags);
            return;
        }
    }

    private void renderLoop(MediaCodec codec) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (decoder == codec) {
            try {
                int index = codec.dequeueOutputBuffer(info, 50_000);
                if (index >= 0) {
                    codec.releaseOutputBuffer(index, true);
                }
            } catch (RuntimeException e) {
                break;
            }
        }
    }

    private void releaseDecoder() {
        MediaCodec codec = decoder;
        decoder = null;
        Thread t = renderThread;
        renderThread = null;
        join(t);
        if (codec != null) {
            try {
                codec.stop();
            } catch (RuntimeException ignored) {
            }
            codec.release();
        }
    }

    // ---------------------------------------------------------------- input back to the phone

    private boolean onVideoTouch(View view, MotionEvent event) {
        byte action;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                action = Protocol.TOUCH_DOWN;
                break;
            case MotionEvent.ACTION_MOVE:
                long now = SystemClock.uptimeMillis();
                if (now - lastMoveSent < 16) {
                    return true;
                }
                lastMoveSent = now;
                action = Protocol.TOUCH_MOVE;
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                action = Protocol.TOUCH_UP;
                break;
            default:
                return true;
        }
        if (view.getWidth() == 0 || view.getHeight() == 0) {
            return true;
        }
        float x = event.getX() / view.getWidth();
        float y = event.getY() / view.getHeight();
        sendHandler.post(() -> {
            DataOutputStream o = out;
            if (o == null) {
                return;
            }
            try {
                synchronized (o) {
                    o.writeByte(Protocol.MSG_TOUCH);
                    o.writeInt(9);
                    o.writeByte(action);
                    o.writeFloat(x);
                    o.writeFloat(y);
                    o.flush();
                }
            } catch (IOException ignored) {
            }
        });
        return true;
    }

    private void sendKey(byte key) {
        sendHandler.post(() -> {
            DataOutputStream o = out;
            if (o == null) {
                return;
            }
            try {
                synchronized (o) {
                    o.writeByte(Protocol.MSG_KEY);
                    o.writeInt(1);
                    o.writeByte(key);
                    o.flush();
                }
            } catch (IOException ignored) {
            }
        });
    }

    // ---------------------------------------------------------------- UI helpers

    private void showSettings() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("192.168.43.1");
        input.setText(prefs.getString(PREF_HOST, ""));
        new AlertDialog.Builder(this)
                .setTitle("Phone address")
                .setMessage("Leave empty to find the phone automatically. If that fails, type the "
                        + "address shown in the CarMirror app on the phone.")
                .setView(input)
                .setPositiveButton("Save", (dialog, which) -> {
                    prefs.edit().putString(PREF_HOST, input.getText().toString().trim()).apply();
                    // Drop the current link so the new address is tried straight away.
                    closeQuietly(currentSocket);
                })
                .setNegativeButton("Cancel", null)
                .setOnDismissListener(dialog -> hideSystemBars())
                .show();
    }

    private String searchingText() {
        return "Looking for your phone…\n\n"
                + "On the phone: turn on the hotspot, open CarMirror and tap Start mirroring.\n"
                + "On the car: connect Wi-Fi to the phone's hotspot.";
    }

    private void showStatus(String text) {
        ui.post(() -> {
            statusView.setText(text);
            statusView.setVisibility(View.VISIBLE);
        });
    }

    private Button barButton(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(26);
        button.setTextColor(Color.WHITE);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setOnClickListener(listener);
        return button;
    }

    private LinearLayout.LayoutParams barParams() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static void join(Thread thread) {
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(1500);
            } catch (InterruptedException ignored) {
            }
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
