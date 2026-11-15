package com.mosman.carmirror.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * Captures the phone screen, encodes it as H.264 and streams it to the car app.
 * Also receives touch and key messages from the car and hands them to TouchService.
 */
public class MirrorService extends Service {
    static final String EXTRA_RESULT_CODE = "resultCode";
    static final String EXTRA_RESULT_DATA = "resultData";

    private static final String TAG = "CarMirror";
    private static final String CHANNEL_ID = "mirror";
    private static final int NOTIFICATION_ID = 1;
    private static final int MAX_LONG_SIDE = 1280;
    private static final int BIT_RATE = 4_000_000;
    private static final int FRAME_RATE = 30;

    /** Read by MainActivity to show status. */
    static volatile boolean running;
    static volatile String clientInfo;

    private final Object lock = new Object();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private Handler mainHandler;
    private DisplayManager displayManager;
    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private VirtualDisplay virtualDisplay;
    private volatile MediaCodec encoder;
    private Surface inputSurface;
    private Thread drainThread;
    private int width;
    private int height;
    private volatile byte[] codecConfig;
    private volatile Client client;
    private volatile ServerSocket serverSocket;
    private volatile boolean stopped;
    private boolean listenerRegistered;
    private PowerManager.WakeLock wakeLock;

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
        }

        @Override
        public void onDisplayRemoved(int displayId) {
        }

        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                worker.execute(() -> reconfigureIfResized());
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        // The screen has to stay on while mirroring, otherwise the car shows black.
        wakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "carmirror:mirror");
        wakeLock.setReferenceCounted(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startAsForeground();
        if (projection != null) {
            return START_NOT_STICKY;
        }

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        try {
            projection = resultData == null ? null : manager.getMediaProjection(resultCode, resultData);
        } catch (RuntimeException e) {
            Log.e(TAG, "getMediaProjection failed", e);
            projection = null;
        }
        if (projection == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        projectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopSelf();
            }
        };
        projection.registerCallback(projectionCallback, mainHandler);

        try {
            synchronized (lock) {
                startPipelineLocked();
            }
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Could not start capture", e);
            stopSelf();
            return START_NOT_STICKY;
        }

        displayManager.registerDisplayListener(displayListener, mainHandler);
        listenerRegistered = true;
        new Thread(this::acceptLoop, "cm-accept").start();
        new Thread(this::beaconLoop, "cm-beacon").start();
        running = true;
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopped = true;
        running = false;
        clientInfo = null;
        if (listenerRegistered) {
            displayManager.unregisterDisplayListener(displayListener);
            listenerRegistered = false;
        }
        ServerSocket ss = serverSocket;
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException ignored) {
            }
        }
        Client c = client;
        client = null;
        if (c != null) {
            c.close();
        }
        worker.shutdown();
        synchronized (lock) {
            stopEncoderLocked();
            if (virtualDisplay != null) {
                virtualDisplay.release();
                virtualDisplay = null;
            }
            if (projection != null) {
                if (projectionCallback != null) {
                    projection.unregisterCallback(projectionCallback);
                }
                projection.stop();
                projection = null;
            }
        }
        setWake(false);
        super.onDestroy();
    }

    // ---------------------------------------------------------------- notification

    private void startAsForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "Screen mirroring", NotificationManager.IMPORTANCE_LOW));

        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("CarMirror is sharing your screen")
                .setContentText("Open the app to stop.")
                .setContentIntent(open)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    // ---------------------------------------------------------------- capture + encode

    /** Returns {width, height, densityDpi} for the stream, scaled down from the real screen. */
    private int[] computeSize() {
        DisplayMetrics metrics = new DisplayMetrics();
        displayManager.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics);
        int w = metrics.widthPixels;
        int h = metrics.heightPixels;
        float scale = Math.min(1f, (float) MAX_LONG_SIDE / Math.max(w, h));
        int sw = Math.max(2, Math.round(w * scale) & ~1);
        int sh = Math.max(2, Math.round(h * scale) & ~1);
        return new int[]{sw, sh, metrics.densityDpi};
    }

    private void startPipelineLocked() throws IOException {
        int[] size = computeSize();
        width = size[0];
        height = size[1];

        MediaCodec codec = createEncoder(true);

        if (virtualDisplay == null) {
            virtualDisplay = projection.createVirtualDisplay("CarMirror", width, height, size[2],
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, inputSurface, null, null);
        } else {
            // Android 14+ allows only one virtual display per consent, so reuse it.
            virtualDisplay.resize(width, height, size[2]);
            virtualDisplay.setSurface(inputSurface);
        }

        // Tell the car the new picture size before any frame of that size goes out.
        Client c = client;
        if (c != null) {
            c.sendConfig(width, height);
        }

        encoder = codec;
        drainThread = new Thread(() -> drainLoop(codec), "cm-encode");
        drainThread.start();
    }

    /**
     * Builds and starts the H.264 encoder. The tuned settings ask for constant bitrate and
     * the lowest latency; if this phone's encoder rejects them, fall back to the basics.
     */
    private MediaCodec createEncoder(boolean tuned) throws IOException {
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        // Key frames are large and cause hitches, so send few; the car asks when it needs one.
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 3);
        // A still screen produces no new frames; repeat the last one so the car never starves.
        format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L);
        if (tuned) {
            format.setInteger(MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
            format.setInteger(MediaFormat.KEY_PRIORITY, 0);
            format.setInteger(MediaFormat.KEY_LATENCY, 0);
            if (Build.VERSION.SDK_INT >= 29) {
                format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
            }
        }

        MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = codec.createInputSurface();
            codec.start();
            return codec;
        } catch (RuntimeException e) {
            codec.release();
            if (inputSurface != null) {
                inputSurface.release();
                inputSurface = null;
            }
            if (tuned) {
                Log.w(TAG, "Tuned encoder settings rejected, using defaults", e);
                return createEncoder(false);
            }
            throw e;
        }
    }

    private void stopEncoderLocked() {
        MediaCodec codec = encoder;
        encoder = null;
        Thread t = drainThread;
        drainThread = null;
        if (t != null) {
            try {
                t.join(1000);
            } catch (InterruptedException ignored) {
            }
        }
        if (virtualDisplay != null) {
            virtualDisplay.setSurface(null);
        }
        if (codec != null) {
            try {
                codec.stop();
            } catch (RuntimeException ignored) {
            }
            codec.release();
        }
        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }
        codecConfig = null;
    }

    /** Runs when the phone rotates: rebuild the encoder at the new shape. */
    private void reconfigureIfResized() {
        synchronized (lock) {
            if (stopped || projection == null) {
                return;
            }
            int[] size = computeSize();
            if (size[0] == width && size[1] == height) {
                return;
            }
            stopEncoderLocked();
            try {
                startPipelineLocked();
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "Could not restart capture after rotation", e);
                mainHandler.post(() -> stopSelf());
            }
        }
    }

    private void drainLoop(MediaCodec codec) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (!stopped && encoder == codec) {
            try {
                int index = codec.dequeueOutputBuffer(info, 100_000);
                if (index < 0) {
                    continue;
                }
                ByteBuffer buffer = codec.getOutputBuffer(index);
                byte[] data = new byte[info.size];
                if (buffer != null && info.size > 0) {
                    buffer.position(info.offset);
                    buffer.limit(info.offset + info.size);
                    buffer.get(data);
                }
                boolean isConfig = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                boolean isKey = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                long pts = info.presentationTimeUs;
                codec.releaseOutputBuffer(index, false);

                if (data.length == 0) {
                    continue;
                }
                if (isConfig) {
                    codecConfig = data;
                }
                Client c = client;
                if (c != null) {
                    c.sendVideo(data, isConfig, isKey, pts);
                }
            } catch (IllegalStateException e) {
                // Encoder was stopped underneath us (rotation or shutdown).
                break;
            }
        }
    }

    private void requestKeyFrame() {
        MediaCodec codec = encoder;
        if (codec == null) {
            return;
        }
        try {
            Bundle params = new Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            codec.setParameters(params);
        } catch (RuntimeException ignored) {
        }
    }

    // ---------------------------------------------------------------- network

    private void acceptLoop() {
        ServerSocket ss;
        try {
            ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(Protocol.TCP_PORT));
            serverSocket = ss;
        } catch (IOException e) {
            Log.e(TAG, "Could not listen on port " + Protocol.TCP_PORT, e);
            mainHandler.post(() -> stopSelf());
            return;
        }
        while (!stopped) {
            Socket socket;
            try {
                socket = ss.accept();
            } catch (IOException e) {
                break;
            }
            onClientConnected(socket);
        }
    }

    private void onClientConnected(Socket socket) {
        Client fresh;
        try {
            socket.setTcpNoDelay(true);
            fresh = new Client(socket);
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            return;
        }
        Client old;
        synchronized (lock) {
            old = client;
            fresh.sendConfig(width, height);
            fresh.sendCaps(TouchService.isEnabled());
            byte[] config = codecConfig;
            if (config != null) {
                fresh.sendVideo(config, true, false, 0);
            }
            client = fresh;
            requestKeyFrame();
        }
        // Newest connection wins: the car reconnecting replaces a stale link.
        if (old != null) {
            old.close();
        }
        clientInfo = socket.getInetAddress().getHostAddress();
        setWake(true);
        fresh.start();
    }

    private void onClientGone(Client gone) {
        synchronized (lock) {
            if (client == gone) {
                client = null;
                clientInfo = null;
                setWake(false);
            }
        }
    }

    private void setWake(boolean on) {
        try {
            if (on) {
                wakeLock.acquire();
            } else if (wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (RuntimeException ignored) {
        }
    }

    /** Announces this phone on the local network so the car finds it without typing an IP. */
    private void beaconLoop() {
        byte[] payload = Protocol.BEACON.getBytes(StandardCharsets.US_ASCII);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            while (!stopped) {
                for (InetAddress address : broadcastAddresses()) {
                    try {
                        socket.send(new DatagramPacket(payload, payload.length, address, Protocol.UDP_PORT));
                    } catch (IOException ignored) {
                    }
                }
                Thread.sleep(1000);
            }
        } catch (IOException | InterruptedException ignored) {
        }
    }

    private static List<InetAddress> broadcastAddresses() {
        List<InetAddress> result = new ArrayList<>();
        try {
            result.add(InetAddress.getByName("255.255.255.255"));
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) {
                    continue;
                }
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress broadcast = ia.getBroadcast();
                    if (broadcast != null) {
                        result.add(broadcast);
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return result;
    }

    /** One framed message waiting to be written to the car. */
    private static final class Packet {
        final byte[] bytes;
        final boolean droppable;

        Packet(byte[] bytes, boolean droppable) {
            this.bytes = bytes;
            this.droppable = droppable;
        }
    }

    /**
     * One connected car. Messages go through a queue with its own writer thread, so a slow
     * link never stalls the encoder. If the queue backs up, the waiting video frames are
     * thrown away and a fresh key frame is requested: the picture skips ahead instead of
     * falling further and further behind.
     */
    private final class Client {
        private static final int MAX_QUEUED = 12;

        private final Socket socket;
        private final OutputStream out;
        private final LinkedBlockingDeque<Packet> queue = new LinkedBlockingDeque<>();
        private volatile boolean closed;
        private volatile Thread writer;
        private boolean waitingForKeyFrame = true;
        private boolean touchAnnounced;

        Client(Socket socket) throws IOException {
            this.socket = socket;
            this.out = new BufferedOutputStream(socket.getOutputStream(), 64 * 1024);
        }

        void start() {
            Thread w = new Thread(this::writeLoop, "cm-write");
            writer = w;
            w.start();
            new Thread(this::readLoop, "cm-read").start();
        }

        synchronized void sendConfig(int w, int h) {
            ByteBuffer b = ByteBuffer.allocate(13);
            b.put(Protocol.MSG_CONFIG);
            b.putInt(8);
            b.putInt(w);
            b.putInt(h);
            queue.add(new Packet(b.array(), false));
            waitingForKeyFrame = true;
        }

        synchronized void sendCaps(boolean touch) {
            ByteBuffer b = ByteBuffer.allocate(6);
            b.put(Protocol.MSG_CAPS);
            b.putInt(1);
            b.put((byte) (touch ? 1 : 0));
            queue.add(new Packet(b.array(), false));
            touchAnnounced = touch;
        }

        synchronized void sendVideo(byte[] data, boolean isConfig, boolean isKey, long pts) {
            if (closed) {
                return;
            }
            boolean touch = TouchService.isEnabled();
            if (touch != touchAnnounced) {
                sendCaps(touch);
            }
            if (!isConfig) {
                if (queue.size() > MAX_QUEUED) {
                    queue.removeIf(p -> p.droppable);
                    waitingForKeyFrame = true;
                    requestKeyFrame();
                }
                // A decoder can only start from a key frame.
                if (waitingForKeyFrame && !isKey) {
                    return;
                }
                waitingForKeyFrame = false;
            }
            int flags = (isConfig ? Protocol.FLAG_CODEC_CONFIG : 0) | (isKey ? Protocol.FLAG_KEYFRAME : 0);
            ByteBuffer b = ByteBuffer.allocate(14 + data.length);
            b.put(Protocol.MSG_VIDEO);
            b.putInt(9 + data.length);
            b.put((byte) flags);
            b.putLong(pts);
            b.put(data);
            queue.add(new Packet(b.array(), !isConfig));
        }

        private void writeLoop() {
            try {
                while (!closed) {
                    Packet p = queue.take();
                    out.write(p.bytes);
                    if (queue.isEmpty()) {
                        out.flush();
                    }
                }
            } catch (IOException | InterruptedException ignored) {
            } finally {
                close();
            }
        }

        private void readLoop() {
            try {
                DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                while (!closed) {
                    int type = in.readByte();
                    int length = in.readInt();
                    if (length < 0 || length > 1024) {
                        throw new IOException("Bad message length " + length);
                    }
                    if (type == Protocol.MSG_TOUCH && length == 9) {
                        byte action = in.readByte();
                        float x = in.readFloat();
                        float y = in.readFloat();
                        TouchService.remoteTouch(action, x, y);
                    } else if (type == Protocol.MSG_KEY && length == 1) {
                        TouchService.remoteKey(in.readByte());
                    } else if (type == Protocol.MSG_REQUEST_KEY && length == 0) {
                        requestKeyFrame();
                    } else {
                        in.readFully(new byte[length]);
                    }
                }
            } catch (IOException ignored) {
            } finally {
                close();
                onClientGone(this);
            }
        }

        void close() {
            closed = true;
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            Thread w = writer;
            if (w != null) {
                w.interrupt();
            }
        }
    }
}
