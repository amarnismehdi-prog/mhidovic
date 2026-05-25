package com.byd.dashcast;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.gms.maps.CameraPosition;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MapStyleOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ClusterMapActivity — 3D navigation map that runs on the BYD cluster display
 * (Display 1, 1920×720). Launched by ClusterService once the dashboard
 * virtual display is ready.
 *
 * Design targets:
 *  • 3D tilt camera (65°) locked behind the car cursor
 *  • Car cursor fixed at 78% from top of screen (camera focal offset via setPadding)
 *  • Dark night map style
 *  • HUD: turn icon + distance + street name + ETA
 *  • Speed badge bottom-right (from BYD vehicle API or GPS speed)
 */
public class ClusterMapActivity extends AppCompatActivity implements OnMapReadyCallback {

    private static final String TAG = "ClusterMapActivity";

    // Camera constants
    private static final float TILT    = 65f;
    private static final float ZOOM    = 17.5f;
    private static final int   CAM_MS  = 400;   // camera animation duration ms

    // Cursor offset: 78% from top means focal point is 56% below centre
    // setPadding(left, top, right, bottom) shifts the camera focal point
    private static final float CURSOR_FRAC = 0.78f;

    private MapView    mMapView;
    private GoogleMap  mMap;
    private Polyline   mPolyline;

    private View       mHudTop;
    private ImageView  mTurnIcon;
    private TextView   mTvDistance;
    private TextView   mTvStreet;
    private TextView   mTvEta;
    private TextView   mTvRemaining;
    private ImageView  mCursorCar;
    private TextView   mSpeedValue;
    private TextView   mArrivedBanner;

    private float  mCurrentBearing = 0f;
    private double mCurrentLat     = 0;
    private double mCurrentLng     = 0;
    private float  mCurrentSpeed   = 0f;

    private final BroadcastReceiver mNavReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            switch (action) {
                case NavigationEngine.ACTION_LOCATION:
                    handleLocation(intent);
                    break;
                case NavigationEngine.ACTION_ROUTE:
                    handleRoute(intent);
                    break;
                case NavigationEngine.ACTION_STEP:
                    handleStep(intent);
                    break;
                case NavigationEngine.ACTION_ARRIVED:
                    handleArrived();
                    break;
            }
        }
    };

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Immersive full-screen for cluster display
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

        mMapView      = findViewById(R.id.cluster_map_view);
        mHudTop       = findViewById(R.id.hud_top);
        mTurnIcon     = findViewById(R.id.hud_turn_icon);
        mTvDistance   = findViewById(R.id.hud_distance);
        mTvStreet     = findViewById(R.id.hud_street);
        mTvEta        = findViewById(R.id.hud_eta);
        mTvRemaining  = findViewById(R.id.hud_remaining);
        mCursorCar    = findViewById(R.id.cursor_car);
        mSpeedValue   = findViewById(R.id.speed_value);
        mArrivedBanner= findViewById(R.id.arrived_banner);

        mMapView.onCreate(savedInstanceState);
        mMapView.getMapAsync(this);

        // Register for navigation events
        IntentFilter filter = new IntentFilter();
        filter.addAction(NavigationEngine.ACTION_LOCATION);
        filter.addAction(NavigationEngine.ACTION_ROUTE);
        filter.addAction(NavigationEngine.ACTION_STEP);
        filter.addAction(NavigationEngine.ACTION_ARRIVED);
        LocalBroadcastManager.getInstance(this).registerReceiver(mNavReceiver, filter);

        // Start GPS via engine
        NavigationEngine.getInstance().startGps(this);

        Log.d(TAG, "ClusterMapActivity created");
    }

    @Override
    protected void onResume() {
        super.onResume();
        mMapView.onResume();
    }

    @Override
    protected void onPause() {
        mMapView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mNavReceiver);
        mMapView.onDestroy();
        super.onDestroy();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        mMapView.onLowMemory();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        mMapView.onSaveInstanceState(out);
    }

    // ─── Map ready ────────────────────────────────────────────────────────────

    @Override
    public void onMapReady(GoogleMap map) {
        mMap = map;

        // Dark night style
        try {
            MapStyleOptions style = MapStyleOptions.loadRawResourceStyle(this, R.raw.map_style_night);
            mMap.setMapStyle(style);
        } catch (Exception e) {
            Log.w(TAG, "Map style load failed", e);
        }

        // Disable all UI controls — clean cluster look
        mMap.getUiSettings().setAllGesturesEnabled(false);
        mMap.getUiSettings().setCompassEnabled(false);
        mMap.getUiSettings().setMapToolbarEnabled(false);
        mMap.getUiSettings().setMyLocationButtonEnabled(false);
        mMap.getUiSettings().setZoomControlsEnabled(false);

        // Map background colour matches night style
        mMap.setBackgroundColor(Color.parseColor("#1a1a2e"));

        // Camera padding: shift focal point so the cursor sits at 78% from top.
        // getPadding top = height × (CURSOR_FRAC - 0.5) × 2
        // We set it after layout so height is known.
        mMapView.post(() -> {
            int h = mMapView.getHeight();
            int topPad = (int)(h * (CURSOR_FRAC - 0.5f) * 2);
            mMap.setPadding(0, topPad, 0, 0);

            // Position cursor ImageView at 78% from top, centred horizontally
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mCursorCar.getLayoutParams();
            lp.topMargin = (int)(h * CURSOR_FRAC) - mCursorCar.getHeight() / 2;
            mCursorCar.setLayoutParams(lp);

            // Initial camera at current location if available
            android.location.Location loc = NavigationEngine.getInstance().getCurrentLocation();
            if (loc != null) {
                mCurrentLat = loc.getLatitude();
                mCurrentLng = loc.getLongitude();
                mCurrentBearing = loc.getBearing();
                animateCamera(false);
            }
        });

        Log.d(TAG, "Map ready");
    }

    // ─── Navigation event handlers ────────────────────────────────────────────

    private void handleLocation(Intent i) {
        mCurrentLat     = i.getDoubleExtra(NavigationEngine.EXTRA_LAT, mCurrentLat);
        mCurrentLng     = i.getDoubleExtra(NavigationEngine.EXTRA_LNG, mCurrentLng);
        mCurrentBearing = i.getFloatExtra(NavigationEngine.EXTRA_BEARING, mCurrentBearing);
        mCurrentSpeed   = i.getFloatExtra(NavigationEngine.EXTRA_SPEED_KMH, 0f);

        mSpeedValue.setText(String.format(Locale.getDefault(), "%d", (int) mCurrentSpeed));

        if (mMap != null) animateCamera(true);
    }

    private void handleRoute(Intent i) {
        if (mMap == null) return;
        ArrayList<LatLng> pts = i.getParcelableArrayListExtra(NavigationEngine.EXTRA_POLYLINE);
        if (pts == null || pts.isEmpty()) return;

        // Remove old polyline
        if (mPolyline != null) mPolyline.remove();

        mPolyline = mMap.addPolyline(new PolylineOptions()
                .addAll(pts)
                .color(Color.parseColor("#4488FF"))
                .width(14f)
                .geodesic(true));

        int etaSec = i.getIntExtra(NavigationEngine.EXTRA_ETA_SECONDS, 0);
        int distM  = i.getIntExtra(NavigationEngine.EXTRA_TOTAL_DIST_M, 0);
        mTvEta.setText(NavigationEngine.formatDuration(etaSec));
        mTvRemaining.setText(NavigationEngine.formatDistance(distM));
        mHudTop.setVisibility(View.VISIBLE);
    }

    private void handleStep(Intent i) {
        String text   = i.getStringExtra(NavigationEngine.EXTRA_STEP_TEXT);
        int    distM  = i.getIntExtra(NavigationEngine.EXTRA_STEP_DIST_M, 0);
        int    etaSec = i.getIntExtra(NavigationEngine.EXTRA_ETA_SECONDS, 0);
        int    totM   = i.getIntExtra(NavigationEngine.EXTRA_TOTAL_DIST_M, 0);
        String maneuv = i.getStringExtra(NavigationEngine.EXTRA_TURN_TYPE);

        mTvDistance.setText(NavigationEngine.formatDistance(distM));
        mTvStreet.setText(text != null ? text : "");
        mTvEta.setText(NavigationEngine.formatDuration(etaSec));
        mTvRemaining.setText(NavigationEngine.formatDistance(totM));
        mHudTop.setVisibility(View.VISIBLE);

        // Turn icon
        if (maneuv != null) {
            if (maneuv.contains("left")) {
                mTurnIcon.setImageResource(R.drawable.ic_turn_left);
            } else if (maneuv.contains("right")) {
                mTurnIcon.setImageResource(R.drawable.ic_turn_right);
            } else {
                mTurnIcon.setImageResource(R.drawable.ic_turn_straight);
            }
        }
    }

    private void handleArrived() {
        mHudTop.setVisibility(View.GONE);
        mArrivedBanner.setVisibility(View.VISIBLE);
        mArrivedBanner.postDelayed(() -> mArrivedBanner.setVisibility(View.GONE), 8000);
    }

    // ─── Camera ───────────────────────────────────────────────────────────────

    private void animateCamera(boolean animate) {
        if (mMap == null || (mCurrentLat == 0 && mCurrentLng == 0)) return;

        CameraPosition pos = new CameraPosition.Builder()
                .target(new LatLng(mCurrentLat, mCurrentLng))
                .zoom(ZOOM)
                .tilt(TILT)
                .bearing(mCurrentBearing)
                .build();

        if (animate) {
            mMap.animateCamera(CameraUpdateFactory.newCameraPosition(pos), CAM_MS, null);
        } else {
            mMap.moveCamera(CameraUpdateFactory.newCameraPosition(pos));
        }
    }
}
