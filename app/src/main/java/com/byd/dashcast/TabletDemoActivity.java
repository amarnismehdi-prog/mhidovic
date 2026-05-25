package com.byd.dashcast;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MapStyleOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.libraries.places.api.Places;
import com.google.android.libraries.places.api.model.AutocompletePrediction;
import com.google.android.libraries.places.api.model.Place;
import com.google.android.libraries.places.api.net.FetchPlaceRequest;
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest;
import com.google.android.libraries.places.api.net.PlacesClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * TabletDemoActivity — standalone tablet preview of the NavCast in-car experience.
 *
 * Left pane:  3D cluster simulation map (8:3 aspect ratio, same style/camera as car).
 * Right pane: Places autocomplete search + ETA card + navigation controls.
 *
 * No BYD-specific APIs needed. Works on any Android 10+ device.
 *
 * Without a Google Maps API key: shows a "For Development" watermark but still
 * renders the 3D view + demo destinations. Add your key to
 * res/values/google_maps_api.xml to unlock live maps and Places search.
 */
@SuppressLint("SetTextI18n")
public class TabletDemoActivity extends AppCompatActivity implements OnMapReadyCallback {

    private static final String TAG = "TabletDemo";

    private static final float TILT        = 65f;
    private static final float ZOOM        = 17.5f;
    private static final int   CAM_MS      = 400;
    private static final float CURSOR_FRAC = 0.55f;

    // ─── Unified suggestion model ──────────────────────────────────────────────

    /** One suggestion item — either from Places (placeId != null) or a hardcoded demo spot. */
    private static class Suggestion {
        final String  label;
        final String  placeId;   // non-null → fetch LatLng via Places SDK
        final LatLng  knownLatLng; // non-null → navigate directly (demo fallback)
        Suggestion(String label, String placeId) {
            this.label = label; this.placeId = placeId; this.knownLatLng = null;
        }
        Suggestion(String label, double lat, double lng) {
            this.label = label; this.placeId = null;
            this.knownLatLng = new LatLng(lat, lng);
        }
    }

    // Demo destinations for Morocco (no API key required)
    private static final Suggestion[] DEMO_SPOTS = {
        new Suggestion("Casablanca — Place Mohammed V",     33.5898, -7.6037),
        new Suggestion("Casablanca — Gare Casa Port",      33.5972, -7.6222),
        new Suggestion("Casablanca — Aéroport Mohammed V", 33.3675, -7.5898),
        new Suggestion("Rabat — Avenue Mohammed V",        34.0139, -6.8326),
        new Suggestion("Marrakech — Place Jemaa el-Fna",  31.6258, -7.9892),
        new Suggestion("Tanger — Médina",                  35.7870, -5.8021),
        new Suggestion("Fès — Bab Bou Jeloud",             34.0643, -4.9769),
        new Suggestion("Agadir — Promenade de la plage",   30.4197, -9.5985),
    };

    private final List<Suggestion> mItems = new ArrayList<>();

    // ─── Views ────────────────────────────────────────────────────────────────

    private MapView   mMapView;
    private GoogleMap mMap;
    private Polyline  mPolyline;

    private View      mHud;
    private ImageView mTurnIcon;
    private TextView  mTvDistance;
    private TextView  mTvStreet;
    private TextView  mTvEtaHud;
    private ImageView mCursor;
    private TextView  mSpeedValue;
    private TextView  mArrivedBanner;
    private View      mGpsDot;
    private TextView  mTvGpsStatus;
    private TextView  mTvCoords;

    private com.google.android.material.textfield.TextInputEditText mEtAddress;
    private RecyclerView mRvSuggestions;
    private View         mCardEta;
    private TextView     mTvEtaDest;
    private TextView     mTvEtaStep;
    private TextView     mTvEtaTime;
    private TextView     mTvEtaDist;
    private View         mBtnGo;
    private View         mBtnStop;
    private TextView     mNavSubtitle;

    // ─── State ────────────────────────────────────────────────────────────────

    private PlacesClient mPlacesClient;
    private LatLng       mSelectedLatLng;
    private String       mSelectedName;
    private float        mBearing = 0f;
    private double       mLat     = 0;
    private double       mLng     = 0;

    // ─── NavEngine receiver ────────────────────────────────────────────────────

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
        setContentView(R.layout.activity_tablet_demo);

        bindViews();
        setupMapContainer();
        initPlaces();
        setupSuggestionsRv();
        setupTextWatcher();
        setupButtons();

        mMapView.onCreate(savedInstanceState);
        mMapView.getMapAsync(this);

        NavigationEngine.getInstance().startGps(this);

        IntentFilter f = new IntentFilter();
        f.addAction(NavigationEngine.ACTION_LOCATION);
        f.addAction(NavigationEngine.ACTION_ROUTE);
        f.addAction(NavigationEngine.ACTION_STEP);
        f.addAction(NavigationEngine.ACTION_ARRIVED);
        LocalBroadcastManager.getInstance(this).registerReceiver(mNavReceiver, f);
    }

    private void bindViews() {
        mMapView       = findViewById(R.id.demo_map_view);
        mHud           = findViewById(R.id.demo_hud);
        mTurnIcon      = findViewById(R.id.demo_turn_icon);
        mTvDistance    = findViewById(R.id.demo_distance);
        mTvStreet      = findViewById(R.id.demo_street);
        mTvEtaHud      = findViewById(R.id.demo_eta_hud);
        mCursor        = findViewById(R.id.demo_cursor);
        mSpeedValue    = findViewById(R.id.demo_speed);
        mArrivedBanner = findViewById(R.id.demo_arrived);
        mGpsDot        = findViewById(R.id.demo_gps_dot);
        mTvGpsStatus   = findViewById(R.id.demo_gps_status);
        mTvCoords      = findViewById(R.id.demo_coords);
        mEtAddress     = findViewById(R.id.demo_et_address);
        mRvSuggestions = findViewById(R.id.demo_rv_suggestions);
        mCardEta       = findViewById(R.id.demo_card_eta);
        mTvEtaDest     = findViewById(R.id.demo_eta_dest);
        mTvEtaStep     = findViewById(R.id.demo_eta_step);
        mTvEtaTime     = findViewById(R.id.demo_eta_time);
        mTvEtaDist     = findViewById(R.id.demo_eta_dist);
        mBtnGo         = findViewById(R.id.demo_btn_go);
        mBtnStop       = findViewById(R.id.demo_btn_stop);
        mNavSubtitle   = findViewById(R.id.demo_nav_subtitle);
    }

    private void setupMapContainer() {
        FrameLayout container = findViewById(R.id.demo_map_container);
        container.post(() -> {
            int w = container.getWidth();
            if (w <= 0) return;
            ViewGroup.LayoutParams lp = container.getLayoutParams();
            lp.height = w * 3 / 8;
            container.setLayoutParams(lp);
        });
    }

    private void initPlaces() {
        String apiKey = getString(R.string.google_maps_key);
        boolean hasKey = !apiKey.equals("YOUR_GOOGLE_MAPS_API_KEY");
        if (hasKey) {
            if (!Places.isInitialized()) Places.initialize(getApplicationContext(), apiKey);
            try { mPlacesClient = Places.createClient(this); }
            catch (Exception e) { AppLogger.w(TAG, "PlacesClient: " + e.getMessage()); }
        }
    }

    private void setupSuggestionsRv() {
        mRvSuggestions.setLayoutManager(new LinearLayoutManager(this));
        mRvSuggestions.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
                TextView tv = new TextView(parent.getContext());
                int dp = (int)(parent.getContext().getResources().getDisplayMetrics().density * 14);
                tv.setPadding(dp * 2, dp, dp * 2, dp);
                tv.setTextSize(13f);
                tv.setTextColor(0xFFCCCCFF);
                tv.setMaxLines(2);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                tv.setBackgroundColor(0xFF14142a);
                RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        RecyclerView.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, 1, 0, 0);
                tv.setLayoutParams(lp);
                return new RecyclerView.ViewHolder(tv) {};
            }
            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder holder, int pos) {
                Suggestion s = mItems.get(pos);
                ((TextView) holder.itemView).setText(s.label);
                holder.itemView.setOnClickListener(v -> onSuggestionSelected(s));
            }
            @Override public int getItemCount() { return mItems.size(); }
        });
    }

    private void setupTextWatcher() {
        mEtAddress.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int start, int b, int count) {
                mSelectedLatLng = null;
                mSelectedName   = null;
                mBtnGo.setVisibility(View.VISIBLE);
                querySuggestions(s.toString().trim());
            }
        });
    }

    private void setupButtons() {
        mBtnGo.setOnClickListener(v -> {
            if (mSelectedLatLng != null) {
                startNavigation(mSelectedLatLng, mSelectedName);
            } else if (!mItems.isEmpty()) {
                onSuggestionSelected(mItems.get(0));
            } else {
                mNavSubtitle.setText("Sélectionnez une suggestion");
            }
        });
        mBtnStop.setOnClickListener(v -> {
            NavigationEngine.getInstance().stopNavigation(this);
            resetNavUi();
        });
    }

    @Override protected void onResume()  { super.onResume();  mMapView.onResume(); }
    @Override protected void onPause()   { mMapView.onPause(); super.onPause(); }
    @Override protected void onDestroy() {
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
        try { mMap.setMapStyle(MapStyleOptions.loadRawResourceStyle(this, R.raw.map_style_day)); }
        catch (Exception e) { /* ignore */ }

        mMap.getUiSettings().setCompassEnabled(false);
        mMap.getUiSettings().setMapToolbarEnabled(false);
        mMap.getUiSettings().setZoomControlsEnabled(false);
        mMap.getUiSettings().setRotateGesturesEnabled(false);
        mMap.getUiSettings().setTiltGesturesEnabled(false);
        mMap.getUiSettings().setScrollGesturesEnabled(true);
        mMap.getUiSettings().setZoomGesturesEnabled(true);

        mMapView.post(() -> {
            int h = mMapView.getHeight();
            if (h <= 0) return;
            mMap.setPadding(0, (int)(h * (CURSOR_FRAC - 0.5f) * 2), 0, 0);

            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mCursor.getLayoutParams();
            lp.topMargin = (int)(h * CURSOR_FRAC) - mCursor.getHeight() / 2;
            mCursor.setLayoutParams(lp);

            android.location.Location loc = NavigationEngine.getInstance().getCurrentLocation();
            if (loc != null) {
                mLat = loc.getLatitude(); mLng = loc.getLongitude(); mBearing = loc.getBearing();
            } else {
                mLat = 33.5731; mLng = -7.5898; // Default: Casablanca
            }
            animateCamera(false);
        });
    }

    // ─── Navigation events ────────────────────────────────────────────────────

    private void onLocation(Intent i) {
        mLat     = i.getDoubleExtra(NavigationEngine.EXTRA_LAT, mLat);
        mLng     = i.getDoubleExtra(NavigationEngine.EXTRA_LNG, mLng);
        mBearing = i.getFloatExtra(NavigationEngine.EXTRA_BEARING, mBearing);
        float speed = i.getFloatExtra(NavigationEngine.EXTRA_SPEED_KMH, 0f);
        mSpeedValue.setText(String.format(Locale.getDefault(), "%d", (int) speed));
        setGpsDot(true);
        mTvGpsStatus.setText("GPS: actif");
        mTvCoords.setText(String.format(Locale.US, "%.5f, %.5f", mLat, mLng));
        if (mMap != null) animateCamera(true);
    }

    private void onRoute(Intent i) {
        if (mMap == null) return;
        ArrayList<LatLng> pts = i.getParcelableArrayListExtra(NavigationEngine.EXTRA_POLYLINE);
        if (pts == null || pts.isEmpty()) return;
        if (mPolyline != null) mPolyline.remove();
        mPolyline = mMap.addPolyline(new PolylineOptions()
                .addAll(pts).color(Color.parseColor("#F5C518")).width(12f).geodesic(true));
        int eta = i.getIntExtra(NavigationEngine.EXTRA_ETA_SECONDS, 0);
        int dist= i.getIntExtra(NavigationEngine.EXTRA_TOTAL_DIST_M, 0);
        mTvEtaHud.setText(NavigationEngine.formatDuration(eta));
        mTvEtaTime.setText(NavigationEngine.formatDuration(eta));
        mTvEtaDist.setText(NavigationEngine.formatDistance(dist));
        mHud.setVisibility(View.VISIBLE);
        mCardEta.setVisibility(View.VISIBLE);
        mNavSubtitle.setText("Navigation en cours…");
    }

    private void onStep(Intent i) {
        String text   = i.getStringExtra(NavigationEngine.EXTRA_STEP_TEXT);
        int    distM  = i.getIntExtra(NavigationEngine.EXTRA_STEP_DIST_M, 0);
        int    etaSec = i.getIntExtra(NavigationEngine.EXTRA_ETA_SECONDS, 0);
        int    totM   = i.getIntExtra(NavigationEngine.EXTRA_TOTAL_DIST_M, 0);
        String maneuv = i.getStringExtra(NavigationEngine.EXTRA_TURN_TYPE);
        String dest   = i.getStringExtra(NavigationEngine.EXTRA_DEST_NAME);

        mTvDistance.setText(NavigationEngine.formatDistance(distM));
        mTvStreet.setText(text != null ? text : "");
        mTvEtaHud.setText(NavigationEngine.formatDuration(etaSec));
        mHud.setVisibility(View.VISIBLE);

        mTvEtaStep.setText(NavigationEngine.formatDistance(distM)
                + (text != null ? " — " + text : ""));
        mTvEtaTime.setText(NavigationEngine.formatDuration(etaSec));
        mTvEtaDist.setText(NavigationEngine.formatDistance(totM));
        if (dest != null && !dest.isEmpty()) mTvEtaDest.setText(dest);
        mCardEta.setVisibility(View.VISIBLE);

        if (maneuv != null) {
            if      (maneuv.contains("left"))  mTurnIcon.setImageResource(R.drawable.ic_turn_left);
            else if (maneuv.contains("right")) mTurnIcon.setImageResource(R.drawable.ic_turn_right);
            else                               mTurnIcon.setImageResource(R.drawable.ic_turn_straight);
        }
    }

    private void onArrived() {
        mHud.setVisibility(View.GONE);
        mArrivedBanner.setVisibility(View.VISIBLE);
        mArrivedBanner.postDelayed(() -> mArrivedBanner.setVisibility(View.GONE), 6000);
        mNavSubtitle.setText("Arrivé à destination!");
        mBtnStop.setVisibility(View.VISIBLE);
        mBtnGo.setVisibility(View.GONE);
    }

    // ─── Camera ───────────────────────────────────────────────────────────────

    private void animateCamera(boolean animate) {
        if (mMap == null || (mLat == 0 && mLng == 0)) return;
        CameraPosition pos = new CameraPosition.Builder()
                .target(new LatLng(mLat, mLng)).zoom(ZOOM).tilt(TILT).bearing(mBearing).build();
        if (animate) mMap.animateCamera(CameraUpdateFactory.newCameraPosition(pos), CAM_MS, null);
        else         mMap.moveCamera(CameraUpdateFactory.newCameraPosition(pos));
    }

    // ─── Suggestions (unified: Places API or demo fallback) ───────────────────

    private void querySuggestions(String query) {
        if (query.length() < 2) {
            mItems.clear();
            notifySuggestions();
            return;
        }
        if (mPlacesClient != null) {
            mPlacesClient.findAutocompletePredictions(
                    FindAutocompletePredictionsRequest.builder().setQuery(query).build())
                    .addOnSuccessListener(resp -> {
                        mItems.clear();
                        for (AutocompletePrediction p : resp.getAutocompletePredictions()) {
                            mItems.add(new Suggestion(
                                    p.getFullText(null).toString(), p.getPlaceId()));
                        }
                        notifySuggestions();
                    })
                    .addOnFailureListener(e -> {
                        AppLogger.w(TAG, "autocomplete: " + e.getMessage());
                        showDemoFallback(query);
                    });
        } else {
            showDemoFallback(query);
        }
    }

    private void showDemoFallback(String query) {
        String q = query.toLowerCase(Locale.ROOT);
        mItems.clear();
        for (Suggestion s : DEMO_SPOTS) {
            if (s.label.toLowerCase(Locale.ROOT).contains(q)) mItems.add(s);
        }
        if (mItems.isEmpty()) {
            for (Suggestion s : DEMO_SPOTS) mItems.add(s);
        }
        notifySuggestions();
    }

    private void notifySuggestions() {
        if (mRvSuggestions.getAdapter() != null) mRvSuggestions.getAdapter().notifyDataSetChanged();
        mRvSuggestions.setVisibility(mItems.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void onSuggestionSelected(Suggestion s) {
        mEtAddress.setText(s.label);
        if (mEtAddress.getText() != null) mEtAddress.setSelection(mEtAddress.getText().length());
        mItems.clear();
        notifySuggestions();

        if (s.knownLatLng != null) {
            // Demo destination — navigate directly
            mSelectedLatLng = s.knownLatLng;
            mSelectedName   = s.label;
            startNavigation(mSelectedLatLng, mSelectedName);
        } else if (s.placeId != null && mPlacesClient != null) {
            // Fetch LatLng from Places
            mPlacesClient.fetchPlace(
                    FetchPlaceRequest.newInstance(s.placeId,
                            Arrays.asList(Place.Field.LAT_LNG, Place.Field.NAME)))
                    .addOnSuccessListener(resp -> {
                        mSelectedLatLng = resp.getPlace().getLatLng();
                        mSelectedName   = resp.getPlace().getName();
                        if (mSelectedLatLng != null)
                            startNavigation(mSelectedLatLng, mSelectedName);
                    })
                    .addOnFailureListener(e -> AppLogger.w(TAG, "fetchPlace: " + e.getMessage()));
        }
    }

    // ─── Navigation ───────────────────────────────────────────────────────────

    private void startNavigation(LatLng dest, String name) {
        mBtnGo.setVisibility(View.GONE);
        mBtnStop.setVisibility(View.VISIBLE);
        mCardEta.setVisibility(View.VISIBLE);
        mTvEtaDest.setText(name != null ? name : "");
        mTvEtaStep.setText("Calcul de l'itinéraire…");
        mTvEtaTime.setText("");
        mTvEtaDist.setText("");
        mNavSubtitle.setText("Calcul en cours…");
        NavigationEngine.getInstance().startNavigation(this, dest, name);
    }

    private void resetNavUi() {
        mBtnGo.setVisibility(View.VISIBLE);
        mBtnStop.setVisibility(View.GONE);
        mCardEta.setVisibility(View.GONE);
        mHud.setVisibility(View.GONE);
        if (mPolyline != null) { mPolyline.remove(); mPolyline = null; }
        mNavSubtitle.setText("Saisissez une destination");
        mSelectedLatLng = null;
        mSelectedName   = null;
    }

    // ─── GPS indicator ────────────────────────────────────────────────────────

    private void setGpsDot(boolean active) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(active ? 0xFF4CAF50 : 0xFF888888);
        mGpsDot.setBackground(d);
    }
}
