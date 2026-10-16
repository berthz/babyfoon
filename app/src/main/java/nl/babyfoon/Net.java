package nl.babyfoon;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.Inet4Address;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Shared constants, network helpers, notifications and status shown in the UI. */
final class Net {
    static final int AUDIO_PORT = 50505;
    static final int DISCOVERY_PORT = 50506;
    static final int SAMPLE_RATE = 16000;          // 16 kHz mono 16-bit = 32 KB/s
    static final int CHUNK_BYTES = SAMPLE_RATE / 10; // 50 ms of audio
    static final String BEACON = "BABYFOON1";

    // New ids: importance and lock-screen visibility of an existing channel can't be changed.
    static final String CHANNEL_STATUS = "status_v2";
    static final String CHANNEL_ALARM = "alarm_v2";

    static final int GREEN = 0xFF2E7D32;
    static final int RED = 0xFFC62828;

    /** Read by MainActivity to show what's happening. */
    static volatile String status = "Niet actief";
    static volatile int level = 0; // 0..100

    private static final ArrayDeque<String> events = new ArrayDeque<>();
    private static final int MAX_EVENTS = 30;
    private static final long MAX_LOG_BYTES = 256 * 1024;
    private static File logFile;

    private Net() {}

    /** Enables the log file (kept across restarts, so it can be shared afterwards). */
    static synchronized void initLog(Context ctx) {
        if (logFile != null) return;
        logFile = new File(ctx.getFilesDir(), "log.txt");
        if (events.isEmpty()) {
            String[] lines = readLog().split("\n");
            for (int i = Math.max(0, lines.length - MAX_EVENTS); i < lines.length; i++) {
                if (!lines[i].isEmpty()) events.addFirst(lines[i]);
            }
        }
    }

    /** Adds a timestamped line to the event log shown in the app and to the log file. */
    static synchronized void log(String msg) {
        String line = new SimpleDateFormat("dd-MM HH:mm:ss", Locale.ROOT).format(new Date()) + "  " + msg;
        events.addFirst(line);
        while (events.size() > MAX_EVENTS) events.removeLast();
        if (logFile == null) return;
        try {
            if (logFile.length() > MAX_LOG_BYTES) {
                File old = new File(logFile.getParentFile(), "log.old.txt");
                old.delete();
                logFile.renameTo(old);
            }
            try (FileWriter w = new FileWriter(logFile, true)) {
                w.write(line + "\n");
            }
        } catch (IOException ignored) {
        }
    }

    static synchronized String logText() {
        StringBuilder sb = new StringBuilder();
        for (String e : events) sb.append(e).append('\n');
        return sb.toString();
    }

    /** Whole log file (oldest first), for sharing. */
    static synchronized String readLog() {
        if (logFile == null) return logText();
        StringBuilder sb = new StringBuilder();
        for (File f : new File[]{new File(logFile.getParentFile(), "log.old.txt"), logFile}) {
            if (!f.exists()) continue;
            try {
                sb.append(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
        }
        // Share sheets choke on huge texts; the end is what matters.
        int max = 90_000;
        return sb.length() > max ? sb.substring(sb.length() - max) : sb.toString();
    }

    /** First site-local IPv4 address of this phone (the Wi-Fi address). */
    static String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return "?";
    }

    /** Subnet broadcast addresses plus the global one; some routers drop 255.255.255.255. */
    static List<InetAddress> broadcastAddresses() {
        List<InetAddress> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getBroadcast() != null) out.add(ia.getBroadcast());
                }
            }
            out.add(InetAddress.getByName("255.255.255.255"));
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Peak loudness of a PCM16 little-endian buffer, scaled to 0..100. */
    static int peakLevel(byte[] buf, int len) {
        int peak = 0;
        for (int i = 0; i + 1 < len; i += 2) {
            int s = Math.abs((short) ((buf[i] & 0xff) | (buf[i + 1] << 8)));
            if (s > peak) peak = s;
        }
        return Math.min(100, peak * 100 / 32767);
    }

    static void createChannels(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        nm.deleteNotificationChannel("run");
        nm.deleteNotificationChannel("alarm");

        // Default importance (not low) so the status is shown on the lock screen; silent.
        NotificationChannel status = new NotificationChannel(
                CHANNEL_STATUS, "Status babyfoon", NotificationManager.IMPORTANCE_DEFAULT);
        status.setSound(null, null);
        status.enableVibration(false);
        status.setShowBadge(false);
        status.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(status);

        NotificationChannel alarm = new NotificationChannel(
                CHANNEL_ALARM, "Geen verbinding", NotificationManager.IMPORTANCE_HIGH);
        alarm.enableVibration(true);
        alarm.setVibrationPattern(new long[]{0, 600, 300, 600, 300, 600});
        alarm.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        alarm.setBypassDnd(true);
        alarm.setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI,
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
        nm.createNotificationChannel(alarm);
    }

    /** Ongoing, colored status notification that is fully visible on the lock screen. */
    static Notification statusNotification(Context ctx, String title, String text, boolean ok) {
        return builder(ctx, CHANNEL_STATUS, title, text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setColor(ok ? GREEN : RED)
                .setColorized(true)
                .setSmallIcon(ok ? android.R.drawable.ic_btn_speak_now
                        : android.R.drawable.stat_notify_error)
                .build();
    }

    static Notification alarmNotification(Context ctx, String title, String text) {
        return builder(ctx, CHANNEL_ALARM, title, text)
                .setCategory(Notification.CATEGORY_ALARM)
                .setColor(RED)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .build();
    }

    private static Notification.Builder builder(Context ctx, String channel, String title, String text) {
        PendingIntent open = PendingIntent.getActivity(ctx, 0,
                new Intent(ctx, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(ctx, channel)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(open);
    }
}
