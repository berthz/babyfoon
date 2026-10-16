package nl.babyfoon;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Simple screen: pick the role of this phone, see status and sound level, stop. */
public class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView statusView;
    private TextView infoView;
    private ProgressBar levelBar;
    private EditText ipField;
    private TextView logView;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            String role = BabyService.running ? "Rol: BABY (zender)"
                    : ParentService.running ? "Rol: OUDERS (ontvanger)" : "Kies een rol";
            infoView.setText(role + "\nIP van deze telefoon: " + Net.localIp());
            statusView.setText(Net.status);
            levelBar.setProgress(Net.level);
            logView.setText(Net.logText());
            handler.postDelayed(this, 200);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 2, pad, pad);

        TextView title = new TextView(this);
        title.setText("Babyfoon");
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView version = new TextView(this);
        version.setText("Versie " + versionName());
        version.setGravity(Gravity.CENTER);
        root.addView(version);

        infoView = new TextView(this);
        infoView.setPadding(0, pad, 0, 0);
        root.addView(infoView);

        statusView = new TextView(this);
        statusView.setTextSize(18);
        statusView.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(statusView);

        levelBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        levelBar.setMax(100);
        root.addView(levelBar);

        Button baby = new Button(this);
        baby.setText("Dit is de BABY-telefoon");
        baby.setOnClickListener(v -> startBaby());
        root.addView(baby);

        ipField = new EditText(this);
        ipField.setHint("IP babyfoon (leeg = automatisch zoeken)");
        ipField.setInputType(InputType.TYPE_CLASS_PHONE);
        root.addView(ipField);

        Button parent = new Button(this);
        parent.setText("Dit is de OUDER-telefoon");
        parent.setOnClickListener(v -> startParent());
        root.addView(parent);

        Button stop = new Button(this);
        stop.setText("Stop");
        stop.setOnClickListener(v -> stopAll());
        root.addView(stop);

        Button battery = new Button(this);
        battery.setText("Batterijbesparing uitzetten voor deze app");
        battery.setOnClickListener(v -> askIgnoreBatteryOptimizations());
        root.addView(battery);

        TextView logTitle = new TextView(this);
        logTitle.setText("Gebeurtenissen");
        logTitle.setTextSize(16);
        logTitle.setPadding(0, pad, 0, 0);
        root.addView(logTitle);

        logView = new TextView(this);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setTextSize(12);
        root.addView(logView);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        requestPermissionsIfNeeded();
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    private void requestPermissionsIfNeeded() {
        List<String> needed = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!needed.isEmpty()) requestPermissions(needed.toArray(new String[0]), 1);
    }

    private void startBaby() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionsIfNeeded();
            return;
        }
        stopAll();
        startForegroundService(new Intent(this, BabyService.class));
    }

    private void startParent() {
        stopAll();
        startForegroundService(new Intent(this, ParentService.class)
                .putExtra(ParentService.EXTRA_IP, ipField.getText().toString()));
    }

    private void stopAll() {
        stopService(new Intent(this, BabyService.class));
        stopService(new Intent(this, ParentService.class));
    }

    private void askIgnoreBatteryOptimizations() {
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
            Net.status = "Batterijbesparing staat al uit voor deze app";
            return;
        }
        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName())));
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }
}
