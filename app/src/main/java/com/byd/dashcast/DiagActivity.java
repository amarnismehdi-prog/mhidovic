package com.byd.dashcast;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

public class DiagActivity extends AppCompatActivity {

    private static final String TAG = "DiagActivity";

    private MaterialButton btnProbeRun;
    private MaterialButton btnProbeCopy;
    private ProgressBar    pbProbe;
    private TextView       tvProbeStatus;
    private TextView       tvProbeOutput;
    private ScrollView     svProbeOutput;

    private final Handler mUiHandler = new Handler(Looper.getMainLooper());
    private String mLastProbeResult = null;

    // All commands run as one compound shell expression so they share a single ADB
    // connection. Each section is delimited by a header line for easy parsing later.
    private static final String PROBE_CMD =
            "echo '### MODEL ###';"
            + " getprop ro.product.model;"
            + " getprop ro.product.manufacturer;"
            + " getprop ro.build.version.release;"
            + " getprop ro.build.display.id;"
            + " echo '### CPU ###';"
            + " getprop ro.hardware;"
            + " getprop ro.board.platform;"
            + " echo '### SERVICES ###';"
            + " service list 2>/dev/null | grep -iE 'auto|cluster|fission|xdja|disp|dash|container|dilink';"
            + " echo '### DISPLAYS ###';"
            + " dumpsys display 2>/dev/null | grep -E 'mDisplayId|name=|mBaseDisplayInfo|width=|height=|isVirtual|mIsEnabled' | head -80;"
            + " echo '### PACKAGES ###';"
            + " pm list packages 2>/dev/null | grep -iE 'xdja|byd|container|cluster|dilink|fission|autodisp';"
            + " echo '### SERVICE CHECKS ###';"
            + " for svc in AutoContainer AutoDisplay CarCluster ClusterDisplay BydAutoContainer; do"
            + "   result=$(service check $svc 2>&1);"
            + "   echo \"$svc: $result\";"
            + " done;"
            + " echo '### VIRTUAL DISPLAYS ###';"
            + " dumpsys display 2>/dev/null | grep -A5 -i 'virtual';"
            + " echo '### DONE ###'";

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.applyLocale(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diag);

        btnProbeRun   = findViewById(R.id.btn_probe_run);
        btnProbeCopy  = findViewById(R.id.btn_probe_copy);
        pbProbe       = findViewById(R.id.pb_probe);
        tvProbeStatus = findViewById(R.id.tv_probe_status);
        tvProbeOutput = findViewById(R.id.tv_probe_output);
        svProbeOutput = findViewById(R.id.sv_probe_output);

        btnProbeRun.setOnClickListener(v -> runProbe());
        btnProbeCopy.setOnClickListener(v -> copyResult());

        wireDiagNavRail();
        AppLogger.lifecycle(getClass().getSimpleName(), "onCreate");
    }

    private void runProbe() {
        btnProbeRun.setEnabled(false);
        pbProbe.setVisibility(View.VISIBLE);
        tvProbeStatus.setText(getString(R.string.diag_probe_running));
        tvProbeStatus.setVisibility(View.VISIBLE);
        svProbeOutput.setVisibility(View.GONE);
        btnProbeCopy.setVisibility(View.GONE);
        mLastProbeResult = null;

        AdbLocalClient.executeShellWithResult(this, PROBE_CMD, new AdbLocalClient.Callback() {
            @Override
            public void onSuccess(String output) {
                mUiHandler.post(() -> showResult(output));
            }
            @Override
            public void onError(String error) {
                mUiHandler.post(() -> showError(error));
            }
        });
    }

    private void showResult(String output) {
        mLastProbeResult = output;
        tvProbeOutput.setText(output);
        svProbeOutput.setVisibility(View.VISIBLE);
        btnProbeCopy.setVisibility(View.VISIBLE);
        pbProbe.setVisibility(View.GONE);
        tvProbeStatus.setText(getString(R.string.diag_probe_done));
        btnProbeRun.setEnabled(true);
        // Scroll to top so the model line is visible first
        svProbeOutput.post(() -> svProbeOutput.scrollTo(0, 0));
        AppLogger.i(TAG, "Probe complete:\n" + output);
    }

    private void showError(String error) {
        pbProbe.setVisibility(View.GONE);
        tvProbeStatus.setText(getString(R.string.diag_probe_error, error));
        btnProbeRun.setEnabled(true);
        AppLogger.e(TAG, "Probe error: " + error);
    }

    private void copyResult() {
        if (mLastProbeResult == null) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("cluster_probe", mLastProbeResult));
            Toast.makeText(this, getString(R.string.diag_probe_copied), Toast.LENGTH_SHORT).show();
        }
    }

    private void wireDiagNavRail() {
        View navApps     = findViewById(R.id.nav_apps_diag);
        View navSettings = findViewById(R.id.nav_settings_diag);
        View navSysinfo  = findViewById(R.id.nav_sysinfo_diag);
        View navLog      = findViewById(R.id.nav_log_diag);
        View navLogo     = findViewById(R.id.iv_nav_logo_diag);
        if (navApps     != null) navApps.setOnClickListener(v -> { startActivity(new Intent(this, MainActivity.class)); finish(); });
        if (navSettings != null) navSettings.setOnClickListener(v -> { startActivity(new Intent(this, SettingsActivity.class)); finish(); });
        if (navSysinfo  != null) navSysinfo.setOnClickListener(v -> { startActivity(new Intent(this, SysInfoActivity.class)); finish(); });
        if (navLog      != null) navLog.setOnClickListener(v -> { startActivity(new Intent(this, LogActivity.class)); finish(); });
        if (navLogo     != null) navLogo.setOnClickListener(v -> { startActivity(new Intent(this, MainActivity.class)); finish(); });
    }
}
