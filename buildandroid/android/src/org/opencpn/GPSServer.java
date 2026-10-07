package org.opencpn;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.OnNmeaMessageListener;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import org.opencpn.OCPNGpsNmeaListener;
import org.opencpn.OCPNNativeLib;
import org.opencpn.opencpn.R;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Locale;

/**
 * GPS/NMEA tracking as a real Android foreground service.
 *
 * <p>Historical note: this class used to be instantiated with {@code new
 * GPSServer(...)} as a plain object and was never declared in the manifest,
 * so location updates silently stopped whenever the Activity was backgrounded
 * (Android 8+ kills background location access). It is now a proper
 * {@link Service} declared in AndroidManifest.xml with
 * {@code foregroundServiceType="location"}. The legacy {@code doService(int)}
 * entry point used by the native layer is preserved.
 *
 * <p>Uses only non-deprecated APIs (minSdk 24): {@link GnssStatus.Callback}
 * instead of the removed {@code GpsStatus} listener, and
 * {@link android.location.OnNmeaMessageListener} instead of the deprecated
 * {@code GpsStatus.NmeaListener}.
 */
public class GPSServer extends Service {

    private static final String TAG = "OCPN-GPS";

    /** Intent actions for {@link #start(Context, String)}. */
    public static final String ACTION_START = "org.opencpn.action.GPS_START";
    public static final String ACTION_STOP = "org.opencpn.action.GPS_STOP";

    // Legacy command codes, kept for the native/JNI bridge (doService).
    public static final int GPS_OFF = 0;
    public static final int GPS_ON = 1;
    public static final int GPS_PROVIDER_AVAILABLE = 2;
    public static final int GPS_SHOWPREFERENCES = 3;

    private static final String CHANNEL_ID = "ocpn_gps_tracking";
    private static final int NOTIF_ID = 1001;

    private static final long MIN_TIME_MS = 1000;      // 1 s between fixes
    private static final float MIN_DISTANCE_M = 1.0f; // 1 m
    private static final long STALE_AFTER_MS = 15000; // no fix for 15 s => stale

    /** The running instance, set in {@link #onCreate()}. */
    private static volatile GPSServer sInstance;
    private static final Object sInstanceLock = new Object();

    private LocationManager locationManager;
    private OCPNNativeLib nativeLib;
    private WeakReference<Activity> activityRef;

    private final Object lock = new Object();
    private boolean tracking = false;
    private boolean hasFix = false;
    private long lastFixElapsedMs = 0;
    private boolean staleReported = false;

    private volatile double latitude;
    private volatile double longitude;
    private volatile float course;
    private volatile float speed;

    private HandlerThread workerThread;
    private Handler workerHandler;
    private GnssStatus.Callback gnssCallback;
    private OnNmeaMessageListener nmeaListener;
    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            if (location == null) return;
            latitude = location.getLatitude();
            longitude = location.getLongitude();
            if (location.hasBearing()) course = location.getBearing();
            if (location.hasSpeed()) speed = location.getSpeed();
            lastFixElapsedMs = SystemClock.elapsedRealtime();
            staleReported = false;
            hasFix = true;
            if (nativeLib != null) {
                nativeLib.processNMEA(createRMC(true));
            }
        }

        @Override public void onProviderEnabled(String provider) {
            Log.i(TAG, "provider enabled: " + provider);
        }

        @Override public void onProviderDisabled(String provider) {
            Log.i(TAG, "provider disabled: " + provider);
            hasFix = false;
        }

        @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    };

    /** Required no-arg constructor for system instantiation. */
    public GPSServer() {}

    /**
     * Legacy constructor kept for source compatibility. Prefer
     * {@link #obtain(Context, OCPNNativeLib, Activity)}.
     */
    @Deprecated
    public GPSServer(Context context, OCPNNativeLib nativelib, Activity activity) {
        wire(nativelib, activity);
    }

    /** Attach the native bridge and (optionally) the Activity for UI prompts. */
    public void wire(OCPNNativeLib nativelib, Activity activity) {
        this.nativeLib = nativelib;
        this.activityRef = activity != null ? new WeakReference<>(activity) : null;
    }

    /**
     * Start the foreground service (if needed), wire the native bridge, and
     * return the running instance. Safe to call repeatedly. Waits (bounded,
     * off the UI thread) for the service to publish its instance.
     */
    public static GPSServer obtain(Context context, OCPNNativeLib nativelib,
                                   Activity activity) {
        Intent intent = new Intent(context, GPSServer.class)
                .setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
        // The instance is published in onCreate(); wait for it with a bound.
        synchronized (sInstanceLock) {
            long deadline = SystemClock.uptimeMillis() + 2000;
            while (sInstance == null) {
                long remaining = deadline - SystemClock.uptimeMillis();
                if (remaining <= 0) break;
                try {
                    sInstanceLock.wait(remaining);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
        }
        GPSServer inst = sInstance;
        if (inst != null) inst.wire(nativelib, activity);
        return inst;
    }

    /** Convenience: start tracking via intent. */
    public static void start(Context context) {
        Intent intent = new Intent(context, GPSServer.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    /** Convenience: stop tracking via intent. */
    public static void stop(Context context) {
        context.startService(new Intent(context, GPSServer.class).setAction(ACTION_STOP));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        synchronized (sInstanceLock) {
            sInstance = this;
            sInstanceLock.notifyAll();
        }
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        workerThread = new HandlerThread("OCPN-GPS");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());
        createNotificationChannel();
        Log.i(TAG, "service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopTracking();
            stopSelf();
            return START_NOT_STICKY;
        }
        // ACTION_START (or null): become foreground immediately, as required
        // for location foreground services on Android 8+.
        // NOTE: the 3-arg startForeground(id, notification, type) overload only
        // exists on API 29+; call the 2-arg form on older releases.
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIF_ID, buildNotification());
        }
        beginTracking();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopTracking();
        if (workerThread != null) workerThread.quitSafely();
        synchronized (sInstanceLock) {
            if (sInstance == this) sInstance = null;
        }
        Log.i(TAG, "service destroyed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // started-service only; native layer uses the singleton
    }

    // ------------------------------------------------------------------
    // Legacy native bridge (called from QtActivity.queryGPSServer via JNI)
    // ------------------------------------------------------------------

    /**
     * Legacy entry point. GPS_ON starts the foreground service and begins
     * tracking; GPS_OFF stops tracking and the service.
     */
    public String doService(int parm) {
        switch (parm) {
            case GPS_OFF:
                stopTracking();
                stopSelf();
                return "GPS_OFF OK";
            case GPS_ON: {
                String err = beginTracking();
                return err != null ? err : "GPS_ON OK";
            }
            case GPS_PROVIDER_AVAILABLE:
                return hasGPSDevice(this) ? "YES" : "NO";
            case GPS_SHOWPREFERENCES:
                showSettingsAlert();
                return "SETTINGS SHOWN";
            default:
                return "???";
        }
    }

    // ------------------------------------------------------------------
    // Tracking core
    // ------------------------------------------------------------------

    /** Begin location updates. Returns null on success, else an error string. */
    private String beginTracking() {
        synchronized (lock) {
            if (tracking) return null;
            if (locationManager == null) return "no location manager";

            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "location permission not granted");
                requestLocationPermission();
                return "location permission not granted";
            }

            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                Log.w(TAG, "GPS provider disabled");
                return "GPS is disabled";
            }

            try {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, MIN_TIME_MS, MIN_DISTANCE_M,
                        locationListener, workerThread.getLooper());

                nmeaListener = (nmea, timestamp) -> {
                    lastFixElapsedMs = SystemClock.elapsedRealtime();
                    staleReported = false;
                    if (nativeLib != null && nmea != null) {
                        nativeLib.processNMEA(nmea);
                    }
                };
                locationManager.addNmeaListener(nmeaListener, workerHandler);

                gnssCallback = new GnssStatus.Callback() {
                    @Override
                    public void onFirstFix(int ttffMillis) {
                        hasFix = true;
                        Log.i(TAG, "GNSS first fix, ttff=" + ttffMillis + "ms");
                    }

                    @Override
                    public void onSatelliteStatusChanged(GnssStatus status) {
                        int used = 0;
                        for (int i = 0; i < status.getSatelliteCount(); i++) {
                            if (status.usedInFix(i)) used++;
                        }
                        hasFix = used >= 3;
                    }
                };
                locationManager.registerGnssStatusCallback(gnssCallback, workerHandler);

                tracking = true;
                lastFixElapsedMs = SystemClock.elapsedRealtime();
                workerHandler.post(watchdog);
                updateNotification();
                Log.i(TAG, "tracking started");
                return null;
            } catch (SecurityException e) {
                Log.e(TAG, "requestLocationUpdates denied", e);
                return "location permission denied";
            }
        }
    }

    private void stopTracking() {
        synchronized (lock) {
            if (!tracking) return;
            tracking = false;
            try {
                if (locationManager != null) {
                    locationManager.removeUpdates(locationListener);
                    if (nmeaListener != null) {
                        locationManager.removeNmeaListener(nmeaListener);
                    }
                    if (gnssCallback != null) {
                        locationManager.unregisterGnssStatusCallback(gnssCallback);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "error while stopping updates", e);
            }
            nmeaListener = null;
            gnssCallback = null;
            hasFix = false;
            if (workerHandler != null) workerHandler.removeCallbacks(watchdog);
            stopForeground(true);
            Log.i(TAG, "tracking stopped");
        }
    }

    /** Watchdog: synthesize a void RMC when fixes go stale (no polling of GPS). */
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            synchronized (lock) {
                if (!tracking) return;
                long silent = SystemClock.elapsedRealtime() - lastFixElapsedMs;
                if (silent > STALE_AFTER_MS && !staleReported) {
                    staleReported = true;
                    hasFix = false;
                    Log.w(TAG, "location stale after " + silent + "ms");
                    if (nativeLib != null) {
                        nativeLib.processNMEA(createRMC(false));
                    }
                }
                workerHandler.postDelayed(this, 2000);
            }
        }
    };

    private void requestLocationPermission() {
        Activity act = activityRef != null ? activityRef.get() : null;
        if (act != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            act.requestPermissions(
                    new String[]{
                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                            android.Manifest.permission.ACCESS_COARSE_LOCATION},
                    0x0c9a);
        }
    }

    private void showSettingsAlert() {
        Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "cannot open location settings", e);
        }
    }

    public boolean hasGPSDevice(Context context) {
        final LocationManager mgr =
                (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (mgr == null) return false;
        final List<String> providers = mgr.getAllProviders();
        return providers != null && providers.contains(LocationManager.GPS_PROVIDER);
    }

    // ------------------------------------------------------------------
    // Foreground notification
    // ------------------------------------------------------------------

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.gps_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription(getString(R.string.gps_notification_channel_description));
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    @SuppressWarnings("deprecation") // Notification.Builder(Context) for API 24-25
    private Notification buildNotification() {
        Intent tap = new Intent(this, org.qtproject.qt5.android.bindings.QtActivity.class);
        tap.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent tapPi = PendingIntent.getActivity(
                this, 0, tap, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, GPSServer.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle(getString(R.string.gps_notification_title))
                .setContentText(statusLine())
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(tapPi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                        getString(R.string.gps_notification_stop), stopPi)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_NAVIGATION)
                .build();
    }

    private String statusLine() {
        if (!tracking) return getString(R.string.gps_notification_text);
        if (!hasFix) return "Waiting for GPS fix…";
        return String.format(Locale.US, "%.5f, %.5f", latitude, longitude);
    }

    private void updateNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification());
    }

    // ------------------------------------------------------------------
    // NMEA synthesis (RMC) — checksum is computed, not hardcoded
    // ------------------------------------------------------------------

    private String createRMC(boolean valid) {
        StringBuilder body = new StringBuilder("LCRMC,,");
        body.append(valid ? 'A' : 'V').append(',');
        body.append(formatLat()).append(',');
        body.append(formatLon()).append(',');
        // speed in knots
        body.append(String.format(Locale.US, "%.2f,", speed / 0.5144f));
        body.append(String.format(Locale.US, "%.0f,", course));
        body.append(",,,"); // date/magvar unused

        int checksum = 0;
        for (int i = 0; i < body.length(); i++) checksum ^= body.charAt(i);
        return "$" + body + String.format(Locale.US, "*%02X", checksum);
    }

    private String formatLat() {
        double v = Math.abs(latitude);
        double deg = Math.floor(v);
        double min = (v - deg) * 60.0;
        return String.format(Locale.US, "%02.0f%07.4f,%c", deg, min,
                latitude >= 0 ? 'N' : 'S');
    }

    private String formatLon() {
        double v = Math.abs(longitude);
        double deg = Math.floor(v);
        double min = (v - deg) * 60.0;
        return String.format(Locale.US, "%03.0f%07.4f,%c", deg, min,
                longitude >= 0 ? 'E' : 'W');
    }
}
