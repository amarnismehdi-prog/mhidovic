package com.byd.dashcast;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MapStyleOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * ClusterMapActivity — 3D navigation on the BYD Seal U cluster (Display 1, 1920×720).
 *
 * Visual design matches BYD reference:
 *   - Light / day map style (cream/beige roads)
 *   - Yellow route line
 *   - Blue solid chevron cursor at 55% from screen top
 *   - Top bar: clock | EV | gear | ECO | AW | temp
 *   - Bottom bar: kW | battery% | OK | range | speed km/h | TRIP B
 */
public class ClusterMapActivity extends AppCompatActivity implements OnMapReadyCallback {

    private static final String TAG = "ClusterMap";

    // Camera
    private static final float TILT        = 60f;
    private static final float ZOOM        = 18f;
    private static final int   CAM_MS      = 350;
    private static final float CURSOR_FRAC = 0.55f;  // 55% from top, matches reference

    // Route colour: yellow like reference image
    private static final int ROUTE_COLOR = Color.parseColor("#F5C518");
    private static final float ROUTE_WIDTH = 18f;

    // Views — map
    private MapView  mMapView;
    private GoogleMap mMap;
    private Polyline  mPolyline;

    // Views — top bar
    private TextView mTvClock;
    private TextView mTvGear;
    private TextView mTvTemp;
    private TextView mTvEvMode;
    private TextView mTvDriveMode;

    // Views — speed limit
    private View     mSpeedLimitBadge;
    private TextView mTvSpeedLimit;

    // Views — turn strip
    private View      mTurnStrip;
    private ImageView mTurnIcon;
    private TextView  mTvDistance;
    private TextView  mTvStreet;

    // Views — cursor
    private ImageView mCursorCar;

    // Views — arrived
    private TextView mArrivedBanner;

    // Views — bottom bar
    private TextView    mTvPowerKw;
    private TextView    mTvBatteryPct;
    private ProgressBar mBatteryBar;
    private TextView    mTvBatteryStatus;
    private TextView    mTvRangeKm;
    private TextView    mTvSpeed;
    private TextView    mTvTrip;

    // State
    private float  mHeading  = 0f;
    private double mLat      = 0;
    private double mLng      = 0;
    private float  mSpeedKmh = 0f;
    private double mTripKm   = 0;

    // Clock updater
    private final Handler mClockHandler = new Handler(Looper.getMainLooper());
    private final Runnable mClockTick   = new Runnable() {
        @Override public void run() {
            updateClock();
            mClockHandler.postDelayed(this, 10_000); // update every 10s is enough
        }
    };

    // Nav broadcast
    private final BroadcastReceiver mNavReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            switch (action) {
                case NavigationEngine.ACTION_LOCATION: handleLocation(intent);  break;
                case NavigationEngine.ACTION_ROUTE:    handleRoute(intent);     break;
                case NavigationEngine.ACTION_STEP:     handleStep(intent);      break;
                case NavigationEngine.ACTION_ARRIVED:  handleArrived();         break;
            }
        }
    };

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        setContentView(R.layout.activity_cluster_map);

        // Top bar
        mTvClock     = findViewById(R.id.hud_clock);
        mTvGear      = findViewById(R.id.hud_gear);
        mTvTemp      = findViewById(R.id.hud_temperature);
        mTvEvMode    = findViewById(R.id.hud_ev_mode);
        mTvDriveMode = findViewById(R.id.hud_drive_mode);

        // Speed limit
        mSpeedLimitBadge = findViewById(R.id.speed_limit_badge);
        mTvSpeedLimit    = findViewById(R.id.hud_speed_limit);

        // Turn strip
        mTurnStrip  = findViewById(R.id.hud_turn_strip);
        mTurnIcon   = findViewById(R.id.hud_turn_icon);
        mTvDistance = findViewById(R.id.hud_distance);
        mTvStreet   = findViewById(R.id.hud_street);

        // Cursor + arrived
        mCursorCar     = findViewById(R.id.cursor_car);
        mArrivedBanner = findViewById(R.id.arrived_banner);

        // Bottom bar
        mTvPowerKw     = findViewById(R.id.hud_power_kw);
        mTvBatteryPct  = findViewById(R.id.hud_battery_pct);
        mBatteryBar    = findViewById(R.id.hud_battery_bar);
        mTvBatteryStatus= findViewById(R.id.hud_battery_status);
        mTvRangeKm     = findViewById(R.id.hud_range_km);
        mTvSpeed       = findViewById(R.id.hud_speed);
        mTvTrip        = findViewById(R.id.hud_trip);

        // Map
        mMapView = findViewById(R.id.cluster_map_view);
        mMapView.onCreate(savedInstanceState);
        mMapView.getMapAsync(this);

        // Start clock
        mClockHandler.post(mClockTick);

        // Broadcast receiver
        IntentFilter f = new IntentFilter();
        f.addAction(NavigationEngine.ACTION_LOCATION);
        f.addAction(NavigationEngine.ACTION_ROUTE);
        f.addAction(NavigationEngine.ACTION_STEP);
        f.addAction(NavigationEngine.ACTION_ARRIVED);
        LocalBroadcastManager.getInstance(this).registerReceiver(mNavReceiver, f);

        NavigationEngine.getInstance().startGps(this);
        Log.d(TAG, "ClusterMapActivity created");
    }

    @Override protected void onResume()  { super.onResume();  mMapView.onResume(); }
    @Override protected void onPause()   { mMapView.onPause(); super.onPause(); }
    @Override
    protected void onDestroy() {
        mClockHandler.removeCallbacks(mClockTick);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mNavReceiver);
        mMapView.onDestroy();
        super.onDestroy();
    }
    @Override public void onLowMemory() { super.onLowMemory(); mMapView.onLowMemory(); }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        mMapView.onSaveInstanceState(out);
    }

    // ─── Map ready ────────────────────────────────────────────────────────────

    @Override
    public void onMapReady(GoogleMap map) {
        mMap = map;

        // Light day style matching reference
        try {
            mMap.setMapStyle(MapStyleOptions.loadRawResourceStyle(this, R.raw.map_style_day));
        } catch (Exception e) {
            Log.w(TAG, "day map style load failed", e);
        }

        // Clean cluster look — no controls
        mMap.getUiSettings().setAllGesturesEnabled(false);
        mMap.getUiSettings().setCompassEnabled(false);
        mMap.getUiSettings().setMapToolbarEnabled(false);
        mMap.getUiSettings().setMyLocationButtonEnabled(false);
        mMap.getUiSettings().setZoomControlsEnabled(false);

        // Shift focal point: cursor at 55% from top
        // setPadding(left, top, right, bottom): positive top shifts focal point down
        mMapView.post(() -> {
            int h = mMapView.getHeight();
            // topPad > 0 moves the map focal point toward cursor position
            int topPad = (int)(h * (CURSOR_FRAC - 0.5f) * 2);
            mMap.setPadding(0, topPad, 0, 0);

            // Position cursor ImageView
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mCursorCar.getLayoutParams();
            lp.topMargin = (int)(h * CURSOR_FRAC) - mCursorCar.getHeight() / 2;
            mCursorCar.setLayoutParams(lp);

            // Initial camera position
            android.location.Location loc = NavigationEngine.getInstance().getCurrentLocation();
            if (loc != null) {
                mLat = loc.getLatitude(); mLng = loc.getLongitude();
                mHeading = loc.getBearing();
            }
            animateCamera(false);
        });

        Log.d(TAG, "Map ready (day style)");
    }

    // ─── Nav events ───────────────────────────────────────────────────────────

    private void handleLocation(Intent i) {
        mLat      = i.getDoubleExtra(NavigationEngine.EXTRA_LAT, mLat);
        mLng      = i.getDoubleExtra(NavigationEngine.EXTRA_LNG, mLng);
        mHeading  = i.getFloatExtra(NavigationEngine.EXTRA_BEARING, mHeading);
        mSpeedKmh = i.getFloatExtra(NavigationEngine.EXTRA_SPEED_KMH, 0f);

        // Accumulate trip distance (rough: metres between fixes at 1Hz)
        // More accurate trip tracking would use the distance from the route step.

        mTvSpeed.setText(String.format(Locale.getDefault(), "%d", (int) mSpeedKmh));

        if (mMap != null) animateCamera(true);
    }

    private void handleRoute(Intent i) {
        if (mMap == null) return;
        ArrayList<LatLng> pts = i.getParcelableArrayListExtra(NavigationEngine.EXTRA_POLYLINE);
        if (pts == null || pts.isEmpty()) return;

        if (mPolyline != null) mPolyline.remove();
        mPolyline = mMap.addPolyline(new PolylineOptions()
                .addAll(pts)
                .color(ROUTE_COLOR)
                .width(ROUTE_WIDTH)
                .geodesic(true));
    }

    private void handleStep(Intent i) {
        String text   = i.getStringExtra(NavigationEngine.EXTRA_STEP_TEXT);
        int    distM  = i.getIntExtra(NavigationEngine.EXTRA_STEP_DIST_M, 0);
        String maneuv = i.getStringExtra(NavigationEngine.EXTRA_TURN_TYPE);

        mTvDistance.setText(NavigationEngine.formatDistance(distM));
        mTvStreet.setText(text != null ? text : "");
        mTurnStrip.setVisibility(View.VISIBLE);

        if (maneuv != null) {
            if      (maneuv.contains("left"))  mTurnIcon.setImageResource(R.drawable.ic_turn_left);
            else if (maneuv.contains("right")) mTurnIcon.setImageResource(R.drawable.ic_turn_right);
            else                               mTurnIcon.setImageResource(R.drawable.ic_turn_straight);
        }
    }

    private void handleArrived() {
        mTurnStrip.setVisibility(View.GONE);
        mArrivedBanner.setVisibility(View.VISIBLE);
        mArrivedBanner.postDelayed(() -> mArrivedBanner.setVisibility(View.GONE), 8000);
    }

    // ─── Camera ───────────────────────────────────────────────────────────────

    private void animateCamera(boolean animate) {
        if (mMap == null || (mLat == 0 && mLng == 0)) return;
        CameraPosition pos = new CameraPosition.Builder()
                .target(new LatLng(mLat, mLng))
                .zoom(ZOOM)
                .tilt(TILT)
                .bearing(mHeading)
                .build();
        if (animate)
            mMap.animateCamera(CameraUpdateFactory.newCameraPosition(pos), CAM_MS, null);
        else
            mMap.moveCamera(CameraUpdateFactory.newCameraPosition(pos));
    }

    // ─── Clock ────────────────────────────────────────────────────────────────

    private void updateClock() {
        String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        if (mTvClock != null) mTvClock.setText(time);
    }

    // ─── Public API — called by BYD vehicle data bridge ───────────────────────

    /**
     * Update vehicle data from BYD auto API. Call from a polling thread.
     * All params: -1 = unknown / unavailable.
     *
     * @param batteryPct   0-100
     * @param rangeKm      remaining range in km
     * @param powerKw      motor power in kW (negative = regen)
     * @param gear         0=P, 1=R, 2=N, 3=D
     * @param speedKmh     from vehicle speed sensor (more accurate than GPS)
     * @param tempC        outside temperature in °C
     */
    public void updateVehicleData(int batteryPct, int rangeKm, int powerKw,
                                   int gear, int speedKmh, int tempC) {
        runOnUiThread(() -> {
            if (batteryPct >= 0) {
                mTvBatteryPct.setText(String.valueOf(batteryPct));
                mBatteryBar.setProgress(batteryPct);
            }
            if (rangeKm >= 0) mTvRangeKm.setText(String.valueOf(rangeKm));
            if (powerKw != Integer.MIN_VALUE)
                mTvPowerKw.setText(String.valueOf(Math.abs(powerKw)));
            if (speedKmh >= 0) {
                mTvSpeed.setText(String.valueOf(speedKmh));
                mSpeedKmh = speedKmh;
            }
            if (tempC != Integer.MIN_VALUE) mTvTemp.setText(tempC + "°c");

            String gearStr;
            switch (gear) {
                case 0: gearStr = "P"; break;
                case 1: gearStr = "R"; break;
                case 2: gearStr = "N"; break;
                case 3: gearStr = "D"; break;
                default: gearStr = mTvGear.getText().toString(); break;
            }
            mTvGear.setText(gearStr);
        });
    }

    /** Show or hide speed limit badge. Pass 0 to hide. */
    public void setSpeedLimit(int limitKmh) {
        runOnUiThread(() -> {
            if (limitKmh > 0) {
                mTvSpeedLimit.setText(String.valueOf(limitKmh));
                mSpeedLimitBadge.setVisibility(View.VISIBLE);
            } else {
                mSpeedLimitBadge.setVisibility(View.GONE);
            }
        });
    }

    /** Update trip B meter. */
    public void setTripKm(double tripKm) {
        mTripKm = tripKm;
        runOnUiThread(() ->
            mTvTrip.setText(String.format(Locale.getDefault(), "%.1f km", tripKm)));
    }
}
