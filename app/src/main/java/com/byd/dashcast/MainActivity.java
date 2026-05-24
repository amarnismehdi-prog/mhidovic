package com.byd.dashcast;

import android.content.ComponentName;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.view.Display;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.byd.dashcast.dashboard.DashboardLauncher;

import net.osmand.aidl.IOsmAndAidlInterface;
import net.osmand.aidl.navigate.AStopNavigationParams;

import java.util.HashSet;
import java.util.Set;

@android.annotation.SuppressLint({"ClickableViewAccessibility", "SetTextI18n"})
@SuppressWarnings("deprecation")
public class MainActivity extends AppCompatActivity
        implements ClusterService.Listener {

    private static final String TAG = "BYDApp";

    // ── Service ───────────────────────────────────────────────────────────────
    private ClusterService   mClusterService;
    private boolean          mServiceBound   = false;
    private boolean          mBindRequested  = false;
    private DashboardLauncher mDashboardLauncher;

    private static final String PREFS_NAME       = SettingsActivity.PREFS_NAME;
    private static final String PREF_CLUSTER_PKG  = "cluster_active_pkg";
    private static final String PREF_CLUSTER_NAME = "cluster_active_name";
    private static final String PREF_CLUSTER_TYPE = SettingsActivity.PREF_CLUSTER_TYPE;
    private static final int    CLUSTER_TYPE_DEFAULT = 30;
    private static final String PREF_SESSION_CLUSTER_PKGS = "session_cluster_pkgs";

    private static final long ACTIVATE_TIMEOUT_MS = 30_000;
    private Runnable mActivateTimeoutRunnable;
    private boolean  mWasManualActivation = false;

    // ── UI ────────────────────────────────────────────────────────────────────
    private View     mStatusDot;
    private android.graphics.drawable.GradientDrawable mStatusDotDrawable;
    private TextView tvDashboardStatus;
    private Button   btnActivateCluster;
    private Button   btnRestoreCluster;
    private Button   btnRestartNav;
    private Button   btnShowMirror;
    private android.widget.ImageView ivNavLogo;

    // Fullscreen mirror overlay
    private com.google.android.material.floatingactionbutton.FloatingActionButton btnExitFullscreen;
    private View vNavRail;
    private View llTopBar;
    private View llRightPane;
    private android.widget.FrameLayout vRootOverlay;
    private boolean mIsFullscreenMirror = false;

    // Mirror views
    private com.google.android.material.card.MaterialCardView cardClusterPreview;
    private android.widget.FrameLayout frameMirror;
    private TextureView clusterMirror;
    private TextView    tvMirrorPlaceholder;
    private ImageView   clusterMirrorScreenshot;
    private InsetOverlayView mInsetOverlay;
    private Surface     mMirrorSurface;

    // Screenshot loop (fallback)
    private final Handler mScreenshotHandler = new Handler(Looper.getMainLooper());
    private volatile Runnable mScreenshotRunnable = null;
    private static final int SCREENSHOT_INTERVAL_MS = 800;

    // Daemon Binder (uid=2000 mirror)
    private IBinder mDaemonBinder = null;
    private final BroadcastReceiver mDaemonReadyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Bundle extras = intent.getExtras();
            if (extras == null) return;
            IBinder binder = extras.getBinder("daemon_binder");
            if (binder == null) return;
            mDaemonBinder = binder;
            AppLogger.i(TAG, "Daemon Binder received");
            if (mServiceBound && mClusterService != null) {
                mClusterService.getInputForwarder().setDaemonBinder(mDaemonBinder);
            }
            if (mMirrorSurface != null && mMirrorSurface.isValid()) {
                attemptStartMirrorWithCurrentHolder();
            }
        }
    };

    // OsmAnd+ AIDL
    private IOsmAndAidlInterface mOsmAndAidl = null;
    private boolean mOsmAndBindRequested = false;
    private com.google.android.material.textfield.TextInputEditText etNavAddress;
    private Button btnNavGo;
    private Button btnNavStop;
    private final ServiceConnection mOsmAndConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            mOsmAndAidl = IOsmAndAidlInterface.Stub.asInterface(service);
            AppLogger.i(TAG, "OsmAnd+ AIDL connected");
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            mOsmAndAidl = null;
            mOsmAndBindRequested = false;
        }
    };

    // Cluster app tracking
    private String mCurrentDashboardApp = null;
    private String mCurrentDashboardPkg = null;
    private final java.util.Set<String> mSessionClusterPackages = new java.util.LinkedHashSet<>();

    private static final int DOT_COLOR_OFF     = 0xFF888888;
    private static final int DOT_COLOR_PENDING = 0xFFFFC107;
    private static final int DOT_COLOR_ACTIVE  = 0xFF4CAF50;

    // ── Service connection ────────────────────────────────────────────────────
    private final ServiceConnection mServiceConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            mClusterService   = ((ClusterService.LocalBinder) binder).getService();
            mServiceBound     = true;
            mDashboardLauncher = mClusterService.getLauncher();
            mClusterService.setListener(MainActivity.this);
            AppLogger.log(TAG, "ClusterService bound — displayId=" + mClusterService.getDisplayId());
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            mServiceBound   = false;
            mBindRequested  = false;
            mClusterService = null;
            if (mDashboardLauncher != null) mDashboardLauncher.setDashboardDisplayId(-1);
            mCurrentDashboardApp = null;
            mCurrentDashboardPkg = null;
            if (btnActivateCluster != null) btnActivateCluster.setEnabled(true);
            AppLogger.log(TAG, "ClusterService disconnected");
        }
    };

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.applyLocale(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        AppLogger.lifecycle(getClass().getSimpleName(), "onCreate");

        // Boot cleanup: move cluster apps back to Display 0 if auto-start is off
        SharedPreferences bootPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (!bootPrefs.getBoolean(SettingsActivity.PREF_BOOT_AUTO_START, false)) {
            final Context appCtx = getApplicationContext();
            Thread t = new Thread(new Runnable() {
                @Override public void run() { cleanupDisplayAffinityAtBoot(appCtx); }
            }, "boot-cleanup");
            t.setDaemon(true);
            t.start();
        } else {
            bootPrefs.edit().remove(PREF_SESSION_CLUSTER_PKGS).apply();
        }

        // Unlock hidden APIs for SurfaceControl mirror
        com.byd.dashcast.dashboard.ClusterMirrorManager.unlockHiddenApis();

        // Daemon Binder receiver
        registerReceiver(mDaemonReadyReceiver,
                new IntentFilter(com.byd.dashcast.daemon.MirrorDaemon.ACTION_DAEMON_READY));

        // Floating remote button (not in lightweight mode)
        boolean lightweight = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
                .getBoolean(SettingsActivity.PREF_LIGHTWEIGHT_MODE, false);
        if (!lightweight) {
            startService(new Intent(this, FloatingRemoteButton.class));
        }

        handleShowMirrorIntent(getIntent());

        // ── Views ──
        mStatusDot          = findViewById(R.id.view_status_dot);
        tvDashboardStatus   = (TextView) findViewById(R.id.tv_dashboard_status);
        mStatusDotDrawable  = new android.graphics.drawable.GradientDrawable();
        mStatusDotDrawable.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        if (mStatusDot != null) mStatusDot.setBackground(mStatusDotDrawable);

        btnActivateCluster  = (Button) findViewById(R.id.btn_activate_cluster);
        btnRestoreCluster   = (Button) findViewById(R.id.btn_restore_cluster);
        btnRestartNav       = (Button) findViewById(R.id.btn_restart_nav);
        btnShowMirror       = (Button) findViewById(R.id.btn_show_mirror);
        ivNavLogo           = (android.widget.ImageView) findViewById(R.id.iv_nav_logo);

        btnExitFullscreen   = findViewById(R.id.btn_exit_fullscreen);
        vNavRail            = findViewById(R.id.ll_nav_rail);
        llTopBar            = findViewById(R.id.ll_top_bar);
        llRightPane         = findViewById(R.id.ll_right_pane);
        vRootOverlay        = findViewById(R.id.root_overlay);
        cardClusterPreview  = findViewById(R.id.card_cluster_preview);
        frameMirror         = (android.widget.FrameLayout) findViewById(R.id.frame_cluster_mirror);
        clusterMirror       = (TextureView) findViewById(R.id.cluster_mirror);
        tvMirrorPlaceholder = (TextView) findViewById(R.id.tv_mirror_placeholder);
        clusterMirrorScreenshot = (ImageView) findViewById(R.id.cluster_mirror_screenshot);
        mInsetOverlay       = (InsetOverlayView) findViewById(R.id.inset_overlay);

        // OsmAnd+ nav controls
        etNavAddress = (com.google.android.material.textfield.TextInputEditText)
                findViewById(R.id.et_nav_address);
        btnNavGo     = (Button) findViewById(R.id.btn_nav_go);
        btnNavStop   = (Button) findViewById(R.id.btn_nav_stop);

        mDashboardLauncher = new DashboardLauncher(this);

        // ── Button wiring ──

        btnActivateCluster.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { activateCluster(); }
        });

        btnRestoreCluster.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { restoreBydDashboard(); }
        });
        btnRestoreCluster.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                showStopProjectionSheet();
                return true;
            }
        });

        if (btnRestartNav != null) {
            btnRestartNav.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    String bootPkg = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
                            .getString(SettingsActivity.PREF_BOOT_DEFAULT_APP, "net.osmand.plus");
                    tvDashboardStatus.setText(getString(R.string.status_activating_cluster));
                    setStatusDot(DOT_COLOR_PENDING);
                    if (mServiceBound && mClusterService != null) {
                        mClusterService.forceRestartProjection(bootPkg);
                    } else {
                        Intent svcIntent = new Intent(MainActivity.this, ClusterService.class);
                        svcIntent.putExtra(ClusterService.EXTRA_AUTO_LAUNCH_PKG, bootPkg);
                        startForegroundService(svcIntent);
                        if (!mBindRequested) {
                            mBindRequested = true;
                            bindService(new Intent(MainActivity.this, ClusterService.class),
                                    mServiceConn, BIND_AUTO_CREATE);
                        }
                    }
                }
            });
        }

        if (btnNavGo != null) {
            btnNavGo.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    String address = etNavAddress != null
                            ? etNavAddress.getText().toString().trim() : "";
                    if (address.isEmpty()) return;
                    try {
                        Uri geoUri = Uri.parse("geo:0,0?q=" + Uri.encode(address));
                        Intent navIntent = new Intent(Intent.ACTION_VIEW, geoUri);
                        navIntent.setPackage("net.osmand.plus");
                        navIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP
                                | Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(navIntent);
                    } catch (android.content.ActivityNotFoundException e) {
                        Toast.makeText(MainActivity.this, "OsmAnd+ introuvable", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        if (btnNavStop != null) {
            btnNavStop.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (mOsmAndAidl != null) {
                        try {
                            mOsmAndAidl.stopNavigation(new AStopNavigationParams());
                        } catch (android.os.RemoteException e) {
                            AppLogger.w(TAG, "stopNavigation: " + e.getMessage());
                        }
                    } else {
                        Toast.makeText(MainActivity.this,
                                getString(R.string.main_nav_stop_error), Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        if (btnShowMirror != null) {
            btnShowMirror.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    enterFullscreenMirror();
                }
            });
        }

        if (btnExitFullscreen != null) {
            btnExitFullscreen.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { exitFullscreenMirror(); }
            });
        }

        if (ivNavLogo != null) {
            ivNavLogo.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    showOverflowMenu(v);
                    return true;
                }
            });
        }

        // Nav rail
        View navSettings = findViewById(R.id.nav_settings);
        if (navSettings != null) navSettings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        View navDiag = findViewById(R.id.nav_diag);
        if (navDiag != null) navDiag.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, DiagActivity.class));
            }
        });
        View navSysinfo = findViewById(R.id.nav_sysinfo);
        if (navSysinfo != null) navSysinfo.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SysInfoActivity.class));
            }
        });
        View navLog = findViewById(R.id.nav_log);
        if (navLog != null) navLog.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, LogActivity.class));
            }
        });
        View navHelp = findViewById(R.id.nav_help);
        if (navHelp != null) navHelp.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    Intent it = new Intent(Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://github.com/Kiroha/byd-dashcast"));
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(it);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, R.string.main_nav_help, Toast.LENGTH_SHORT).show();
                }
            }
        });

        // TextureView: creates/destroys the mirror Surface
        clusterMirror.setOpaque(true);
        clusterMirror.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        clusterMirror.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                st.setDefaultBufferSize(w, h);
                mMirrorSurface = new Surface(st);
                attemptStartMirrorWithCurrentHolder();
            }
            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                st.setDefaultBufferSize(w, h);
                if (mMirrorSurface != null) { mMirrorSurface.release(); mMirrorSurface = null; }
                mMirrorSurface = new Surface(st);
                attemptStartMirrorWithCurrentHolder();
            }
            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                stopClusterMirror();
                if (mMirrorSurface != null) { mMirrorSurface.release(); mMirrorSurface = null; }
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
        });
        if (clusterMirror.isAvailable()) {
            mMirrorSurface = new Surface(clusterMirror.getSurfaceTexture());
        }

        // Touch on mirror → forward to cluster display
        clusterMirror.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                }
                forwardTouchFromMirror(v, event);
                return true;
            }
        });
        clusterMirrorScreenshot.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                }
                forwardTouchFromMirror(v, event);
                return true;
            }
        });

        // OTA check on fresh launch
        if (savedInstanceState == null) {
            UpdateChecker.checkUpdate(this, makeOtaProgressListener(this, false));
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShowMirrorIntent(intent);
    }

    private void handleShowMirrorIntent(Intent intent) {
        if (intent == null) return;
        if (FloatingRemoteButton.ACTION_SHOW_MIRROR.equals(intent.getAction())
                && mCurrentDashboardApp != null) {
            attemptStartMirrorWithCurrentHolder();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        AppLogger.lifecycle(getClass().getSimpleName(), "onStart");

        if (mDaemonBinder == null) tryGetDaemonBinderFromServiceManager();

        if (mServiceBound && mClusterService != null) {
            mClusterService.setListener(this);
            if (mCurrentDashboardApp != null) {
                attemptStartMirrorWithCurrentHolder();
                btnShowMirror.setVisibility(View.VISIBLE);
                FloatingRemoteButton.show();
            }
        } else if (!mBindRequested) {
            if (ClusterService.sIsRunning) {
                mBindRequested = true;
                tvDashboardStatus.setText(getString(R.string.status_starting_cluster));
                bindService(new Intent(this, ClusterService.class), mServiceConn, BIND_AUTO_CREATE);
            } else {
                boolean autoStart = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .getBoolean(SettingsActivity.PREF_BOOT_AUTO_START, false);
                if (autoStart) activateCluster();
            }
        }

        if (!mOsmAndBindRequested) {
            try {
                Intent osmAndIntent = new Intent("net.osmand.aidl.OsmAndAidlServiceV2");
                osmAndIntent.setPackage("net.osmand.plus");
                boolean bound = bindService(osmAndIntent, mOsmAndConnection, BIND_AUTO_CREATE);
                if (bound) mOsmAndBindRequested = true;
            } catch (Exception e) {
                AppLogger.w(TAG, "OsmAnd+ AIDL bind failed: " + e.getMessage());
            }
        }

        startStatePoll();
    }

    @Override
    protected void onStop() {
        super.onStop();
        AppLogger.lifecycle(getClass().getSimpleName(), "onStop");
        stopStatePoll();
        stopClusterMirror();
        if (mServiceBound && mClusterService != null) mClusterService.setListener(null);
        if (mOsmAndBindRequested) {
            try { unbindService(mOsmAndConnection); } catch (Exception ignored) {}
            mOsmAndAidl = null;
            mOsmAndBindRequested = false;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        AppLogger.lifecycle(getClass().getSimpleName(), "onDestroy");
        stopScreenshotLoop();
        mScreenshotHandler.removeCallbacksAndMessages(null);
        unregisterReceiver(mDaemonReadyReceiver);
        if (mServiceBound) {
            unbindService(mServiceConn);
            mServiceBound  = false;
            mBindRequested = false;
        }
        if (mOsmAndBindRequested) {
            try { unbindService(mOsmAndConnection); } catch (Exception ignored) {}
            mOsmAndAidl = null;
            mOsmAndBindRequested = false;
        }
        if (mMirrorSurface != null) {
            try { mMirrorSurface.release(); } catch (Exception ignored) {}
            mMirrorSurface = null;
        }
    }

    // ── ClusterService.Listener ───────────────────────────────────────────────

    @Override
    public void onClusterDisplayConnected(Display display, int displayId) {
        AppLogger.log(TAG, "Cluster display connected — displayId=" + displayId);
        if (mServiceBound && mClusterService != null) {
            mDashboardLauncher = mClusterService.getLauncher();
        }
        runOnUiThread(new Runnable() {
            @Override public void run() {
                cancelActivateTimeout();
                mWasManualActivation = false;
                btnActivateCluster.setEnabled(true);

                // Restore state if Activity was recreated
                if (mCurrentDashboardPkg == null) {
                    SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                    String pkg  = p.getString(PREF_CLUSTER_PKG,  null);
                    String name = p.getString(PREF_CLUSTER_NAME, null);
                    if (pkg != null) {
                        mCurrentDashboardPkg = pkg;
                        mCurrentDashboardApp = (name != null) ? name : pkg;
                    } else {
                        // Fresh connection: OsmAnd+ will auto-launch via ClusterService
                        mCurrentDashboardPkg = "net.osmand.plus";
                        mCurrentDashboardApp = "OsmAnd+";
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                                .putString(PREF_CLUSTER_PKG,  mCurrentDashboardPkg)
                                .putString(PREF_CLUSTER_NAME, mCurrentDashboardApp).apply();
                    }
                    mSessionClusterPackages.add(mCurrentDashboardPkg);
                    persistSessionClusterPackages();
                }

                updateDashboardStatus(mCurrentDashboardApp);
                attemptStartMirrorWithCurrentHolder();
            }
        });
    }

    @Override
    public void onClusterDisplayDisconnected() {
        AppLogger.log(TAG, "Cluster display disconnected");
        runOnUiThread(new Runnable() {
            @Override public void run() {
                cancelActivateTimeout();
                mWasManualActivation = false;
                mCurrentDashboardApp = null;
                mCurrentDashboardPkg = null;
                btnActivateCluster.setEnabled(true);
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .remove(PREF_CLUSTER_PKG).remove(PREF_CLUSTER_NAME).apply();
                setDashboardOffState();
            }
        });
    }

    // ── Mirror infrastructure ─────────────────────────────────────────────────

    private void startClusterMirror() {
        frameMirror.setAlpha(0f);
        frameMirror.animate().alpha(1f).setDuration(150).start();
        attemptStartMirrorWithCurrentHolder();
    }

    private void stopClusterMirror() {
        if (mServiceBound && mClusterService != null) {
            boolean wasActive = mClusterService.getMirrorManager().isMirrorActive();
            if (mDaemonBinder != null) {
                mClusterService.getMirrorManager().stopMirrorViaDaemon(mDaemonBinder);
            }
            mClusterService.getMirrorManager().stopMirror();
            if (wasActive) AppLogger.d(TAG, "stopClusterMirror OK");
        }
        stopScreenshotLoop();
    }

    private void attemptStartMirrorWithCurrentHolder() {
        if (!mServiceBound || mClusterService == null) return;
        if (mMirrorSurface == null || !mMirrorSurface.isValid()) return;

        if (mClusterService.getMirrorManager().isMirrorActive()) {
            clusterMirror.setVisibility(View.VISIBLE);
            stopScreenshotLoop();
            return;
        }

        int viewW = clusterMirror.getWidth();
        int viewH = clusterMirror.getHeight();
        if (viewW <= 0 || viewH <= 0) return;

        Display clusterDisplay = null;
        int displayId = mClusterService.getDisplayId();
        if (displayId >= 0) {
            DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
            if (dm != null) clusterDisplay = dm.getDisplay(displayId);
        }

        boolean mirrorOk = false;
        if (mDaemonBinder != null) {
            mirrorOk = mClusterService.getMirrorManager().startMirrorViaDaemon(
                    mDaemonBinder, clusterDisplay, mMirrorSurface, viewW, viewH);
        }
        if (!mirrorOk) {
            mirrorOk = mClusterService.getMirrorManager().startMirror(
                    clusterDisplay, mMirrorSurface, viewW, viewH);
        }

        if (mirrorOk) {
            clusterMirror.setVisibility(View.VISIBLE);
            clusterMirrorScreenshot.setVisibility(View.GONE);
            tvMirrorPlaceholder.setVisibility(View.GONE);
            stopScreenshotLoop();
        } else {
            clusterMirror.setVisibility(View.GONE);
            tvMirrorPlaceholder.setVisibility(View.GONE);
            startScreenshotLoop(displayId);
        }
    }

    private void startScreenshotLoop(final int displayId) {
        stopScreenshotLoop();
        clusterMirrorScreenshot.setVisibility(View.VISIBLE);
        final java.lang.ref.WeakReference<MainActivity> weakSelf = new java.lang.ref.WeakReference<>(this);
        mScreenshotRunnable = new Runnable() {
            @Override public void run() {
                AdbLocalClient.captureClusterDisplay(getApplicationContext(), displayId,
                        new AdbLocalClient.BitmapCallback() {
                    @Override public void onBitmap(final Bitmap bm) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                MainActivity self = weakSelf.get();
                                if (self != null && !self.isFinishing()
                                        && self.clusterMirrorScreenshot != null) {
                                    self.clusterMirrorScreenshot.setImageBitmap(bm);
                                }
                            }
                        });
                        Runnable r = mScreenshotRunnable;
                        if (r != null) mScreenshotHandler.postDelayed(r, SCREENSHOT_INTERVAL_MS);
                    }
                    @Override public void onError(String error) {
                        AppLogger.w(TAG, "screenshotLoop: " + error);
                        Runnable r = mScreenshotRunnable;
                        if (r != null) mScreenshotHandler.postDelayed(r, SCREENSHOT_INTERVAL_MS * 2L);
                    }
                });
            }
        };
        mScreenshotHandler.post(mScreenshotRunnable);
    }

    private void stopScreenshotLoop() {
        if (mScreenshotRunnable != null) {
            mScreenshotHandler.removeCallbacks(mScreenshotRunnable);
            mScreenshotRunnable = null;
        }
        if (clusterMirrorScreenshot != null) clusterMirrorScreenshot.setVisibility(View.GONE);
    }

    private void forwardTouchFromMirror(View mirrorView, MotionEvent event) {
        com.byd.dashcast.dashboard.ClusterInputForwarder forwarder = getInputForwarder();
        if (forwarder == null) return;
        com.byd.dashcast.dashboard.ClusterMirrorManager mirror =
                mServiceBound && mClusterService != null
                        ? mClusterService.getMirrorManager() : null;
        if (mirror == null) return;

        float scale   = mirror.getProjScale();
        if (scale <= 0f) return;
        float offsetX = mirror.getProjOffsetX();
        float offsetY = mirror.getProjOffsetY();
        int   clusterW = mirror.getClusterWidth();
        int   clusterH = mirror.getClusterHeight();
        if (clusterW <= 0 || clusterH <= 0) return;

        int pointerCount = event.getPointerCount();
        if (pointerCount <= 0) return;
        int[] pointerIds = new int[pointerCount];
        float[] clusterXs = new float[pointerCount];
        float[] clusterYs = new float[pointerCount];
        for (int i = 0; i < pointerCount; i++) {
            pointerIds[i] = event.getPointerId(i);
            float cx = (event.getX(i) - offsetX) / scale;
            float cy = (event.getY(i) - offsetY) / scale;
            clusterXs[i] = Math.max(0, Math.min(cx, clusterW - 1));
            clusterYs[i] = Math.max(0, Math.min(cy, clusterH - 1));
        }
        forwarder.forwardTouchFinalMulti(
                pointerIds, clusterXs, clusterYs,
                event.getActionMasked(), event.getActionIndex(), pointerCount);
    }

    // ── Fullscreen mirror mode ────────────────────────────────────────────────

    private void enterFullscreenMirror() {
        if (mIsFullscreenMirror) return;
        mIsFullscreenMirror = true;
        stopClusterMirror();

        if (vNavRail != null) vNavRail.setVisibility(View.GONE);
        if (llTopBar  != null) llTopBar.setVisibility(View.GONE);
        if (llRightPane != null) llRightPane.setVisibility(View.GONE);
        if (btnExitFullscreen != null) btnExitFullscreen.setVisibility(View.VISIBLE);

        try {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } catch (Throwable t) {
            AppLogger.w(TAG, "immersive: " + t.getMessage());
        }

        if (clusterMirror != null) {
            clusterMirror.postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        SurfaceTexture st = clusterMirror.getSurfaceTexture();
                        int w = clusterMirror.getWidth();
                        int h = clusterMirror.getHeight();
                        if (st == null || w <= 0 || h <= 0) return;
                        st.setDefaultBufferSize(w, h);
                        if (mMirrorSurface != null) { mMirrorSurface.release(); mMirrorSurface = null; }
                        mMirrorSurface = new Surface(st);
                        attemptStartMirrorWithCurrentHolder();
                    } catch (Throwable t) {
                        AppLogger.w(TAG, "fullscreen mirror restart: " + t.getMessage());
                    }
                }
            }, 250);
        }
        AppLogger.i(TAG, "enterFullscreenMirror");
    }

    private void exitFullscreenMirror() {
        if (!mIsFullscreenMirror) return;
        mIsFullscreenMirror = false;
        stopClusterMirror();

        if (vNavRail    != null) vNavRail.setVisibility(View.VISIBLE);
        if (llTopBar    != null) llTopBar.setVisibility(View.VISIBLE);
        if (llRightPane != null) llRightPane.setVisibility(View.VISIBLE);
        if (btnExitFullscreen != null) btnExitFullscreen.setVisibility(View.GONE);

        try {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        } catch (Throwable t) { /* ignore */ }

        if (clusterMirror != null) {
            clusterMirror.postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        SurfaceTexture st = clusterMirror.getSurfaceTexture();
                        int w = clusterMirror.getWidth();
                        int h = clusterMirror.getHeight();
                        if (st == null || w <= 0 || h <= 0) return;
                        st.setDefaultBufferSize(w, h);
                        if (mMirrorSurface != null) { mMirrorSurface.release(); mMirrorSurface = null; }
                        mMirrorSurface = new Surface(st);
                        attemptStartMirrorWithCurrentHolder();
                    } catch (Throwable t) { /* ignore */ }
                }
            }, 250);
        }
        AppLogger.i(TAG, "exitFullscreenMirror");
    }

    @Override
    public void onBackPressed() {
        if (mIsFullscreenMirror) { exitFullscreenMirror(); return; }
        super.onBackPressed();
    }

    // ── Cluster activation / restoration ─────────────────────────────────────

    private void activateCluster() {
        btnActivateCluster.setEnabled(false);
        tvDashboardStatus.setText(getString(R.string.status_activating_cluster));
        setStatusDot(DOT_COLOR_PENDING);
        mWasManualActivation = true;
        startActivateTimeout();

        String bootPkg = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(SettingsActivity.PREF_BOOT_DEFAULT_APP, "net.osmand.plus");

        if (!mServiceBound || mClusterService == null) {
            if (!mBindRequested) {
                mBindRequested = true;
                Intent svcIntent = new Intent(this, ClusterService.class);
                svcIntent.putExtra(ClusterService.EXTRA_AUTO_LAUNCH_PKG, bootPkg);
                startForegroundService(svcIntent);
                bindService(svcIntent, mServiceConn, BIND_AUTO_CREATE);
            }
            tvDashboardStatus.setText(getString(R.string.status_starting_cluster));
        } else {
            mClusterService.forceRestartProjection(bootPkg);
        }
    }

    private void restoreBydDashboard() {
        btnRestoreCluster.setEnabled(false);
        tvDashboardStatus.setText(getString(R.string.status_restoring_cluster));
        setStatusDot(DOT_COLOR_PENDING);

        final String capturedPkg = mCurrentDashboardPkg;
        mCurrentDashboardApp = null;
        mCurrentDashboardPkg = null;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .remove(PREF_CLUSTER_PKG).remove(PREF_CLUSTER_NAME).apply();

        moveSessionAppsToMainDisplay();

        AdbLocalClient.restoreBydOnCluster(this, capturedPkg, new AdbLocalClient.Callback() {
            @Override public void onSuccess(final String report) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (mServiceBound && mClusterService != null) {
                            mClusterService.stopProjectionNoAdb();
                        }
                        setDashboardOffState();
                        btnActivateCluster.setEnabled(true);
                        btnRestoreCluster.setEnabled(true);
                        AppLogger.log(TAG, "BYD restored ✓");
                    }
                });
            }
            @Override public void onError(final String error) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        btnRestoreCluster.setEnabled(true);
                        Toast.makeText(MainActivity.this,
                                getString(R.string.toast_restore_failed, error), Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void originCluster() {
        tvDashboardStatus.setText(getString(R.string.status_restoring_origin));
        setStatusDot(DOT_COLOR_PENDING);

        final String capturedPkg = mCurrentDashboardPkg;
        mCurrentDashboardApp = null;
        mCurrentDashboardPkg = null;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .remove(PREF_CLUSTER_PKG).remove(PREF_CLUSTER_NAME).apply();

        moveSessionAppsToMainDisplay();
        int clusterTypeCmd = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getInt(PREF_CLUSTER_TYPE, CLUSTER_TYPE_DEFAULT);

        AdbLocalClient.restoreOriginCluster(this, clusterTypeCmd, capturedPkg, new AdbLocalClient.Callback() {
            @Override public void onSuccess(final String report) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (mServiceBound && mClusterService != null) mClusterService.stopProjectionNoAdb();
                        updateDashboardStatus(null);
                        btnActivateCluster.setEnabled(true);
                        AppLogger.log(TAG, "Origin cluster restored ✓");
                    }
                });
            }
            @Override public void onError(final String error) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        Toast.makeText(MainActivity.this,
                                getString(R.string.toast_origin_failed, error), Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    // ── Status UI ─────────────────────────────────────────────────────────────

    private void updateDashboardStatus(String appName) {
        tvDashboardStatus.setTextColor(Color.WHITE);
        if (appName == null) {
            tvDashboardStatus.setText(getString(R.string.status_dashboard_byd));
            if (btnShowMirror != null) btnShowMirror.setVisibility(View.GONE);
            FloatingRemoteButton.hide();
        } else {
            tvDashboardStatus.setText(getString(R.string.status_dashboard_app, appName));
            if (btnShowMirror != null) btnShowMirror.setVisibility(View.VISIBLE);
            FloatingRemoteButton.show();
        }
        setStatusDot(DOT_COLOR_ACTIVE);
        btnRestoreCluster.setEnabled(true);
    }

    private void setDashboardOffState() {
        if (tvDashboardStatus == null) return;
        tvDashboardStatus.setTextColor(Color.WHITE);
        tvDashboardStatus.setText(getString(R.string.main_cluster_status_off));
        setStatusDot(DOT_COLOR_OFF);
        if (btnShowMirror != null) btnShowMirror.setVisibility(View.GONE);
        FloatingRemoteButton.hide();
    }

    private void setStatusDot(int color) {
        if (mStatusDotDrawable != null) mStatusDotDrawable.setColor(color);
    }

    // ── Session / cleanup ─────────────────────────────────────────────────────

    private void moveSessionAppsToMainDisplay() {
        if (mSessionClusterPackages.isEmpty()) return;
        if (!mServiceBound || mClusterService == null) return;
        for (String pkg : mSessionClusterPackages) {
            mClusterService.moveTaskToDisplay(pkg, 0, null);
        }
        mSessionClusterPackages.clear();
        persistSessionClusterPackages();
    }

    private void persistSessionClusterPackages() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putStringSet(PREF_SESSION_CLUSTER_PKGS,
                        new java.util.HashSet<>(mSessionClusterPackages))
                .apply();
    }

    static void cleanupDisplayAffinityAtBoot(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
        java.util.Set<String> pkgs = prefs.getStringSet(PREF_SESSION_CLUSTER_PKGS, null);
        if (pkgs == null || pkgs.isEmpty()) return;
        java.util.Set<String> remaining = new java.util.HashSet<>(pkgs);
        for (String pkg : pkgs) {
            if (moveTaskToDisplayZero(pkg)) remaining.remove(pkg);
        }
        if (remaining.isEmpty()) {
            prefs.edit().remove(PREF_SESSION_CLUSTER_PKGS).apply();
        } else {
            prefs.edit().putStringSet(PREF_SESSION_CLUSTER_PKGS, remaining).apply();
        }
    }

    private static boolean moveTaskToDisplayZero(String packageName) {
        try {
            Class<?> atmClass = Class.forName("android.app.ActivityTaskManager");
            Object iatm = atmClass.getMethod("getService").invoke(null);
            @SuppressWarnings("unchecked")
            java.util.List<?> tasks = (java.util.List<?>) iatm.getClass()
                    .getMethod("getTasks", int.class).invoke(iatm, 100);
            if (tasks == null) return false;
            for (Object taskInfo : tasks) {
                android.content.ComponentName base = (android.content.ComponentName)
                        taskInfo.getClass().getField("baseActivity").get(taskInfo);
                if (base != null && packageName.equals(base.getPackageName())) {
                    int taskId = taskInfo.getClass().getField("taskId").getInt(taskInfo);
                    iatm.getClass().getMethod("moveTaskToDisplay", int.class, int.class)
                            .invoke(iatm, taskId, 0);
                    return true;
                }
            }
            return true;
        } catch (Exception e) {
            AppLogger.w("DisplayCleanup", "moveTaskToDisplayZero " + packageName + ": " + e.getMessage());
            return false;
        }
    }

    private void clearClusterState() {
        mCurrentDashboardApp = null;
        mCurrentDashboardPkg = null;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .remove(PREF_CLUSTER_PKG).remove(PREF_CLUSTER_NAME).apply();
        updateDashboardStatus(null);
        stopClusterMirror();
    }

    // ── Display state polling ─────────────────────────────────────────────────

    private static final long STATE_POLL_INTERVAL_MS = 5_000;
    private Runnable mStatePollRunnable;

    private void startStatePoll() {
        if (mStatePollRunnable != null) return;
        mStatePollRunnable = new Runnable() {
            @Override public void run() {
                reconcileDisplayState();
                mScreenshotHandler.postDelayed(this, STATE_POLL_INTERVAL_MS);
            }
        };
        mScreenshotHandler.postDelayed(mStatePollRunnable, STATE_POLL_INTERVAL_MS);
    }

    private void stopStatePoll() {
        if (mStatePollRunnable != null) {
            mScreenshotHandler.removeCallbacks(mStatePollRunnable);
            mStatePollRunnable = null;
        }
    }

    private void reconcileDisplayState() {
        final String clusterPkg = mCurrentDashboardPkg;
        if (clusterPkg == null) return;
        AdbLocalClient.executeShellWithResult(this, "pidof " + clusterPkg,
                new AdbLocalClient.Callback() {
                    @Override public void onSuccess(String output) {
                        final boolean alive = output != null && !output.trim().isEmpty();
                        if (alive) return;
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                if (isFinishing() || isDestroyed()) return;
                                if (!clusterPkg.equals(mCurrentDashboardPkg)) return;
                                AppLogger.w(TAG, "state-poll: " + clusterPkg + " died → clearing");
                                clearClusterState();
                            }
                        });
                    }
                    @Override public void onError(String error) {
                        AppLogger.w(TAG, "state-poll pidof error: " + error);
                    }
                });
    }

    // ── Activate timeout ──────────────────────────────────────────────────────

    private void startActivateTimeout() {
        cancelActivateTimeout();
        mActivateTimeoutRunnable = new Runnable() {
            @Override public void run() {
                mActivateTimeoutRunnable = null;
                mWasManualActivation = false;
                btnActivateCluster.setEnabled(true);
                setStatusDot(DOT_COLOR_OFF);
                tvDashboardStatus.setText(getString(R.string.status_disconnected));
                Toast.makeText(MainActivity.this,
                        getString(R.string.toast_activate_timeout), Toast.LENGTH_LONG).show();
            }
        };
        mScreenshotHandler.postDelayed(mActivateTimeoutRunnable, ACTIVATE_TIMEOUT_MS);
    }

    private void cancelActivateTimeout() {
        if (mActivateTimeoutRunnable != null) {
            mScreenshotHandler.removeCallbacks(mActivateTimeoutRunnable);
            mActivateTimeoutRunnable = null;
        }
    }

    // ── Daemon Binder ─────────────────────────────────────────────────────────

    private void tryGetDaemonBinderFromServiceManager() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Class<?> smClass = Class.forName("android.os.ServiceManager");
                    java.lang.reflect.Method getService = smClass.getDeclaredMethod("getService", String.class);
                    getService.setAccessible(true);
                    IBinder binder = (IBinder) getService.invoke(null, "byd_mirror_daemon");
                    if (binder != null) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                mDaemonBinder = binder;
                                if (mServiceBound && mClusterService != null) {
                                    mClusterService.getInputForwarder().setDaemonBinder(binder);
                                }
                                if (mCurrentDashboardApp != null) attemptStartMirrorWithCurrentHolder();
                            }
                        });
                    }
                } catch (Exception e) {
                    AppLogger.w(TAG, "tryGetDaemonBinder: " + e.getMessage());
                }
            }
        }, "sm-daemon-lookup").start();
    }

    private com.byd.dashcast.dashboard.ClusterInputForwarder getInputForwarder() {
        if (mServiceBound && mClusterService != null) return mClusterService.getInputForwarder();
        return null;
    }

    // ── Overflow menu ─────────────────────────────────────────────────────────

    private void showOverflowMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 0, getString(R.string.menu_settings));
        popup.getMenu().add(0, 5, 1, getString(R.string.menu_language));
        popup.getMenu().add(0, 6, 2, getString(R.string.menu_check_updates));
        popup.getMenu().add(0, 8, 3, getString(R.string.btn_origin_cluster));
        popup.getMenu().add(1, 2, 4, getString(R.string.menu_diagnostic));
        popup.getMenu().add(1, 3, 5, getString(R.string.menu_system_report));
        popup.getMenu().add(1, 4, 6, getString(R.string.menu_log));
        try {
            popup.getMenu().getClass()
                    .getDeclaredMethod("setGroupDividerEnabled", boolean.class)
                    .invoke(popup.getMenu(), true);
        } catch (Exception ignored) {}
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case 1: startActivity(new Intent(MainActivity.this, SettingsActivity.class)); return true;
                    case 8: originCluster(); return true;
                    case 2: startActivity(new Intent(MainActivity.this, DiagActivity.class)); return true;
                    case 3: startActivity(new Intent(MainActivity.this, SysInfoActivity.class)); return true;
                    case 4: startActivity(new Intent(MainActivity.this, LogActivity.class)); return true;
                    case 5:
                        SharedPreferences p = getSharedPreferences(LocaleHelper.PREF_FILE, MODE_PRIVATE);
                        p.edit().remove(LocaleHelper.PREF_SETUP_DONE).apply();
                        Intent intent = new Intent(MainActivity.this, WelcomeActivity.class);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(intent);
                        return true;
                    case 6:
                        UpdateChecker.checkUpdate(MainActivity.this,
                                makeOtaProgressListener(MainActivity.this, true));
                        return true;
                }
                return false;
            }
        });
        popup.show();
    }

    // ── Stop projection sheet (long-press Restaurer) ──────────────────────────

    @android.annotation.SuppressLint("InflateParams")
    private void showStopProjectionSheet() {
        if (isFinishing() || isDestroyed()) return;
        final com.google.android.material.bottomsheet.BottomSheetDialog dialog =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        View v = getLayoutInflater().inflate(R.layout.dialog_stop_projection, null);
        dialog.setContentView(v);
        View rowOrigin = v.findViewById(R.id.sheet_action_origin_cluster);
        if (rowOrigin != null) {
            rowOrigin.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    dialog.dismiss();
                    originCluster();
                }
            });
        }
        dialog.show();
        try {
            com.google.android.material.bottomsheet.BottomSheetBehavior<?> b = dialog.getBehavior();
            b.setSkipCollapsed(true);
            b.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
        } catch (Throwable t) { /* ignore */ }
    }

    // ── OTA update dialog ─────────────────────────────────────────────────────

    public static UpdateChecker.ProgressListener makeOtaProgressListener(
            final android.app.Activity activity, final boolean notifyIfUpToDate) {
        final android.app.AlertDialog[] dlgHolder = {null};
        final ProgressBar[] pbHolder = {null};
        final TextView[]    pctHolder = {null};

        return new UpdateChecker.ProgressListener() {
            @Override
            public void onUpdateFound(final String version, final String changelog, final String downloadUrl) {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                LinearLayout layout = new LinearLayout(activity);
                layout.setOrientation(LinearLayout.VERTICAL);
                int pad = (int) (activity.getResources().getDisplayMetrics().density * 20);
                layout.setPadding(pad, pad, pad, pad / 2);

                TextView tvVersion = new TextView(activity);
                tvVersion.setText(activity.getString(R.string.ota_version_label, version));
                tvVersion.setTextSize(16);
                tvVersion.setPadding(pad, 0, pad, pad / 2);
                tvVersion.setTextColor(activity.getColor(R.color.text_accent));
                layout.addView(tvVersion);

                ScrollView sv = new ScrollView(activity);
                TextView tvChangelog = new TextView(activity);
                tvChangelog.setText(renderMarkdown(changelog));
                tvChangelog.setTextSize(13);
                tvChangelog.setPadding(pad, 0, pad, pad);
                tvChangelog.setTextColor(activity.getColor(R.color.text_primary));
                sv.addView(tvChangelog);
                LinearLayout.LayoutParams svParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (int) (activity.getResources().getDisplayMetrics().density * 250));
                layout.addView(sv, svParams);

                final LinearLayout progressLayout = new LinearLayout(activity);
                progressLayout.setOrientation(LinearLayout.VERTICAL);
                progressLayout.setPadding(pad, pad, pad, 0);
                progressLayout.setVisibility(View.GONE);
                ProgressBar pb = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
                pb.setMax(100);
                progressLayout.addView(pb);
                pbHolder[0] = pb;
                TextView tvPct = new TextView(activity);
                tvPct.setText(activity.getString(R.string.ota_progress_percent, 0));
                tvPct.setGravity(android.view.Gravity.CENTER);
                tvPct.setTextSize(12);
                tvPct.setTextColor(0xFF888888);
                progressLayout.addView(tvPct);
                pctHolder[0] = tvPct;
                layout.addView(progressLayout);

                dlgHolder[0] = new android.app.AlertDialog.Builder(activity)
                        .setTitle(activity.getString(R.string.ota_dialog_title))
                        .setView(layout)
                        .setCancelable(false)
                        .setPositiveButton(activity.getString(R.string.ota_btn_update_now), null)
                        .setNegativeButton(activity.getString(R.string.ota_btn_later),
                                (d, w) -> d.dismiss())
                        .create();
                dlgHolder[0].setOnShowListener(dialog -> {
                    Button posBtn = dlgHolder[0].getButton(android.app.AlertDialog.BUTTON_POSITIVE);
                    posBtn.setOnClickListener(vv -> {
                        posBtn.setEnabled(false);
                        dlgHolder[0].getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                        sv.setVisibility(View.GONE);
                        tvVersion.setText(activity.getString(R.string.ota_downloading));
                        progressLayout.setVisibility(View.VISIBLE);
                        UpdateChecker.startDownload(activity, downloadUrl, this);
                    });
                });
                dlgHolder[0].show();
            }

            @Override public void onDownloadProgress(int percent) {
                if (pbHolder[0] == null) return;
                if (percent < 0) {
                    pbHolder[0].setIndeterminate(true);
                    if (pctHolder[0] != null) pctHolder[0].setText(
                            activity.getString(R.string.ota_progress_unknown));
                } else {
                    pbHolder[0].setIndeterminate(false);
                    pbHolder[0].setProgress(percent);
                    if (pctHolder[0] != null) pctHolder[0].setText(
                            activity.getString(R.string.ota_progress_percent, percent));
                }
            }

            @Override public void onInstalling() {
                if (dlgHolder[0] != null) { dlgHolder[0].dismiss(); dlgHolder[0] = null; }
            }

            @Override public void onUpToDate() {
                if (notifyIfUpToDate) {
                    Toast.makeText(activity, activity.getString(R.string.ota_up_to_date),
                            Toast.LENGTH_SHORT).show();
                }
            }

            @Override public void onError(String message) {
                if (dlgHolder[0] != null) { dlgHolder[0].dismiss(); dlgHolder[0] = null; }
                AppLogger.e("OTA", "error: " + message);
            }
        };
    }

    // ── Markdown renderer (OTA changelog) ────────────────────────────────────

    private static CharSequence renderMarkdown(String raw) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        String[] lines = raw.split("\n");
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            boolean bold = false;
            float relSize = 0f;
            if (line.startsWith("## "))      { line = line.substring(3); bold = true; relSize = 1.15f; }
            else if (line.startsWith("### ")) { line = line.substring(4); bold = true; }
            else if (line.startsWith("- ") || line.startsWith("* ")) { line = "• " + line.substring(2); }
            int lineStart = sb.length();
            appendWithInlineBold(sb, line);
            int lineEnd = sb.length();
            if (bold)     sb.setSpan(new StyleSpan(Typeface.BOLD), lineStart, lineEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (relSize > 0f) sb.setSpan(new RelativeSizeSpan(relSize), lineStart, lineEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (li < lines.length - 1) sb.append('\n');
        }
        return sb;
    }

    private static void appendWithInlineBold(SpannableStringBuilder sb, String text) {
        int i = 0;
        while (i < text.length()) {
            int boldStart = text.indexOf("**", i);
            if (boldStart < 0) { sb.append(text.substring(i)); break; }
            sb.append(text.substring(i, boldStart));
            int boldEnd = text.indexOf("**", boldStart + 2);
            if (boldEnd < 0) { sb.append(text.substring(boldStart)); break; }
            int spanStart = sb.length();
            sb.append(text.substring(boldStart + 2, boldEnd));
            sb.setSpan(new StyleSpan(Typeface.BOLD), spanStart, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            i = boldEnd + 2;
        }
    }
}
