package com.byd.dashcast;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.os.Looper;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.model.LatLng;

import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * NavigationEngine — singleton that owns GPS, routing, and turn-by-turn logic.
 * Communicates via LocalBroadcastManager so both ClusterMapActivity and
 * MainActivity can observe the same stream without coupling.
 */
public class NavigationEngine {

    private static final String TAG = "NavEngine";

    // ─── Broadcast action constants ───────────────────────────────────────────
    /** Intent extra: Location object (current GPS fix). */
    public static final String ACTION_LOCATION = "dashcast.NAV_LOCATION";
    /** Intent extra: List of LatLng route polyline points (Parcelable ArrayList). */
    public static final String ACTION_ROUTE    = "dashcast.NAV_ROUTE";
    /** Intent extra: current step instruction String and distance int (metres). */
    public static final String ACTION_STEP     = "dashcast.NAV_STEP";
    /** No extras — fired when the user arrives at the destination. */
    public static final String ACTION_ARRIVED  = "dashcast.NAV_ARRIVED";

    // ─── Extras keys ──────────────────────────────────────────────────────────
    public static final String EXTRA_LAT          = "lat";
    public static final String EXTRA_LNG          = "lng";
    public static final String EXTRA_BEARING      = "bearing";
    public static final String EXTRA_SPEED_KMH    = "speed_kmh";
    public static final String EXTRA_POLYLINE     = "polyline";    // ArrayList<LatLng>
    public static final String EXTRA_STEP_TEXT    = "step_text";
    public static final String EXTRA_STEP_DIST_M  = "step_dist_m";
    public static final String EXTRA_ETA_SECONDS  = "eta_sec";
    public static final String EXTRA_TOTAL_DIST_M = "total_dist_m";
    public static final String EXTRA_DEST_NAME    = "dest_name";
    public static final String EXTRA_TURN_TYPE    = "turn_type";   // "left","right","straight"

    // ─── Singleton ────────────────────────────────────────────────────────────
    @SuppressLint("StaticFieldLeak")
    private static NavigationEngine sInstance;

    public static NavigationEngine getInstance() {
        if (sInstance == null) sInstance = new NavigationEngine();
        return sInstance;
    }

    private NavigationEngine() {}

    // ─── State ────────────────────────────────────────────────────────────────
    private FusedLocationProviderClient mFused;
    private LocationCallback            mLocCb;
    private Location                    mLastLoc;

    private boolean     mNavigating    = false;
    private LatLng      mDestination;
    private String      mDestName      = "";
    private List<LatLng> mPolyline     = new ArrayList<>();
    private List<RouteStep> mSteps     = new ArrayList<>();
    private int         mCurrentStep   = 0;
    private int         mEtaSeconds    = 0;
    private int         mTotalDistM    = 0;

    // ─── GPS ──────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    public void startGps(Context ctx) {
        if (mFused != null) return;  // already started
        mFused = LocationServices.getFusedLocationProviderClient(ctx.getApplicationContext());

        LocationRequest req = LocationRequest.create()
                .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
                .setInterval(1000)
                .setFastestInterval(500);

        mLocCb = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult result) {
                if (result == null) return;
                Location loc = result.getLastLocation();
                if (loc == null) return;
                mLastLoc = loc;
                onNewLocation(ctx.getApplicationContext(), loc);
            }
        };

        mFused.requestLocationUpdates(req, mLocCb, Looper.getMainLooper());
        Log.d(TAG, "GPS started");
    }

    public void stopGps() {
        if (mFused != null && mLocCb != null) {
            mFused.removeLocationUpdates(mLocCb);
        }
        mFused  = null;
        mLocCb  = null;
        mLastLoc = null;
        Log.d(TAG, "GPS stopped");
    }

    public Location getCurrentLocation() { return mLastLoc; }
    public boolean  isNavigating()       { return mNavigating; }
    public LatLng   getDestination()     { return mDestination; }
    public String   getDestName()        { return mDestName; }
    public int      getEtaSeconds()      { return mEtaSeconds; }
    public int      getTotalDistM()      { return mTotalDistM; }

    // ─── Navigation control ───────────────────────────────────────────────────

    /**
     * Start navigation from current GPS position to destination.
     * Fetches route async on a background thread.
     */
    public void startNavigation(Context ctx, LatLng destination, String destName) {
        mDestination  = destination;
        mDestName     = destName != null ? destName : "";
        mNavigating   = true;
        mCurrentStep  = 0;
        mPolyline.clear();
        mSteps.clear();

        if (mLastLoc == null) {
            Log.w(TAG, "No GPS fix yet, will route when first fix arrives");
        }

        fetchRoute(ctx.getApplicationContext());
    }

    public void stopNavigation(Context ctx) {
        mNavigating  = false;
        mDestination = null;
        mDestName    = "";
        mPolyline.clear();
        mSteps.clear();
        mCurrentStep = 0;
        mEtaSeconds  = 0;
        mTotalDistM  = 0;
        Log.d(TAG, "Navigation stopped");
    }

    // ─── Location updates ─────────────────────────────────────────────────────

    private void onNewLocation(Context ctx, Location loc) {
        // Broadcast location
        Intent i = new Intent(ACTION_LOCATION);
        i.putExtra(EXTRA_LAT,       loc.getLatitude());
        i.putExtra(EXTRA_LNG,       loc.getLongitude());
        i.putExtra(EXTRA_BEARING,   loc.getBearing());
        i.putExtra(EXTRA_SPEED_KMH, loc.getSpeed() * 3.6f);
        LocalBroadcastManager.getInstance(ctx).sendBroadcast(i);

        if (!mNavigating || mSteps.isEmpty()) return;

        // Advance step if close to step end_location
        advanceStep(ctx, loc);
    }

    private void advanceStep(Context ctx, Location loc) {
        if (mCurrentStep >= mSteps.size()) return;

        RouteStep step = mSteps.get(mCurrentStep);
        double distToEnd = haversineMetres(
                loc.getLatitude(), loc.getLongitude(),
                step.endLat, step.endLng);

        // Within 30 m of step end → advance
        if (distToEnd < 30 && mCurrentStep < mSteps.size() - 1) {
            mCurrentStep++;
            broadcastStep(ctx);
        }

        // Final step and within 30 m of destination → arrived
        if (mCurrentStep == mSteps.size() - 1 && distToEnd < 30) {
            mNavigating = false;
            LocalBroadcastManager.getInstance(ctx).sendBroadcast(new Intent(ACTION_ARRIVED));
            Log.d(TAG, "Arrived at destination");
            return;
        }

        // Update ETA (rough: remaining straight-line + original route seconds ratio)
        double totalRemain = 0;
        for (int s = mCurrentStep; s < mSteps.size(); s++) {
            totalRemain += mSteps.get(s).distanceM;
        }
        if (mTotalDistM > 0) {
            mEtaSeconds = (int)((totalRemain / mTotalDistM) * mEtaSeconds);
        }
    }

    private void broadcastStep(Context ctx) {
        if (mCurrentStep >= mSteps.size()) return;
        RouteStep step = mSteps.get(mCurrentStep);
        Intent i = new Intent(ACTION_STEP);
        i.putExtra(EXTRA_STEP_TEXT,   step.instruction);
        i.putExtra(EXTRA_STEP_DIST_M, step.distanceM);
        i.putExtra(EXTRA_ETA_SECONDS, mEtaSeconds);
        i.putExtra(EXTRA_TOTAL_DIST_M, mTotalDistM);
        i.putExtra(EXTRA_DEST_NAME,   mDestName);
        i.putExtra(EXTRA_TURN_TYPE,   step.maneuver);
        LocalBroadcastManager.getInstance(ctx).sendBroadcast(i);
    }

    // ─── Routing ──────────────────────────────────────────────────────────────

    private void fetchRoute(final Context ctx) {
        if (mLastLoc == null || mDestination == null) return;

        final double fromLat = mLastLoc.getLatitude();
        final double fromLng = mLastLoc.getLongitude();
        final double toLat   = mDestination.latitude;
        final double toLng   = mDestination.longitude;
        final String apiKey  = ctx.getString(R.string.google_maps_key);

        new Thread(() -> {
            try {
                String urlStr = String.format(Locale.US,
                        "https://maps.googleapis.com/maps/api/directions/json" +
                        "?origin=%.7f,%.7f&destination=%.7f,%.7f" +
                        "&mode=driving&language=fr&key=%s",
                        fromLat, fromLng, toLat, toLng, apiKey);

                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(15_000);
                conn.setRequestMethod("GET");

                BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                conn.disconnect();

                JSONObject root = new JSONObject(sb.toString());
                String status = root.getString("status");
                if (!"OK".equals(status)) {
                    Log.e(TAG, "Directions API status: " + status);
                    return;
                }

                JSONObject route = root.getJSONArray("routes").getJSONObject(0);
                JSONObject leg   = route.getJSONArray("legs").getJSONObject(0);

                // Total duration + distance
                mEtaSeconds = leg.getJSONObject("duration").getInt("value");
                mTotalDistM = leg.getJSONObject("distance").getInt("value");

                // Overview polyline
                String encodedPoly = route.getJSONObject("overview_polyline").getString("points");
                mPolyline = decodePoly(encodedPoly);

                // Steps
                mSteps.clear();
                JSONArray steps = leg.getJSONArray("steps");
                for (int s = 0; s < steps.length(); s++) {
                    JSONObject st = steps.getJSONObject(s);
                    RouteStep rs = new RouteStep();
                    rs.instruction = stripHtml(st.getString("html_instructions"));
                    rs.distanceM   = st.getJSONObject("distance").getInt("value");
                    rs.endLat      = st.getJSONObject("end_location").getDouble("lat");
                    rs.endLng      = st.getJSONObject("end_location").getDouble("lng");
                    rs.maneuver    = st.optString("maneuver", "straight");
                    mSteps.add(rs);
                }

                // Broadcast route
                Intent ri = new Intent(ACTION_ROUTE);
                ArrayList<LatLng> polyList = new ArrayList<>(mPolyline);
                ri.putParcelableArrayListExtra(EXTRA_POLYLINE, polyList);
                ri.putExtra(EXTRA_ETA_SECONDS,  mEtaSeconds);
                ri.putExtra(EXTRA_TOTAL_DIST_M, mTotalDistM);
                ri.putExtra(EXTRA_DEST_NAME,    mDestName);
                LocalBroadcastManager.getInstance(ctx).sendBroadcast(ri);

                // Broadcast first step
                broadcastStep(ctx);

                Log.d(TAG, "Route fetched: " + mSteps.size() + " steps, "
                        + mTotalDistM + "m, " + mEtaSeconds + "s");

            } catch (Exception e) {
                Log.e(TAG, "fetchRoute error", e);
            }
        }, "NavEngine-Route").start();
    }

    // ─── Polyline decoder ─────────────────────────────────────────────────────

    static List<LatLng> decodePoly(String encoded) {
        List<LatLng> poly = new ArrayList<>();
        int index = 0, len = encoded.length(), lat = 0, lng = 0;
        while (index < len) {
            int b, shift = 0, result = 0;
            do {
                b = encoded.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            lat += ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));
            shift = 0; result = 0;
            do {
                b = encoded.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            lng += ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));
            poly.add(new LatLng(lat / 1E5, lng / 1E5));
        }
        return poly;
    }

    // ─── Haversine ────────────────────────────────────────────────────────────

    static double haversineMetres(double lat1, double lon1, double lat2, double lon2) {
        double R = 6_371_000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat/2) * Math.sin(dLat/2)
                 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLon/2) * Math.sin(dLon/2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
    }

    // ─── Bearing ──────────────────────────────────────────────────────────────

    static float bearingTo(double lat1, double lon1, double lat2, double lon2) {
        double dLon = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dLon) * Math.cos(Math.toRadians(lat2));
        double x = Math.cos(Math.toRadians(lat1)) * Math.sin(Math.toRadians(lat2))
                 - Math.sin(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.cos(dLon);
        float bearing = (float) Math.toDegrees(Math.atan2(y, x));
        return (bearing + 360) % 360;
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static String stripHtml(String html) {
        return html.replaceAll("<[^>]+>", "").replaceAll("&nbsp;", " ").trim();
    }

    /** Formats metres to "1.2 km" or "350 m". */
    public static String formatDistance(int metres) {
        if (metres >= 1000) {
            return String.format(Locale.getDefault(), "%.1f km", metres / 1000f);
        }
        return metres + " m";
    }

    /** Formats seconds to "23 min" or "1h 12min". */
    public static String formatDuration(int seconds) {
        int minutes = seconds / 60;
        if (minutes < 60) return minutes + " min";
        return (minutes / 60) + "h " + (minutes % 60) + "min";
    }

    // ─── Step model ───────────────────────────────────────────────────────────

    private static class RouteStep {
        String instruction;
        int    distanceM;
        double endLat, endLng;
        String maneuver;
    }
}
