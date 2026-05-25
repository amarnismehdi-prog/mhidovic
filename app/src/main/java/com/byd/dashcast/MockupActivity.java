package com.byd.dashcast;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MapStyleOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;

import java.util.ArrayList;
import java.util.Locale;

/**
 * MockupActivity — shows the cluster map preview on the main 15" display.
 * Renders the same 3D map in a fixed 8:3 aspect-ratio frame so the user
 * can verify how navigation looks before deploying to the car.
 */
public class MockupActivity extends AppCompatActivity implements OnMapReadyCallback {

    private static final String TAG = "MockupActivity";

    private static final float TILT   = 65f;
    private static final float ZOOM   = 17.5f;
    private static final int   CAM_MS = 400;
    private static final float CURSOR_FRAC = 0.55f;

    private MapView    mMapView;
    private GoogleMap  mMap;
    private Polyline   mPolyline;

    private View      mHud;
    private ImageView mTurnIcon;
    private TextView  mTvDistance;
    private TextView  mTvStreet;
    private TextView  mTvEta;
    private ImageView mCursor;
    private TextView  mSpeedValue;

    private float  mBearing = 0f;
    private double mLat     = 0;
    private double mLng     = 0;

    private final BroadcastReceiver mNavReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            switch (action) {
                case NavigationEngine.ACTION_LOCATION: onLocation(intent);  break;
                case NavigationEngine.ACTION_ROUTE:    onRoute(intent);     break;
                case NavigationEngine.ACTION_STEP:     onStep(intent);      break;
                case NavigationEngine.ACTION_ARRIVED:  onArrived();         break;
            }
        }
    };

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mockup);

        mMapView    = findViewById(R.id.mockup_map_view);
        mHud        = findViewById(R.id.mockup_hud);
        mTurnIcon   = findViewById(R.id.mockup_turn_icon);
        mTvDistance = findViewById(R.id.mockup_distance);
        mTvStreet   = findViewById(R.id.mockup_street);
        mTvEta      = findViewById(R.id.mockup_eta);
        mCursor     = findViewById(R.id.mockup_cursor);
        mSpeedValue = findViewById(R.id.mockup_speed);

        // Enforce 8:3 aspect ratio for the map container
        FrameLayout container = findViewById(R.id.mockup_map_container);
        container.post(() -> {
            int w = container.getWidth();
            int h = w * 3 / 8;
            ViewGroup.LayoutParams lp = container.getLayoutParams();
            lp.height = h;
            container.setLayoutParams(lp);
        });

        mMapView.onCreate(savedInstanceState);
        mMapView.getMapAsync(this);

        findViewById(R.id.btn_close_mockup).setOnClickListener(v -> finish());

        // Register for live nav events
        IntentFilter f = new IntentFilter();
        f.addAction(NavigationEngine.ACTION_LOCATION);
        f.addAction(NavigationEngine.ACTION_ROUTE);
        f.addAction(NavigationEngine.ACTION_STEP);
        f.addAction(NavigationEngine.ACTION_ARRIVED);
        LocalBroadcastManager.getInstance(this).registerReceiver(mNavReceiver, f);

        // Ensure GPS is running
        NavigationEngine.getInstance().startGps(this);
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

        try {
            mMap.setMapStyle(MapStyleOptions.loadRawResourceStyle(this, R.raw.map_style_day));
        } catch (Exception e) { /* ignore */ }

        mMap.getUiSettings().setAllGesturesEnabled(true);  // allow manual pan in mockup
        mMap.getUiSettings().setCompassEnabled(false);
        mMap.getUiSettings().setMapToolbarEnabled(false);
        mMap.getUiSettings().setZoomControlsEnabled(false);

        mMapView.post(() -> {
            int h = mMapView.getHeight();
            int topPad = (int)(h * (CURSOR_FRAC - 0.5f) * 2);
            mMap.setPadding(0, topPad, 0, 0);

            // Position cursor at 78% from top
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mCursor.getLayoutParams();
            lp.topMargin = (int)(h * CURSOR_FRAC) - mCursor.getHeight() / 2;
            mCursor.setLayoutParams(lp);

            // Position from live GPS if available
            android.location.Location loc = NavigationEngine.getInstance().getCurrentLocation();
            if (loc != null) {
                mLat = loc.getLatitude();
                mLng = loc.getLongitude();
                mBearing = loc.getBearing();
                animateCamera(false);
            } else {
                // Default to Casablanca if no GPS (Morocco locale)
                mLat = 33.5731; mLng = -7.5898;
                animateCamera(false);
            }
        });
    }

    // ─── Nav event handlers ───────────────────────────────────────────────────

    private void onLocation(Intent i) {
        mLat     = i.getDoubleExtra(NavigationEngine.EXTRA_LAT, mLat);
        mLng     = i.getDoubleExtra(NavigationEngine.EXTRA_LNG, mLng);
        mBearing = i.getFloatExtra(NavigationEngine.EXTRA_BEARING, mBearing);
        float speed = i.getFloatExtra(NavigationEngine.EXTRA_SPEED_KMH, 0f);
        mSpeedValue.setText(String.format(Locale.getDefault(), "%d", (int) speed));
        if (mMap != null) animateCamera(true);
    }

    private void onRoute(Intent i) {
        if (mMap == null) return;
        ArrayList<LatLng> pts = i.getParcelableArrayListExtra(NavigationEngine.EXTRA_POLYLINE);
        if (pts == null || pts.isEmpty()) return;
        if (mPolyline != null) mPolyline.remove();
        mPolyline = mMap.addPolyline(new PolylineOptions()
                .addAll(pts)
                .color(Color.parseColor("#F5C518"))
                .width(12f)
                .geodesic(true));
        int etaSec = i.getIntExtra(NavigationEngine.EXTRA_ETA_SECONDS, 0);
        mTvEta.setText(NavigationEngine.formatDuration(etaSec));
        mHud.setVisibility(View.VISIBLE);
    }

    private void onStep(Intent i) {
        String text   = i.getStringExtra(NavigationEngine.EXTRA_STEP_TEXT);
        int    distM  = i.getIntExtra(NavigationEngine.EXTRA_STEP_DIST_M, 0);
        int    etaSec = i.getIntExtra(NavigationEngine.EXTRA_ETA_SECONDS, 0);
        String maneuv = i.getStringExtra(NavigationEngine.EXTRA_TURN_TYPE);

        mTvDistance.setText(NavigationEngine.formatDistance(distM));
        mTvStreet.setText(text != null ? text : "");
        mTvEta.setText(NavigationEngine.formatDuration(etaSec));
        mHud.setVisibility(View.VISIBLE);

        if (maneuv != null) {
            if (maneuv.contains("left")) mTurnIcon.setImageResource(R.drawable.ic_turn_left);
            else if (maneuv.contains("right")) mTurnIcon.setImageResource(R.drawable.ic_turn_right);
            else mTurnIcon.setImageResource(R.drawable.ic_turn_straight);
        }
    }

    private void onArrived() {
        mHud.setVisibility(View.GONE);
    }

    // ─── Camera ───────────────────────────────────────────────────────────────

    private void animateCamera(boolean animate) {
        if (mMap == null || (mLat == 0 && mLng == 0)) return;
        CameraPosition pos = new CameraPosition.Builder()
                .target(new LatLng(mLat, mLng))
                .zoom(ZOOM).tilt(TILT).bearing(mBearing)
                .build();
        if (animate) mMap.animateCamera(CameraUpdateFactory.newCameraPosition(pos), CAM_MS, null);
        else         mMap.moveCamera(CameraUpdateFactory.newCameraPosition(pos));
    }
}
