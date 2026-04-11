package com.byd.myapp.dashboard;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.util.Log;
import android.view.Display;

/**
 * DashboardDisplayHelper — détecte le display secondaire (instrument cluster).
 *
 * COMPORTEMENT SUR BYD SEAL :
 *   Le cluster n'est PAS exposé comme un display Android visible natif.
 *   Il faut d'abord appeler ClusterManager.enterProjectionMode() (sendInfo 1000/16)
 *   pour que AutoDisplayService crée son VirtualDisplay, puis écouter son apparition.
 *
 *   Ce helper délègue aujourd'hui toute la logique à ClusterManager.activateClusterDisplay()
 *   qui gère : sendInfo + startService AutoDisplayService + polling VirtualDisplay + timeout.
 *
 *   Le DisplayListener reste enregistré pour détecter les déconnexions.
 */
public class DashboardDisplayHelper {

    private static final String TAG = "DashboardDisplayHelper";

    public interface Listener {
        void onDashboardDisplayConnected(Display display, int displayId);
        void onDashboardDisplayDisconnected();
    }

    private final Context mContext;
    private final DisplayManager mDisplayManager;
    private final Listener mListener;
    private final ClusterManager mClusterManager;

    // ID du display cluster connu — -1 si non connecté
    private int mKnownClusterDisplayId = -1;

    private final DisplayManager.DisplayListener mDisconnectListener =
            new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {}

                @Override
                public void onDisplayRemoved(int displayId) {
                    if (displayId != mKnownClusterDisplayId) return;
                    Log.i(TAG, "Dashboard display supprimé : id=" + displayId);
                    mKnownClusterDisplayId = -1;
                    mListener.onDashboardDisplayDisconnected();
                }

                @Override
                public void onDisplayChanged(int displayId) {}
            };

    public DashboardDisplayHelper(Context context, Listener listener) {
        mContext = context.getApplicationContext();
        mDisplayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        mListener = listener;
        mClusterManager = new ClusterManager(context);
    }

    /**
     * Déclenche la séquence d'activation du cluster :
     *   1. sendInfo(1000, 16) via Binder direct (Qt entre en standby, display 1 reste dans IActivityManager)
     *   2. startService AutoDisplayService
     *   3. Attend l'apparition du VirtualDisplay (polling + listener, timeout 8s)
     *
     * La callback onDashboardDisplayConnected / onDashboardDisplayDisconnected
     * sera appelée sur le main thread.
     */
    public void start() {
        mDisplayManager.registerDisplayListener(mDisconnectListener, null);

        mClusterManager.activateClusterDisplay(new ClusterManager.DisplayReadyCallback() {
            @Override
            public void onDisplayReady(Display display, int displayId) {
                // Guard : si stop() a déjà été appelé, ignorer le callback
                if (mKnownClusterDisplayId == -2) return;
                mKnownClusterDisplayId = displayId;
                Log.i(TAG, "Dashboard display prêt : id=" + displayId
                        + " name=" + (display != null ? display.getName() : "null"));
                mListener.onDashboardDisplayConnected(display, displayId);
            }

            @Override
            public void onDisplayTimeout() {
                // Guard : si stop() a déjà été appelé, ignorer le callback orphelin
                if (mKnownClusterDisplayId == -2) {
                    Log.d(TAG, "onDisplayTimeout ignoré — stop() déjà appelé");
                    return;
                }
                Log.w(TAG, "Dashboard display non détecté après timeout — "
                        + "fallback sur display ID 1 (IActivityManager path, DiLink 3.0)");
                // Sur DiLink 3.0, le cluster = display 1 dans IActivityManager mais PAS dans DisplayManager.
                mKnownClusterDisplayId = 1;
                Display display1 = mDisplayManager.getDisplay(1); // peut retourner null
                if (display1 != null) {
                    Log.i(TAG, "getDisplay(1) retourné != null — display cluster disponible");
                    mListener.onDashboardDisplayConnected(display1, 1);
                } else {
                    Log.i(TAG, "getDisplay(1) null — cluster via IActivityManager uniquement (displayId=1)");
                    mListener.onDashboardDisplayConnected(null, 1);
                }
            }
        });
    }

    public void stop() {
        // Sentinelle : toute callback ClusterManager orpheline (handler postDelayed) sera ignorée
        mKnownClusterDisplayId = -2;

        // Annuler TOUS les callbacks Handler en attente (polls + timeout) dans ClusterManager.
        // SANS ça, le timeout de 8s se déclenche après stop() et appelle onDashboardDisplayConnected(null,1) → NPE.
        mClusterManager.cancel();

        mDisplayManager.unregisterDisplayListener(mDisconnectListener);

        // Arrêter AutoDisplayService + restaurer Qt uniquement si on a effectivement
        // démarré la séquence de projection (sinon inutile et potentiellement perturbateur).
        // Utiliser -2 comme sentinelle "stop() appelé", donc on vérifie l'état AVANT de le modifier.
        try {
            android.content.Intent stopIntent = new android.content.Intent();
            stopIntent.setComponent(new android.content.ComponentName(
                    "com.xdja.containerservice",
                    "com.xdja.containerservice.AutoDisplayService"));
            mContext.stopService(stopIntent);
        } catch (Exception e) {
            android.util.Log.w(TAG, "stopService AutoDisplayService : " + e.getMessage());
        }
        mClusterManager.restoreNative();
        // Réinitialiser à -1 (état "déconnecté normal" après stop complet)
        mKnownClusterDisplayId = -1;
    }

    /** Restaure le cluster BYD natif (sendInfo 1000/0). */
    public void restoreNative() {
        mClusterManager.restoreNative();
    }

    public int getKnownClusterDisplayId() {
        return mKnownClusterDisplayId;
    }
}
