package nl.babyfoon;

import android.Manifest;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Runs on the phone in the baby's room: records the microphone and streams raw PCM
 * to every connected parent phone over TCP. Announces itself via UDP broadcast.
 * A foreground service with wake + Wi-Fi locks keeps this alive with the screen locked.
 */
public class BabyService extends Service {
    private static final int NOTIF_ID = 1;

    static volatile boolean running;

    private final List<Socket> clients = new CopyOnWriteArrayList<>();
    private ServerSocket server;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (running) return START_STICKY;
        running = true;

        Net.createChannels(this);
        startForegroundCompat("Microfoon actief – wachten op ouder-telefoon");
        acquireLocks();

        new Thread(this::acceptLoop, "accept").start();
        new Thread(this::beaconLoop, "beacon").start();
        new Thread(this::audioLoop, "audio").start();
        return START_STICKY;
    }

    private void startForegroundCompat(String text) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, Net.notification(this, Net.CHANNEL_RUN, text),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIF_ID, Net.notification(this, Net.CHANNEL_RUN, text));
        }
    }

    @SuppressWarnings("deprecation")
    private void acquireLocks() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "babyfoon:baby");
        wakeLock.acquire();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "babyfoon:baby");
        wifiLock.acquire();
    }

    private void updateStatus() {
        int n = clients.size();
        String text = n == 0
                ? "Microfoon actief – wachten op ouder-telefoon (IP " + Net.localIp() + ")"
                : "Microfoon actief – " + n + " ouder-telefoon(s) verbonden";
        Net.status = text;
        getSystemService(NotificationManager.class)
                .notify(NOTIF_ID, Net.notification(this, Net.CHANNEL_RUN, text));
    }

    private void acceptLoop() {
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(Net.AUDIO_PORT));
            updateStatus();
            while (running) {
                Socket s = server.accept();
                s.setTcpNoDelay(true);
                s.setKeepAlive(true);
                clients.add(s);
                updateStatus();
            }
        } catch (IOException e) {
            if (running) Net.status = "Fout: " + e.getMessage();
        }
    }

    private void beaconLoop() {
        byte[] msg = Net.BEACON.getBytes(StandardCharsets.US_ASCII);
        try (DatagramSocket ds = new DatagramSocket()) {
            ds.setBroadcast(true);
            while (running) {
                for (InetAddress a : Net.broadcastAddresses()) {
                    try {
                        ds.send(new DatagramPacket(msg, msg.length, a, Net.DISCOVERY_PORT));
                    } catch (IOException ignored) {
                    }
                }
                Thread.sleep(1000);
            }
        } catch (Exception ignored) {
        }
    }

    private void audioLoop() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Net.status = "Geen microfoon-toestemming";
            stopSelf();
            return;
        }
        int min = AudioRecord.getMinBufferSize(Net.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec = new AudioRecord(MediaRecorder.AudioSource.MIC, Net.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(min, Net.CHUNK_BYTES * 4));
        byte[] buf = new byte[Net.CHUNK_BYTES];
        try {
            rec.startRecording();
            while (running) {
                int n = rec.read(buf, 0, buf.length);
                if (n <= 0) continue;
                Net.level = Net.peakLevel(buf, n);
                for (Socket s : clients) {
                    try {
                        s.getOutputStream().write(buf, 0, n);
                    } catch (IOException e) {
                        closeQuietly(s);
                        clients.remove(s);
                        updateStatus();
                    }
                }
            }
        } finally {
            rec.stop();
            rec.release();
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        try {
            if (c != null) c.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        closeQuietly(server);
        for (Socket s : clients) closeQuietly(s);
        clients.clear();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        Net.status = "Niet actief";
        Net.level = 0;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
