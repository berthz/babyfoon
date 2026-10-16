package nl.babyfoon;

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
import java.nio.charset.StandardCharsets;

/**
 * Runs on the parents' phone: finds the baby phone, receives the audio stream and plays it.
 * Reconnects automatically and sounds an alarm when the connection is lost.
 */
public class ParentService extends Service {
    static final String EXTRA_IP = "ip";
    private static final int NOTIF_ID = 2;
    private static final int ALARM_NOTIF_ID = 3;
    private static final long ALARM_REPEAT_MS = 60_000;

    static volatile boolean running;

    private String manualIp;
    private Thread worker;
    private volatile Socket socket;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
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
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, Net.notification(this, Net.CHANNEL_RUN, "Zoeken naar babyfoon…"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_ID, Net.notification(this, Net.CHANNEL_RUN, "Zoeken naar babyfoon…"));
        }
        acquireLocks();

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
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "babyfoon:parent");
        wifiLock.acquire();
        multicastLock = wm.createMulticastLock("babyfoon:discovery");
        multicastLock.acquire();
    }

    private void setStatus(String text) {
        Net.status = text;
        getSystemService(NotificationManager.class)
                .notify(NOTIF_ID, Net.notification(this, Net.CHANNEL_RUN, text));
    }

    private void run() {
        boolean wasConnected = false;
        long lastAlarm = 0;
        while (running) {
            try {
                InetAddress addr = manualIp != null ? InetAddress.getByName(manualIp) : discover(3000);
                if (addr != null) {
                    connectAndPlay(addr);
                    wasConnected = true; // returned normally = stream ended
                }
            } catch (IOException e) {
                // fall through to reconnect
            }
            if (!running) break;

            Net.level = 0;
            setStatus(wasConnected ? "VERBINDING VERBROKEN – opnieuw verbinden…" : "Zoeken naar babyfoon…");
            if (wasConnected && SystemClock.elapsedRealtime() - lastAlarm > ALARM_REPEAT_MS) {
                lastAlarm = SystemClock.elapsedRealtime();
                alarm();
            }
            SystemClock.sleep(1000);
        }
    }

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
                } catch (java.net.SocketTimeoutException e) {
                    return null;
                }
                String s = new String(p.getData(), 0, p.getLength(), StandardCharsets.US_ASCII);
                if (Net.BEACON.equals(s)) return p.getAddress();
            }
        }
        return null;
    }

    private void connectAndPlay(InetAddress addr) throws IOException {
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
            s.setSoTimeout(4000); // baby phone sends continuously; silence on the wire = lost
            s.setKeepAlive(true);
            InputStream in = s.getInputStream();

            getSystemService(NotificationManager.class).cancel(ALARM_NOTIF_ID);
            setStatus("Verbonden met babyfoon (" + addr.getHostAddress() + ")");
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
                if (n < 0) return;
                if ((n & 1) == 1) { // keep 16-bit sample alignment
                    int b = in.read();
                    if (b < 0) return;
                    buf[n++] = (byte) b;
                }
                Net.level = Net.peakLevel(buf, n);
                track.write(buf, 0, n);
            }
        } finally {
            socket = null;
            track.stop();
            track.release();
        }
    }

    private void alarm() {
        getSystemService(NotificationManager.class).notify(ALARM_NOTIF_ID,
                Net.notification(this, Net.CHANNEL_ALARM, "Verbinding met de babyfoon is verbroken!"));
        Vibrator v = getSystemService(Vibrator.class);
        if (v != null) v.vibrate(VibrationEffect.createWaveform(new long[]{0, 600, 300, 600, 300, 600}, -1));
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
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
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
