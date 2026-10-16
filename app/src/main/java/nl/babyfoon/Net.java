package nl.babyfoon;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Shared constants, network helpers and status shown in the UI. */
final class Net {
    static final int AUDIO_PORT = 50505;
    static final int DISCOVERY_PORT = 50506;
    static final int SAMPLE_RATE = 16000;          // 16 kHz mono 16-bit = 32 KB/s
    static final int CHUNK_BYTES = SAMPLE_RATE / 10; // 50 ms of audio
    static final String BEACON = "BABYFOON1";

    static final String CHANNEL_RUN = "run";
    static final String CHANNEL_ALARM = "alarm";

    /** Read by MainActivity to show what's happening. */
    static volatile String status = "Niet actief";
    static volatile int level = 0; // 0..100

    private Net() {}

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
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_RUN, "Babyfoon actief", NotificationManager.IMPORTANCE_LOW));
        NotificationChannel alarm = new NotificationChannel(
                CHANNEL_ALARM, "Verbinding verbroken", NotificationManager.IMPORTANCE_HIGH);
        alarm.enableVibration(true);
        nm.createNotificationChannel(alarm);
    }

    static Notification notification(Context ctx, String channel, String text) {
        PendingIntent open = PendingIntent.getActivity(ctx, 0,
                new Intent(ctx, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(ctx, channel)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Babyfoon")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }
}
