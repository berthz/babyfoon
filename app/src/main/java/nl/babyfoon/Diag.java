package nl.babyfoon;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.PowerManager;

/**
 * Logs what Android does around the connection (screen, Wi-Fi, network blocking, battery modes)
 * and keeps an explicit request on the Wi-Fi network so it isn't released while the screen is off.
 */
final class Diag {
    private final Context ctx;
    private final ConnectivityManager cm;
    private final PowerManager pm;
    private volatile Network wifi;
    private BroadcastReceiver receiver;
    private ConnectivityManager.NetworkCallback defaultCallback;
    private ConnectivityManager.NetworkCallback wifiCallback;

    Diag(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        cm = this.ctx.getSystemService(ConnectivityManager.class);
        pm = this.ctx.getSystemService(PowerManager.class);
    }

    /** The Wi-Fi network this app holds a request on, or null. */
    Network wifi() {
        return wifi;
    }

    void start(String role) {
        Net.log("=== " + role + " – versie " + versionName() + " – " + Build.MANUFACTURER + " "
                + Build.MODEL + ", Android " + Build.VERSION.RELEASE);
        logSettings();

        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String a = i.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(a)) Net.log("Scherm uit");
                else if (Intent.ACTION_SCREEN_ON.equals(a)) Net.log("Scherm aan");
                else if (PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED.equals(a))
                    Net.log("Doze (diepe slaap): " + (pm.isDeviceIdleMode() ? "AAN" : "uit"));
                else if (PowerManager.ACTION_POWER_SAVE_MODE_CHANGED.equals(a))
                    Net.log("Energiebesparing: " + (pm.isPowerSaveMode() ? "AAN" : "uit"));
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
        f.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            ctx.registerReceiver(receiver, f);
        }

        defaultCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network n) {
                Net.log("Netwerk beschikbaar");
            }

            @Override
            public void onLost(Network n) {
                Net.log("Netwerk WEG");
            }

            @Override
            public void onBlockedStatusChanged(Network n, boolean blocked) {
                Net.log(blocked ? "Android BLOKKEERT netwerk voor deze app" : "Netwerk niet geblokkeerd");
            }
        };
        cm.registerDefaultNetworkCallback(defaultCallback);

        // Holding a request on Wi-Fi tells Android this app needs it, also with the screen off.
        // No INTERNET capability needed: the phones only talk to each other on the local network.
        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        wifiCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network n) {
                wifi = n;
                Net.log("Wifi beschikbaar (IP " + Net.localIp() + ")");
            }

            @Override
            public void onLost(Network n) {
                if (n.equals(wifi)) wifi = null;
                Net.log("Wifi WEG");
            }
        };
        cm.requestNetwork(req, wifiCallback);
    }

    private void logSettings() {
        StringBuilder sb = new StringBuilder("Instellingen: batterij-optimalisatie ");
        sb.append(pm.isIgnoringBatteryOptimizations(ctx.getPackageName()) ? "uit (goed)" : "AAN (slecht)");
        if (Build.VERSION.SDK_INT >= 28) {
            ActivityManager am = ctx.getSystemService(ActivityManager.class);
            sb.append(", achtergrond beperkt: ").append(am.isBackgroundRestricted() ? "JA (slecht)" : "nee");
        }
        int ds = cm.getRestrictBackgroundStatus();
        sb.append(", databesparing: ").append(ds == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
                ? "AAN (slecht)" : "uit");
        sb.append(", energiebesparing: ").append(pm.isPowerSaveMode() ? "AAN" : "uit");
        Net.log(sb.toString());
    }

    private String versionName() {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    void stop() {
        try {
            if (receiver != null) ctx.unregisterReceiver(receiver);
            if (defaultCallback != null) cm.unregisterNetworkCallback(defaultCallback);
            if (wifiCallback != null) cm.unregisterNetworkCallback(wifiCallback);
        } catch (RuntimeException ignored) {
        }
        wifi = null;
    }
}
