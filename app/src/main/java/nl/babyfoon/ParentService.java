package nl.babyfoon;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.ToneGenerator;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;

import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Runs on the parents' phone: finds the baby phone, receives the audio stream and plays it.
 * Reconnects automatically. The lock-screen notification is green when connected and red when not;
 * after a short grace period without connection it shows an alarm notification and vibrates.
 */
public class ParentService extends Service {
    static final String EXTRA_IP = "ip";
    private static final int NOTIF_ID = 2;
    private static final int ALARM_NOTIF_ID = 3;
    /** Short hiccups reconnect within a few seconds; only alarm after this long without connection. */
    private static final long ALARM_AFTER_MS = 10_000;
    private static final long ALARM_REPEAT_MS = 60_000;

    static volatile boolean running;

    private String manualIp;
    /** Last address the baby phone was found on; tried directly because broadcasts are often filtered with the screen off. */
    private InetAddress lastAddr;
    private Thread worker;
    private volatile Socket socket;
    private PowerManager.WakeLock wakeLock;
    private final List<WifiManager.WifiLock> wifiLocks = new ArrayList<>();
    private WifiManager.MulticastLock multicastLock;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (running) return START_STICKY;
        running = true;
        if (intent != null) {
            String ip = intent.getStringExtra(EXTRA_IP);
            manualIp = (ip == null || ip.trim().isEmpty()) ? null : ip.trim();
        }

        Net.createChannels(this);
        Notification n = searchingNotification(false);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_ID, n);
        }
        acquireLocks();
        Net.log("Ouder-telefoon gestart" + (manualIp != null ? " (IP " + manualIp + ")" : ""));

        worker = new Thread(this::run, "parent");
        worker.start();
        return START_STICKY;
    }

    @SuppressWarnings("deprecation")
    private void acquireLocks() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "babyfoon:parent");
        wakeLock.acquire();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        wifiLocks.add(wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "babyfoon:parent"));
        if (Build.VERSION.SDK_INT >= 29) {
            wifiLocks.add(wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "babyfoon:parent-ll"));
        }
        for (WifiManager.WifiLock l : wifiLocks) l.acquire();
        multicastLock = wm.createMulticastLock("babyfoon:discovery");
        multicastLock.acquire();
    }

    private Notification searchingNotification(boolean lost) {
        String text = lost ? "Verbinding verbroken – opnieuw verbinden…" : "Zoeken naar baby-telefoon…";
        Net.status = "GEEN VERBINDING – " + text;
        return Net.statusNotification(this, "❌ GEEN VERBINDING", text, false);
    }

    private void showStatus(Notification n) {
        getSystemService(NotificationManager.class).notify(NOTIF_ID, n);
    }

    private void run() {
        boolean wasConnected = false;
        long disconnectedSince = SystemClock.elapsedRealtime();
        long lastAlarm = 0;
        int attempt = 0;
        while (running) {
            String reason = null;
            try {
                InetAddress addr;
                if (manualIp != null) {
                    addr = InetAddress.getByName(manualIp);
                } else if (lastAddr != null && attempt % 2 == 0) {
                    addr = lastAddr; // alternate: last known address, then discovery
                } else {
                    addr = discover(3000);
                    if (addr != null) lastAddr = addr;
                }
                attempt++;
                if (addr != null) {
                    reason = connectAndPlay(addr);
                }
            } catch (SocketTimeoutException e) {
                reason = "geen geluid meer ontvangen (time-out)";
            } catch (IOException e) {
                reason = e.getMessage();
            }
            if (!running) break;

            if (playing) {
                // We were connected in this round.
                playing = false;
                wasConnected = true;
                disconnectedSince = SystemClock.elapsedRealtime();
                lastAlarm = 0;
                attempt = 0;
                Net.log("Verbinding verbroken: " + reason);
            }
            Net.level = 0;
            showStatus(searchingNotification(wasConnected));

            long now = SystemClock.elapsedRealtime();
            if (now - disconnectedSince >= ALARM_AFTER_MS && now - lastAlarm >= ALARM_REPEAT_MS) {
                lastAlarm = now;
                long since = System.currentTimeMillis() - (now - disconnectedSince);
                alarm(new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(since)));
            }
            SystemClock.sleep(1000);
        }
    }

    /** Set by connectAndPlay once audio flows; read by run() to detect a lost connection. */
    private volatile boolean playing;

    /** Listens for the baby phone's UDP beacon. */
    private InetAddress discover(int timeoutMs) throws IOException {
        try (DatagramSocket ds = new DatagramSocket(null)) {
            ds.setReuseAddress(true);
            ds.bind(new InetSocketAddress(Net.DISCOVERY_PORT));
            ds.setSoTimeout(timeoutMs);
            byte[] buf = new byte[64];
            long end = SystemClock.elapsedRealtime() + timeoutMs;
            while (running && SystemClock.elapsedRealtime() < end) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    ds.receive(p);
                } catch (SocketTimeoutException e) {
                    return null;
                }
                String s = new String(p.getData(), 0, p.getLength(), StandardCharsets.US_ASCII);
                if (Net.BEACON.equals(s)) return p.getAddress();
            }
        }
        return null;
    }

    /** Plays the stream until it ends; returns why it ended. */
    private String connectAndPlay(InetAddress addr) throws IOException {
        int min = AudioTrack.getMinBufferSize(Net.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(Net.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build())
                .setBufferSizeInBytes(Math.max(min, Net.CHUNK_BYTES * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();

        try (Socket s = new Socket()) {
            socket = s;
            s.connect(new InetSocketAddress(addr, Net.AUDIO_PORT), 3000);
            s.setSoTimeout(5000); // baby phone sends continuously; silence on the wire = lost
            s.setKeepAlive(true);
            InputStream in = s.getInputStream();

            playing = true;
            lastAddr = addr;
            getSystemService(NotificationManager.class).cancel(ALARM_NOTIF_ID);
            Net.log("Verbonden met baby-telefoon " + addr.getHostAddress());
            Net.status = "Verbonden met baby-telefoon (" + addr.getHostAddress() + ")";
            showStatus(Net.statusNotification(this, "✅ Verbonden met babyfoon",
                    "Geluid komt binnen van " + addr.getHostAddress(), true));
            track.play();

            byte[] buf = new byte[Net.CHUNK_BYTES];
            int maxBacklog = Net.SAMPLE_RATE; // 0.5 s; drop older audio so latency can't creep up
            while (running) {
                int avail = in.available();
                if (avail > maxBacklog) {
                    long skip = (avail - Net.CHUNK_BYTES) & ~1L;
                    in.skip(skip);
                }
                int n = in.read(buf);
                if (n < 0) return "baby-telefoon heeft de verbinding gesloten";
                if ((n & 1) == 1) { // keep 16-bit sample alignment
                    int b = in.read();
                    if (b < 0) return "baby-telefoon heeft de verbinding gesloten";
                    buf[n++] = (byte) b;
                }
                Net.level = Net.peakLevel(buf, n);
                track.write(buf, 0, n);
            }
            return "gestopt";
        } finally {
            socket = null;
            track.stop();
            track.release();
        }
    }

    @SuppressWarnings("deprecation")
    private void alarm(String since) {
        Net.log("Alarm: geen verbinding sinds " + since);
        getSystemService(NotificationManager.class).notify(ALARM_NOTIF_ID,
                Net.alarmNotification(this, "⚠ GEEN VERBINDING met babyfoon",
                        "Geen verbinding met de baby-telefoon sinds " + since + "."));
        Vibrator v = getSystemService(Vibrator.class);
        if (v != null) {
            // USAGE_ALARM so it also vibrates in silent / do-not-disturb mode where allowed.
            v.vibrate(VibrationEffect.createWaveform(new long[]{0, 800, 400, 800, 400, 800}, -1),
                    new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
        }
        try {
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
            tg.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 2000);
            SystemClock.sleep(2100);
            tg.release();
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void onDestroy() {
        if (running) Net.log("Ouder-telefoon gestopt");
        running = false;
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        if (worker != null) worker.interrupt();
        getSystemService(NotificationManager.class).cancel(ALARM_NOTIF_ID);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        for (WifiManager.WifiLock l : wifiLocks) if (l.isHeld()) l.release();
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        Net.status = "Niet actief";
        Net.level = 0;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
