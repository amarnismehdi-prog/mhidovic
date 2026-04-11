package com.byd.myapp.dashboard;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.util.Log;
import android.view.Display;

import java.lang.reflect.Method;

/**
 * ClusterManager — contrôle direct du cluster BYD Seal via le service Binder "AutoContainer".
 *
 * ARCHITECTURE (DiLink 3.0 / XDJA) :
 *   • Le service "AutoContainer" (android.os.IAutoContainer) est enregistré dans ServiceManager.
 *   • AutoContainerManager (getSystemService("auto_container")) vérifie la whitelist
 *     /system/etc/container_comm_cfg.json → uniquement "com.xdja.clusterdemo" autorisé.
 *   • MAIS : l'appel Binder direct bypasse ce check Java (confirmé TEST 8 — retour 00000000).
 *   • On accède au Binder via ServiceManager.getService("AutoContainer") par réflexion.
 *
 * AIDL IAutoContainer (transactions) :
 *   #1 sendJson(int type, String json)
 *   #2 sendInfo(int type, int infoInt, String infoStr)  ← utilisé ici
 *   #3 sendInfo2(int type, byte[] data)
 *   #4 registerCallback(IAutoContainerCallback cb)
 *
 * COMMANDES CLUSTER (type=1000) :
 *   infoInt=16  → mode projection plein écran : Qt entre en standby et laisse display 1 enregistré
 *                  dans IActivityManager. Header/footer Qt restent visibles SAUF si
 *                  setLaunchWindowingMode(FULLSCREEN=1) est utilisé → app couvre tout.
 *                  C'EST LA BONNE COMMANDE pour lancer une app sur display 1.
 *   infoInt= 1  → déconnecte Qt ENTIÈREMENT → MCU prend le contrôle (Simple mode)
 *                  display 1 DISPARAIT d'IActivityManager → NE PAS UTILISER pour lancer des apps
 *   infoInt= 0  → restaurer rendu BYD natif
 */
public class ClusterManager {

    private static final String TAG = "ClusterManager";

    // Nom exact dans ServiceManager (case-sensitive, confirmé par `service list`)
    public static final String SERVICE_NAME = "AutoContainer";
    // Token AIDL (interface descriptor)
    private static final String INTERFACE_TOKEN = "android.os.IAutoContainer";

    // Numéros de transaction AIDL
    private static final int TX_SEND_INFO = 2;

    // Paramètres sendInfo(type, infoInt, infoStr)
    public static final int CLUSTER_TYPE      = 1000;
    public static final int CMD_PROJECTION_ON  = 16;   // Qt standby + display 1 reste dans IActivityManager
    public static final int CMD_DISCONNECT_QT  = 1;    // Qt déconnecté complètement (Simple mode, display 1 disparaît)
    public static final int CMD_RESTORE_NATIVE = 0;    // restaurer rendu BYD natif

    // Timeout d'attente du VirtualDisplay après sendInfo(projection_on)
    private static final long VIRTUAL_DISPLAY_TIMEOUT_MS = 8000;
    // Polling interval pour détecter le virtual display
    private static final long POLL_INTERVAL_MS = 500;

    // ─────────────────────────────────────────────────────────────────────────

    /** Notifié quand le VirtualDisplay cluster devient disponible (ou timeout). */
    public interface DisplayReadyCallback {
        void onDisplayReady(Display display, int displayId);
        void onDisplayTimeout();
    }

    // ─────────────────────────────────────────────────────────────────────────

    private final Context mContext;
    private IBinder mBinder;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    // Référence au DisplayListener en cours d'activateClusterDisplay(), afin que cancel()
    // puisse le désinscrire même si aucun display n'est jamais apparu.
    private DisplayManager.DisplayListener mActiveDisplayListener = null;
    private DisplayManager               mActiveDisplayManager   = null;

    public ClusterManager(Context context) {
        mContext = context.getApplicationContext();
    }

    // ── Binder ──────────────────────────────────────────────────────────────

    /**
     * Obtient le Binder AutoContainer via réflexion ServiceManager.
     * @return true si le service est disponible.
     */
    public boolean connect() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method getService = sm.getDeclaredMethod("getService", String.class);
            mBinder = (IBinder) getService.invoke(null, SERVICE_NAME);
            if (mBinder == null) {
                Log.w(TAG, "ServiceManager.getService(\"" + SERVICE_NAME + "\") = null");
                return false;
            }
            Log.i(TAG, "AutoContainer Binder obtenu : " + mBinder);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "connect() échec : " + e.getMessage());
            return false;
        }
    }

    /**
     * Envoi d'une commande sendInfo (transaction #2) au service AutoContainer.
     *
     * @param type     toujours 1000 pour le cluster
     * @param infoInt  commande : CMD_PROJECTION_ON(16), CMD_DISCONNECT_QT(1), CMD_RESTORE_NATIVE(0)
     * @param infoStr  payload string (généralement "")
     * @return true si l'appel Binder a réussi (pas d'exception, pas de RemoteException)
     */
    public boolean sendInfo(int type, int infoInt, String infoStr) {
        if (mBinder == null && !connect()) {
            Log.e(TAG, "sendInfo : Binder non disponible");
            return false;
        }
        Parcel data  = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(INTERFACE_TOKEN);
            data.writeInt(type);
            data.writeInt(infoInt);
            data.writeString(infoStr != null ? infoStr : "");
            mBinder.transact(TX_SEND_INFO, data, reply, 0);
            // Lecture de l'exception distante (lance une exception Java si le Binder a retourné une erreur)
            reply.readException();
            Log.i(TAG, "sendInfo(" + type + ", " + infoInt + ", \"" + infoStr + "\") OK");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "sendInfo(" + type + ", " + infoInt + ") échec : " + e.getMessage());
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    // ── Commandes haut niveau ────────────────────────────────────────────────

    /** Met le cluster en mode projection plein écran. */
    public boolean enterProjectionMode() {
        return sendInfo(CLUSTER_TYPE, CMD_PROJECTION_ON, "");
    }

    /** Déconnecte le flux Qt natif (surface disponible pour rendu Android). */
    public boolean disconnectNative() {
        return sendInfo(CLUSTER_TYPE, CMD_DISCONNECT_QT, "");
    }

    /** Restaure le rendu BYD natif (fin du mode projection). */
    public boolean restoreNative() {
        return sendInfo(CLUSTER_TYPE, CMD_RESTORE_NATIVE, "");
    }

    // ── Activation + attente du VirtualDisplay ───────────────────────────────

    /**
     * Séquence complète : sendInfo(16) + démarrage AutoDisplayService
     * + écoute de l'ajout d'un VirtualDisplay dans DisplayManager.
     *
     * CONFIRMATION TEST 10 (11/04/2026) :
     *   cmd=1 = MAUVAISE COMMANDE : Qt se déconnecte entièrement → MCU reprend le contrôle
     *   (Simple mode visible) → display 1 DISPARAIT d'IActivityManager → lancement impossible.
     *
     *   cmd=16 = BONNE COMMANDE : Qt entre en standby, display 1 RESTE enregistré dans
     *   IActivityManager. Le header/footer Qt restaient visibles uniquement parce que
     *   setLaunchWindowingMode(FULLSCREEN) était absent (bug Freedom/WindowManagement).
     *   Avec setLaunchWindowingMode(1), BYDDashboardActivity couvre l'intégralité du 1920×1080.
     *
     * INSIGHT WindowManagement : sur DiLink 3.0, le cluster = display 1 dans IActivityManager.
     * Il ne s'ajoute PAS à DisplayManager. Le timeout est volontairement court (8s) — si après
     * ce délai aucun display n'apparaît, le caller doit utiliser displayId=1 hardcodé.
     *
     * Restauration : appeler restoreNative() (sendInfo(1000,0)) quand l'activité se termine.
     *
     * La callback est appelée sur le main thread.
     */
    public void activateClusterDisplay(final DisplayReadyCallback callback) {
        // 1. Mettre Qt en mode projection standby (cmd=16) — display 1 reste dans IActivityManager
        //    NE PAS utiliser disconnectNative() (cmd=1) : détruit display 1 complètement
        boolean ok = enterProjectionMode();
        Log.i(TAG, "enterProjectionMode (cmd=16) : " + (ok ? "OK" : "ÉCHEC"));

        // 2. Démarrer AutoDisplayService (peut être déjà en cours — sans effet si redémarré)
        try {
            Intent svcIntent = new Intent();
            svcIntent.setComponent(new ComponentName(
                    "com.xdja.containerservice",
                    "com.xdja.containerservice.AutoDisplayService"));
            mContext.startService(svcIntent);
            Log.i(TAG, "startService AutoDisplayService envoyé");
        } catch (Exception e) {
            Log.w(TAG, "startService AutoDisplayService : " + e.getMessage());
        }

        // 3. Écouter l'apparition d'un VirtualDisplay via DisplayManager
        final DisplayManager dm = (DisplayManager) mContext.getSystemService(Context.DISPLAY_SERVICE);

        // Vérifier immédiatement si un display PRESENTATION existe déjà
        Display found = findClusterDisplay(dm);
        if (found != null) {
            Log.i(TAG, "VirtualDisplay déjà présent : id=" + found.getDisplayId());
            callback.onDisplayReady(found, found.getDisplayId());
            return;
        }

        // Sinon : écouter les ajouts + timeout
        final long[] pollCount = {0};
        final DisplayManager.DisplayListener[] listenerHolder = new DisplayManager.DisplayListener[1];

        listenerHolder[0] = new DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int displayId) {
                Display d = dm.getDisplay(displayId);
                Log.i(TAG, "onDisplayAdded id=" + displayId + " display=" + d);
                if (isClusterDisplay(dm, d)) {
                    mHandler.removeCallbacksAndMessages(null);
                    dm.unregisterDisplayListener(listenerHolder[0]);
                    mActiveDisplayListener = null;
                    mActiveDisplayManager  = null;
                    Log.i(TAG, "VirtualDisplay cluster détecté : id=" + displayId);
                    callback.onDisplayReady(d, displayId);
                }
            }
            @Override public void onDisplayRemoved(int displayId) {}
            @Override public void onDisplayChanged(int displayId) {}
        };
        mActiveDisplayManager  = dm;
        mActiveDisplayListener = listenerHolder[0];
        dm.registerDisplayListener(listenerHolder[0], mHandler);

        // Polling supplémentaire : parfois onDisplayAdded n'est pas déclenché pour les VirtualDisplays
        // créés dans un autre processus (binder cross-process)
        scheduleDisplayPoll(dm, listenerHolder, callback, pollCount, 0);

        // Timeout global
        mHandler.postDelayed(new Runnable() {
            @Override public void run() {
                dm.unregisterDisplayListener(listenerHolder[0]);
                mActiveDisplayListener = null;
                mActiveDisplayManager  = null;
                mHandler.removeCallbacksAndMessages(null);
                Log.w(TAG, "Timeout : VirtualDisplay cluster non détecté après "
                        + VIRTUAL_DISPLAY_TIMEOUT_MS + "ms");
                callback.onDisplayTimeout();
            }
        }, VIRTUAL_DISPLAY_TIMEOUT_MS);
    }

    private void scheduleDisplayPoll(
            final DisplayManager dm,
            final DisplayManager.DisplayListener[] listenerHolder,
            final DisplayReadyCallback callback,
            final long[] pollCount,
            long delayMs) {
        mHandler.postDelayed(new Runnable() {
            @Override public void run() {
                pollCount[0]++;
                if (pollCount[0] * POLL_INTERVAL_MS >= VIRTUAL_DISPLAY_TIMEOUT_MS) return;

                Display found = findClusterDisplay(dm);
                if (found != null) {
                    mHandler.removeCallbacksAndMessages(null);
                    dm.unregisterDisplayListener(listenerHolder[0]);
                    mActiveDisplayListener = null;
                    mActiveDisplayManager  = null;
                    Log.i(TAG, "VirtualDisplay trouvé par polling : id=" + found.getDisplayId());
                    callback.onDisplayReady(found, found.getDisplayId());
                } else {
                    scheduleDisplayPoll(dm, listenerHolder, callback, pollCount, POLL_INTERVAL_MS);
                }
            }
        }, delayMs == 0 ? POLL_INTERVAL_MS : delayMs);
    }

    // ── Détection du display cluster ─────────────────────────────────────────

    /**
     * Cherche un display qui ressemble au VirtualDisplay cluster parmi TOUS les displays.
     * On cherche soit PRESENTATION, soit VIRTUAL non-default, car le VirtualDisplay
     * de AutoDisplayService peut avoir n'importe quelle catégorie selon la version ROM.
     */
    private Display findClusterDisplay(DisplayManager dm) {
        // Stratégie 1 : displays de catégorie PRESENTATION
        Display[] presentations = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        if (presentations != null) {
            for (Display d : presentations) {
                if (d.getDisplayId() != 0) {
                    Log.d(TAG, "Candidat PRESENTATION : id=" + d.getDisplayId() + " name=" + d.getName());
                    return d;
                }
            }
        }
        // Stratégie 2 : n'importe quel display non-default (id != 0)
        Display[] all = dm.getDisplays();
        if (all != null) {
            for (Display d : all) {
                if (d.getDisplayId() != 0) {
                    Log.d(TAG, "Candidat non-default : id=" + d.getDisplayId() + " name=" + d.getName());
                    return d;
                }
            }
        }
        return null;
    }

    private boolean isClusterDisplay(DisplayManager dm, Display d) {
        // Un display est considéré cluster si ce n'est pas le display principal (id=0)
        return d != null && d.getDisplayId() != 0;
    }

    // ── Annulation ──────────────────────────────────────────────────────────

    /**
     * Annule toutes les opérations en cours : polls Handler, timeout, et DisplayListener.
     * DOIT être appelé par DashboardDisplayHelper.stop().
     */
    public void cancel() {
        mHandler.removeCallbacksAndMessages(null);
        if (mActiveDisplayManager != null && mActiveDisplayListener != null) {
            mActiveDisplayManager.unregisterDisplayListener(mActiveDisplayListener);
            mActiveDisplayListener = null;
            mActiveDisplayManager  = null;
        }
        Log.d(TAG, "cancel() — Handler et DisplayListener annulés");
    }
}
