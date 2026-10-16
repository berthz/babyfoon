package nl.babyfoon;

import android.Manifest;
import android.app.Notification;
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
import android.os.SystemClock;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Runs on the phone in the baby's room: records the microphone and streams raw PCM
 * to every connected parent phone over TCP. Announces itself via UDP broadcast.
 * A foreground service with wake + Wi-Fi locks keeps this alive with the screen locked.
 */
public class BabyService extends Service {
    private static final int NOTIF_ID = 1;
    /** 2 s of audio; a parent that falls this far behind is considered gone. */
    private static final int QUEUE_CHUNKS = 40;

    static volatile boolean running;

    private final List<Client> clients = new CopyOnWriteArrayList<>();
    private ServerSocket server;
    private PowerManager.WakeLock wakeLock;
    private final List<WifiManager.WifiLock> wifiLocks = new ArrayList<>();

    /** One parent phone. Has its own writer thread so a dead connection never blocks the microphone. */
    private final class Client {
        final Socket socket;
        final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(QUEUE_CHUNKS);
        volatile boolean closed;

        Client(Socket socket) {
            this.socket = socket;
            new Thread(this::writeLoop, "client-writer").start();
        }

        String name() {
            return socket.getInetAddress().getHostAddress();
        }

        private void writeLoop() {
            try {
                while (!closed) {
                    byte[] chunk = queue.take();
                    socket.getOutputStream().write(chunk);
                }
            } catch (IOException e) {
                drop(this, "verbinding verbroken (" + e.getMessage() + ")");
            } catch (InterruptedException ignored) {
            }
        }

        void close() {
            closed = true;
            try {
                socket.close(); // also unblocks a write that is stuck on a dead connection
            } catch (IOException ignored) {
            }
            queue.clear();
            queue.offer(new byte[0]); // wake the writer
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (running) return START_STICKY;

        Net.createChannels(this);
        try {
            startForegroundCompat(notification());
        } catch (RuntimeException e) {
            // Android 14 refuses a microphone service restarted from the background.
            Net.log("Kan microfoon niet starten op de achtergrond – open de app opnieuw");
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        acquireLocks();
        Net.log("Baby-telefoon gestart (IP " + Net.localIp() + ")");

        new Thread(this::acceptLoop, "accept").start();
        new Thread(this::beaconLoop, "beacon").start();
        new Thread(this::audioLoop, "audio").start();
        return START_STICKY;
    }

    private void startForegroundCompat(Notification n) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    @SuppressWarnings("deprecation")
    private void acquireLocks() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "babyfoon:baby");
        wakeLock.acquire();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        wifiLocks.add(wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "babyfoon:baby"));
        if (Build.VERSION.SDK_INT >= 29) {
            wifiLocks.add(wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "babyfoon:baby-ll"));
        }
        for (WifiManager.WifiLock l : wifiLocks) l.acquire();
    }

    private Notification notification() {
        int n = clients.size();
        String text = n == 0
                ? "Microfoon aan – wacht op ouder-telefoon (IP " + Net.localIp() + ")"
                : "Microfoon aan – " + n + " ouder-telefoon(s) verbonden";
        Net.status = text;
        return Net.statusNotification(this, n == 0 ? "Babyfoon: geen ouder verbonden" : "Babyfoon: verbonden",
                text, n > 0);
    }

    private void updateStatus() {
        if (!running) return;
        getSystemService(NotificationManager.class).notify(NOTIF_ID, notification());
    }

    private void drop(Client c, String reason) {
        if (c.closed && !clients.contains(c)) return;
        c.close();
        if (clients.remove(c)) {
            Net.log("Ouder " + c.name() + ": " + reason);
            updateStatus();
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                server = new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(Net.AUDIO_PORT));
                updateStatus();
                while (running) {
                    Socket s = server.accept();
                    s.setTcpNoDelay(true);
                    s.setKeepAlive(true);
                    // A parent that reconnects replaces its old (probably dead) connection.
                    for (Client old : clients) {
                        if (old.socket.getInetAddress().equals(s.getInetAddress())) {
                            drop(old, "vervangen door nieuwe verbinding");
                        }
                    }
                    Client c = new Client(s);
                    clients.add(c);
                    Net.log("Ouder " + c.name() + " verbonden");
                    updateStatus();
                }
            } catch (IOException e) {
                if (running) {
                    Net.log("Server-fout: " + e.getMessage() + " – opnieuw proberen");
                    closeQuietly(server);
                    SystemClock.sleep(1000);
                }
            }
        }
    }

    private void beaconLoop() {
        byte[] msg = Net.BEACON.getBytes(StandardCharsets.US_ASCII);
        while (running) {
            try (DatagramSocket ds = new DatagramSocket()) {
                ds.setBroadcast(true);
                while (running) {
                    for (InetAddress a : Net.broadcastAddresses()) {
                        try {
                            ds.send(new DatagramPacket(msg, msg.length, a, Net.DISCOVERY_PORT));
                        } catch (IOException ignored) {
                        }
                    }
                    SystemClock.sleep(1000);
                }
            } catch (IOException e) {
                SystemClock.sleep(1000);
            }
        }
    }

    private void audioLoop() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Net.log("Geen microfoon-toestemming");
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
                if (n <= 0) {
                    SystemClock.sleep(10);
                    continue;
                }
                Net.level = Net.peakLevel(buf, n);
                byte[] chunk = java.util.Arrays.copyOf(buf, n);
                for (Client c : clients) {
                    if (!c.queue.offer(chunk)) drop(c, "reageert niet meer");
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
        if (running) Net.log("Baby-telefoon gestopt");
        running = false;
        closeQuietly(server);
        for (Client c : clients) c.close();
        clients.clear();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        for (WifiManager.WifiLock l : wifiLocks) if (l.isHeld()) l.release();
        Net.status = "Niet actief";
        Net.level = 0;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
