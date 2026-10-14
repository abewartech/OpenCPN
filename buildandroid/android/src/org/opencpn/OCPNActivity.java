package org.opencpn;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.DownloadManager.Query;
import android.app.DownloadManager.Request;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.opencpn.opencpn.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import ar.com.daidalos.afiledialog.FileChooserActivity;

/**
 * OCPNActivity — the OpenCPN Android activity.
 *
 * <p>Extends Qt's {@code QtActivity} with ONLY the OpenCPN Java bridge:
 * every method the native layer calls on the activity object via
 * {@code callActivityMethod_*} / {@code callObjectMethod} in
 * {@code android/androidUTIL.cpp} and {@code libs/android_jvm/}.
 *
 * <p>History: the fork previously shipped a 3700-line copy of Qt's own
 * QtActivity (Ministro era) with these bridge methods mixed in. That file
 * was deleted in c07eb22 as a supposed "duplicate Qt source", which silently
 * killed the whole native&lt;-&gt;Java bridge: Settings, file chooser, SAF,
 * GPS queries, fullscreen, busy indicator, downloads, Bluetooth, migration
 * — all became NoSuchMethodError at runtime. This subclass restores the
 * bridge without copying any Qt code: Qt's own QtActivity (provided by
 * androiddeployqt) owns the Qt lifecycle; this class owns the OpenCPN parts.
 *
 * <p>Deliberately NOT restored (dead in the current build):
 * <ul>
 *   <li>ActionBar menu / nav spinner — the app theme is
 *       {@code Theme.Material.NoActionBar}; chart-type switching lives in
 *       the in-app wx UI. The state setters are kept as harmless no-ops
 *       because native still calls them.</li>
 *   <li>Google Maps overlay ({@code OCPNMapsActivity}) and Play-Services
 *       license — removed from the build; {@code getGMAPILicense} returns "".</li>
 *   <li>Play Licensing (LVL) — removed; the app is GPL, no license check.</li>
 *   <li>Ministro / assetbridge / plugin relocation — Qt 5.15 androiddeployqt
 *       handles library deployment.</li>
 *   <li>USB serial ports — no USB-serial driver is bundled; the serial
 *       methods return "NOK"/empty. Bluetooth SPP (NMEA over BT) IS
 *       implemented with the platform Bluetooth stack.</li>
 *   <li>{@code buildSVGIcon} — native renders SVG with lunasvg now; the Java
 *       renderer never existed. Returns "NOK", native falls back.</li>
 * </ul>
 */
public class OCPNActivity extends org.qtproject.qt5.android.bindings.QtActivity {

    private static final String TAG = "OpenCPN";

    // Request codes for startActivityForResult (must not collide with Qt's).
    private static final int OCPN_SAF_REQUEST_CODE = 0x5af1;
    private static final int OCPN_SETTINGS_REQUEST_CODE = 0xf3ef;
    private static final int OCPN_FILECHOOSER_REQUEST_CODE = 0x5555;
    private static final int OCPN_AFILECHOOSER_REQUEST_CODE = 0x5556;
    private static final int OCPN_DOC_REQUEST_CODE = 0x5557;

    // Command ids sent to native via invokeCmdEventCmdString (must match
    // the native command table).
    private static final int ID_CMD_APPLY_SETTINGS = 300;
    private static final int ID_CMD_NULL_REFRESH = 301;

    // Serial Port Profile UUID for Bluetooth NMEA connections.
    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private OCPNNativeLib nativeLib;
    private String m_settingsReturn = "";

    private boolean m_fullScreen;
    private boolean m_backButtonEnable = true;
    private boolean m_trackContinuous;
    private boolean m_optionsMenuEnabled = true;
    private boolean m_optionItemsEnabled = true;

    // File-chooser polling state (native polls isFileChooserFinished).
    private boolean m_FileChooserDone;
    private String m_filechooserString = "no";

    // SAF-permission / migration polling state (native polls
    // isSAFChooserFinished).
    private String m_safChooserResult = "no";

    // Color-picker polling state (native polls isColorPickerDialogFinished).
    private String m_colorPickerResult = "no";

    // Async HTTP POST state (native polls checkPostAsync).
    private String m_postResult = "ACTIVE";

    // Unzip state (native polls getUnzipStatus).
    private volatile String m_unzipStatus = "DONE";

    // Migration state (native polls getMigrateStatus).
    private volatile String m_migrateStatus = "";
    private volatile boolean m_migrateCancelled;

    // GPS service state.
    private Boolean m_GPSServiceStarted = false;
    private GPSServer m_GPSServer;
    public boolean m_hasGPS;

    // Bluetooth.
    private BTScanHelper scanHelper;
    private Boolean m_ScanHelperStarted = false;
    private volatile BluetoothSocket m_btSocket;
    private volatile boolean m_btStopReader;
    private Thread m_btReaderThread;

    // DownloadManager.
    private DownloadManager m_dm;
    private long m_enqueue;
    private BroadcastReceiver downloadBCReceiver;
    private String m_downloadRet = "";
    private Semaphore mutex = new Semaphore(0);

    // Busy indicator.
    public ProgressDialog ringProgressDialog;

    // Sound playback.
    private MediaPlayer mediaPlayer;

    // Multicast (AIS / NMEA multicast reception).
    private WifiManager.MulticastLock m_multicastLock;

    // ------------------------------------------------------------------
    // Lifecycle: Qt owns the Qt part via super.*; we add the OpenCPN part.
    // ------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        nativeLib = new OCPNNativeLib();
    }

    @Override
    protected void onPause() {
        safeNative(new Runnable() {
            @Override public void run() { nativeLib.onPause(); }
        });
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        safeNative(new Runnable() {
            @Override public void run() { nativeLib.onResume(); }
        });
    }

    @Override
    protected void onStop() {
        safeNative(new Runnable() {
            @Override public void run() { nativeLib.onStop(); }
        });
        super.onStop();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        final int lvl = level;
        safeNative(new Runnable() {
            @Override public void run() { nativeLib.onTrimMemory(lvl); }
        });
    }

    /**
     * Run a nativeLib call that must never crash the activity: the native
     * library may not be loaded yet (or ever, in a partial build).
     */
    private void safeNative(Runnable r) {
        if (nativeLib == null) return;
        try {
            r.run();
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "native call failed: lib not loaded", e);
        } catch (Exception e) {
            Log.w(TAG, "native call failed", e);
        }
    }

    // ------------------------------------------------------------------
    // Results from activities we launched.
    // ------------------------------------------------------------------

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == OCPN_SAF_REQUEST_CODE) {
            handleSafResult(resultCode, data);
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (requestCode == OCPN_SETTINGS_REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                m_settingsReturn = data.getStringExtra("SettingsString");
                if (m_settingsReturn == null) m_settingsReturn = "";
                final String settings = m_settingsReturn;
                safeNative(new Runnable() {
                    @Override public void run() {
                        nativeLib.invokeCmdEventCmdString(ID_CMD_NULL_REFRESH, settings);
                    }
                });
                // Defer applying settings until the screen refreshes.
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override public void run() {
                        safeNative(new Runnable() {
                            @Override public void run() {
                                nativeLib.invokeCmdEventCmdString(ID_CMD_APPLY_SETTINGS, settings);
                            }
                        });
                    }
                }, 100);
            }
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (requestCode == OCPN_FILECHOOSER_REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                m_filechooserString = "file:" + data.getStringExtra("itemSelected");
            } else if (resultCode == RESULT_CANCELED) {
                m_filechooserString = "cancel:";
            }
            m_FileChooserDone = true;
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (requestCode == OCPN_AFILECHOOSER_REQUEST_CODE) {
            // SAF folder picker (FileChooserActivity): folder path or SAF URI.
            if (resultCode == RESULT_OK && data != null) {
                Bundle bundle = data.getExtras();
                if (bundle != null) {
                    if (bundle.containsKey(FileChooserActivity.OUTPUT_NEW_FILE_NAME)) {
                        File folder = (File) bundle.get(FileChooserActivity.OUTPUT_FILE_OBJECT);
                        String name = bundle.getString(FileChooserActivity.OUTPUT_NEW_FILE_NAME);
                        m_filechooserString = "file:" + folder.getAbsolutePath() + "/" + name;
                    } else if (bundle.containsKey(FileChooserActivity.OUTPUT_FILE_OBJECT)) {
                        File file = (File) bundle.get(FileChooserActivity.OUTPUT_FILE_OBJECT);
                        m_filechooserString = "file:" + file.getAbsolutePath();
                    } else if (bundle.containsKey(FileChooserActivity.OUTPUT_SAF_URI)) {
                        m_filechooserString = "file:"
                                + bundle.getString(FileChooserActivity.OUTPUT_SAF_URI);
                    } else {
                        m_filechooserString = "cancel:";
                    }
                } else {
                    m_filechooserString = "cancel:";
                }
            } else {
                m_filechooserString = "cancel:";
            }
            m_FileChooserDone = true;
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (requestCode == OCPN_DOC_REQUEST_CODE) {
            // Single-document picker (ACTION_OPEN_DOCUMENT): copy the content
            // into the app cache and hand native a real filesystem path.
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri docUri = data.getData();
                String cached = copyDocumentToCache(docUri);
                m_filechooserString = (cached != null) ? "file:" + cached : "cancel:";
            } else {
                m_filechooserString = "cancel:";
            }
            m_FileChooserDone = true;
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /**
     * Copy a SAF document's content into the app cache dir so the native
     * layer (which only understands filesystem paths) can read it.
     * Returns the cache path, or null on failure.
     */
    private String copyDocumentToCache(Uri docUri) {
        try {
            try {
                getContentResolver().takePersistableUriPermission(docUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
                // Non-persistable grant: still readable for this session.
            }
            String name = queryDisplayName(docUri);
            if (name == null || name.isEmpty()) name = "picked_file";
            File out = new File(new File(getCacheDir(), "picked"), name);
            File parent = out.getParentFile();
            if (parent != null) parent.mkdirs();
            InputStream in = getContentResolver().openInputStream(docUri);
            if (in == null) return null;
            OutputStream os = new FileOutputStream(out);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) os.write(buf, 0, n);
            os.close();
            in.close();
            return out.getAbsolutePath();
        } catch (Exception e) {
            Log.w(TAG, "copyDocumentToCache failed", e);
            return null;
        }
    }

    private String queryDisplayName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(
                        android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        String path = uri.getLastPathSegment();
        if (path != null) {
            int slash = path.lastIndexOf('/');
            if (slash >= 0) return path.substring(slash + 1);
            return path;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    /** Launch the native settings UI. Result arrives in onActivityResult. */
    public String doAndroidSettings(String settings) {
        m_settingsReturn = "";
        Intent intent = new Intent(this, OCPNSettingsActivity.class);
        intent.putExtra("SETTINGS_STRING", settings);
        startActivityForResult(intent, OCPN_SETTINGS_REQUEST_CODE);
        return "55";
    }

    /** Polled by native after the settings activity closes. */
    public String checkAndroidSettings() {
        return m_settingsReturn;
    }

    // ------------------------------------------------------------------
    // File chooser (native polls isFileChooserFinished).
    //
    // FileChooserDialog = pick a FILE (GPX import, sound file, ...).
    // DirChooserDialog  = pick a DIRECTORY (chart dirs, ...).
    // Both show OCPNFileDialog, the modern in-app filesystem browser that
    // works on shared storage once All-files access is granted (native
    // requests it before calling here). Results flow through the polling
    // contract: "no" | "cancel:" | "file:<absolute path>".
    // ------------------------------------------------------------------

    public String FileChooserDialog(final String initialDir, final String title,
                                    final String suggestion, final String wildcard) {
        m_FileChooserDone = false;
        m_filechooserString = "no";
        final boolean allowCreate = suggestion != null && !suggestion.isEmpty();
        OCPNFileDialog.showFileDialog(this, initialDir,
                new OCPNFileDialog.Callback() {
                    @Override public void onFinished(String path) {
                        if ("cancel".equals(path)) {
                            m_filechooserString = "cancel:";
                        } else {
                            m_filechooserString = path;
                        }
                        m_FileChooserDone = true;
                    }
                }, false, allowCreate);
        return "OK";
    }

    public String DirChooserDialog(final String initialDir, final String title) {
        m_FileChooserDone = false;
        m_filechooserString = "no";
        OCPNFileDialog.showFileDialog(this, initialDir,
                new OCPNFileDialog.Callback() {
                    @Override public void onFinished(String path) {
                        if ("cancel".equals(path)) {
                            m_filechooserString = "cancel:";
                        } else {
                            m_filechooserString = path;
                        }
                        m_FileChooserDone = true;
                    }
                }, true, true);
        return "OK";
    }

    public String isFileChooserFinished() {
        if (m_FileChooserDone) return m_filechooserString;
        return "no";
    }

    // ------------------------------------------------------------------
    // Storage Access Framework (SAF) + All-files access.
    // ------------------------------------------------------------------

    /**
     * Returns "OK" when a persistable read+write grant exists for the tree
     * document {@code docId} (e.g. "primary:Documents").
     */
    public String CheckSAFPermission(final String docId) {
        if (docId == null) return "";
        try {
            for (android.content.UriPermission perm :
                    getContentResolver().getPersistedUriPermissions()) {
                String treeDoc;
                try {
                    treeDoc = DocumentsContract.getTreeDocumentId(perm.getUri());
                } catch (IllegalArgumentException notATree) {
                    continue;
                }
                if (docId.equals(treeDoc)
                        && perm.isReadPermission() && perm.isWritePermission()) {
                    return "OK";
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "CheckSAFPermission failed", e);
        }
        return "";
    }

    /**
     * Open the system directory picker for a persistable SAF grant.
     * Async: the grant (or denial) arrives in onActivityResult.
     */
    public String DoSAFPermissionDialog() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            startActivityForResult(intent, OCPN_SAF_REQUEST_CODE);
        } catch (Exception e) {
            Log.w(TAG, "DoSAFPermissionDialog failed", e);
        }
        return "OK";
    }

    private void handleSafResult(int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            m_safChooserResult = "cancel:";
            return;
        }
        Uri treeUri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            Log.i(TAG, "SAF grant persisted: " + treeUri);
        } catch (Exception e) {
            Log.w(TAG, "takePersistableUriPermission failed", e);
        }
        String path = resolveTreePath(treeUri);
        m_safChooserResult = "file:" + (path != null ? path : treeUri.toString());
    }

    /** Polled by native (migration flow) for the SAF picker result. */
    public String isSAFChooserFinished() {
        return m_safChooserResult;
    }

    /**
     * Resolve a SAF tree URI to a filesystem path for primary shared storage
     * (content://com.android.externalstorage.documents/tree/primary:DIR)
     * and secondary volumes (/storage/&lt;volumeId&gt;/...).
     * Returns null when the URI cannot be mapped to a direct path.
     */
    private String resolveTreePath(Uri treeUri) {
        try {
            if (!"com.android.externalstorage.documents".equals(treeUri.getAuthority())) {
                return null;
            }
            String docId = DocumentsContract.getTreeDocumentId(treeUri);
            if (docId == null) return null;
            String[] parts = docId.split(":", 2);
            if (parts.length != 2) return null;
            if ("primary".equalsIgnoreCase(parts[0])) {
                File primary = android.os.Environment.getExternalStorageDirectory();
                return new File(primary, parts[1]).getAbsolutePath();
            }
            return new File("/storage/" + parts[0], parts[1]).getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    /** Returns "OK" when the app may read arbitrary shared-storage paths. */
    public String CheckAllFilesAccess() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                return android.os.Environment.isExternalStorageManager() ? "OK" : "";
            }
        } catch (Exception e) {
            Log.w(TAG, "CheckAllFilesAccess failed", e);
        }
        return "OK";  // pre-30: legacy storage perms cover this
    }

    /** Opens the system "All files access" settings page for this app. */
    public String RequestAllFilesAccess() {
        try {
            Intent intent;
            if (Build.VERSION.SDK_INT >= 30) {
                intent = new Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
            } else {
                intent = new Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + getPackageName()));
            }
            startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "RequestAllFilesAccess failed", e);
        }
        return "OK";
    }

    // ------------------------------------------------------------------
    // Device info, display, memory
    // ------------------------------------------------------------------

    public String getDeviceInfo() {
        String s = "Device Info:";
        s += "\n OS Version: " + System.getProperty("os.version")
                + "(" + android.os.Build.VERSION.INCREMENTAL + ")";
        s += "\n OS API Level: " + android.os.Build.VERSION.RELEASE
                + "(" + android.os.Build.VERSION.SDK_INT + ")";
        s += "\n Device: " + android.os.Build.DEVICE;
        s += "\n Model (and Product): " + android.os.Build.MODEL
                + " (" + android.os.Build.PRODUCT + ")";
        return s;
    }

    /**
     * Output format is a native contract (parsed in androidUTIL.cpp):
     *   xdpi;density;densityDpi;width;height-statusBar;width;height;
     *   dm.widthPixels;dm.heightPixels;actionBarHeight;textSize
     * Width/height are the *real* display size (incl. system bars) so the
     * chart renderer can go edge-to-edge; the status-bar height is also
     * reported separately for inset calculations.
     */
    public String getDisplayMetrics() {
        DisplayMetrics dm = getResources().getDisplayMetrics();

        int statusBarHeight = 0;
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            statusBarHeight = getResources().getDimensionPixelSize(resourceId);
        }

        int width;
        int height;
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowMetrics wm = getWindowManager().getCurrentWindowMetrics();
            android.graphics.Rect bounds = wm.getBounds();
            width = bounds.width();
            height = bounds.height();
        } else {
            // API 24-29: getRealMetrics is deprecated in 31 but correct here.
            Display display = getWindowManager().getDefaultDisplay();
            DisplayMetrics real = new DisplayMetrics();
            display.getRealMetrics(real);
            width = real.widthPixels;
            height = real.heightPixels;
            dm = real;
        }

        float tsize = new Button(this).getTextSize();  // in pixels

        return String.format(Locale.US, "%f;%f;%d;%d;%d;%d;%d;%d;%d;%d;%f",
                dm.xdpi, dm.density, dm.densityDpi,
                width, height - statusBarHeight,
                width, height,
                dm.widthPixels, dm.heightPixels, 0, tsize);
    }

    public String getMemInfo(int pid) {
        int[] pids = new int[1];
        pids[0] = pid;
        android.app.ActivityManager activityManager =
                (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        android.os.Debug.MemoryInfo[] memoryInfoArray =
                activityManager.getProcessMemoryInfo(pids);
        int pss = memoryInfoArray[0].getTotalPss();
        return String.format(Locale.US, "%d", pss);
    }

    public String callFromCpp(int pid) {
        return getMemInfo(pid);
    }

    public String getAndroidVersionCode() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= 28) {
                return String.valueOf(pi.getLongVersionCode());
            }
            return String.valueOf(pi.versionCode);
        } catch (Exception e) {
            return "0";
        }
    }

    public String getAndroidVersionName() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName != null ? pi.versionName : "";
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------
    // Fullscreen / rotation / back button / menu state.
    //
    // Fullscreen uses the platform WindowInsets APIs (no Qt-delegate
    // dependency). The menu/state setters are kept because native calls
    // them; with the NoActionBar theme they only record state — the
    // in-app wx UI owns the visible controls.
    // ------------------------------------------------------------------

    public String setFullscreen(final int bfull) {
        setFullscreen(bfull != 0);
        m_fullScreen = (bfull != 0);
        return "OK";
    }

    public void setFullscreen(final boolean bfull) {
        m_fullScreen = bfull;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    if (Build.VERSION.SDK_INT >= 30) {
                        getWindow().setDecorFitsSystemWindows(!bfull);
                        WindowInsetsController c = getWindow().getInsetsController();
                        if (c != null) {
                            if (bfull) {
                                c.hide(WindowInsets.Type.statusBars()
                                        | WindowInsets.Type.navigationBars());
                                c.setSystemBarsBehavior(
                                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                            } else {
                                c.show(WindowInsets.Type.statusBars()
                                        | WindowInsets.Type.navigationBars());
                            }
                        }
                    } else {
                        int flags = android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
                        if (bfull) {
                            flags |= android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                    | android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                                    | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
                        }
                        getWindow().getDecorView().setSystemUiVisibility(flags);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "setFullscreen failed", e);
                }
            }
        });
    }

    public String getFullscreen() {
        return m_fullScreen ? "YES" : "NO";
    }

    public String EnableRotation() {
        setRequestedOrientation(
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        return "OK";
    }

    public String DisableRotation() {
        int orientation = getResources().getConfiguration().orientation;
        int lock = (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
                ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
        setRequestedOrientation(lock);
        return "OK";
    }

    public String getScreenOrientation() {
        return String.valueOf(getResources().getConfiguration().orientation);
    }

    public String setBackButtonState(final int isActive) {
        m_backButtonEnable = (isActive != 0);
        return "OK";
    }

    public String setRouteAnnunciator(final int viz) {
        runOnUiThread(new Runnable() {
            @Override public void run() { invalidateOptionsMenu(); }
        });
        return "OK";
    }

    public String setFollowIconState(final int isActive) {
        runOnUiThread(new Runnable() {
            @Override public void run() { invalidateOptionsMenu(); }
        });
        return "OK";
    }

    public String setTrackIconState(final int isActive) {
        runOnUiThread(new Runnable() {
            @Override public void run() { invalidateOptionsMenu(); }
        });
        return "OK";
    }

    public String enableOptionItemAction(final int benable) {
        m_optionItemsEnabled = (benable != 0);
        return "OK";
    }

    public String enableOptionsMenu(final int benable) {
        m_optionsMenuEnabled = (benable != 0);
        return "OK";
    }

    public String setTrackContinuous(final int trackContinuous) {
        m_trackContinuous = (trackContinuous != 0);
        return "OK";
    }

    /** End-of-init hook; native ignores the return. */
    public String lastCallOnInit(final int parm) {
        return "OK";
    }

    /**
     * Chart-type spinner (raster/vector/CM93). No-op under the NoActionBar
     * theme: the in-app wx UI owns chart-type switching.
     */
    public String configureNavSpinnerTS(final int flag, final int sel) {
        return "OK";
    }

    // ------------------------------------------------------------------
    // Toasts
    // ------------------------------------------------------------------

    public String showToast(final String message) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                Toast.makeText(getApplicationContext(), message,
                        Toast.LENGTH_SHORT).show();
            }
        });
        return "OK";
    }

    private Toast m_timedToast;

    public String showTimedToast(final String message, final int timeMillisec) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (m_timedToast != null) m_timedToast.cancel();
                m_timedToast = Toast.makeText(getApplicationContext(), message,
                        Toast.LENGTH_LONG);
                m_timedToast.show();
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override public void run() {
                        if (m_timedToast != null) {
                            m_timedToast.cancel();
                            m_timedToast = null;
                        }
                    }
                }, timeMillisec);
            }
        });
        return "OK";
    }

    public String cancelTimedToast() {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (m_timedToast != null) {
                    m_timedToast.cancel();
                    m_timedToast = null;
                }
            }
        });
        return "OK";
    }

    // ------------------------------------------------------------------
    // Busy indicator
    // ------------------------------------------------------------------

    public String showBusyCircle() {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    ringProgressDialog =
                            new ProgressDialog(OCPNActivity.this, R.style.MyTheme);
                    ringProgressDialog.setCancelable(false);
                    ringProgressDialog.setProgressStyle(
                            android.R.style.Widget_ProgressBar_Small);
                    Drawable myIcon = getResources().getDrawable(
                            R.drawable.progressbar_custom);
                    ringProgressDialog.setIndeterminateDrawable(myIcon);
                    // Keep the busy spinner from surfacing the hidden
                    // navigation buttons.
                    ringProgressDialog.getWindow().setFlags(
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
                    ringProgressDialog.show();
                } catch (Exception e) {
                    Log.w(TAG, "showBusyCircle failed", e);
                }
            }
        });
        return "";
    }

    public String hideBusyCircle() {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    if (ringProgressDialog != null) {
                        ringProgressDialog.dismiss();
                        ringProgressDialog = null;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "hideBusyCircle failed", e);
                }
            }
        });
        return "";
    }

    // ------------------------------------------------------------------
    // Locale / timezone / network / help / browser
    // ------------------------------------------------------------------

    public String getAndroidLocaleString() {
        return Locale.getDefault().toString();
    }

    public String getAndroidTZOffsetMinutes() {
        int offsetMillis = TimeZone.getDefault()
                .getOffset(System.currentTimeMillis());
        return String.valueOf(offsetMillis / 60000);
    }

    public String getIpAddress() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return "";
            int ip = wm.getConnectionInfo().getIpAddress();
            return (ip & 0xff) + "." + ((ip >> 8) & 0xff) + "."
                    + ((ip >> 16) & 0xff) + "." + ((ip >> 24) & 0xff);
        } catch (Exception e) {
            return "";
        }
    }

    public String isNetworkAvailable() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return "NO";
            if (Build.VERSION.SDK_INT >= 23) {
                Network net = cm.getActiveNetwork();
                if (net == null) return "NO";
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                return (caps != null
                        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                        ? "YES" : "NO";
            }
            android.net.NetworkInfo info = cm.getActiveNetworkInfo();
            return (info != null && info.isConnected()) ? "YES" : "NO";
        } catch (Exception e) {
            return "NO";
        }
    }

    public String isHelpAvailable() {
        try {
            InputStream in = getAssets().open("doc/doc/help_en_US.html");
            in.close();
            return "YES";
        } catch (Exception e) {
            return "NO";
        }
    }

    public String launchHelpBook() {
        return launchHelpView();
    }

    public String launchHelpView() {
        Intent intent = new Intent(this, WebViewActivity.class);
        startActivity(intent);
        return "OK";
    }

    public String launchWebView(final String url) {
        Intent intent = new Intent(this, WebViewActivity.class);
        intent.putExtra("WEBVIEW_URL", url);
        startActivity(intent);
        return "OK";
    }

    /** Open Play Store search for the OpenCPN help app. */
    public String installPlaystoreHelp() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://search?q=OpenCPN"));
            startActivity(intent);
        } catch (Exception e) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(
                        "https://play.google.com/store/search?q=OpenCPN&c=apps"));
                startActivity(intent);
            } catch (Exception e2) {
                Log.w(TAG, "installPlaystoreHelp failed", e2);
            }
        }
        return "OK";
    }

    /**
     * Play-Services license text. Play Services is not bundled, so there is
     * no license text to report. Kept because native calls it for the
     * About dialog.
     */
    public String getGMAPILicense() {
        return "";
    }

    /** Terminate the app (native androidTerminate). */
    public String terminateApp() {
        finishAffinity();
        return "OK";
    }

    // ------------------------------------------------------------------
    // System directories (native contract: see androidGet*Dir()).
    // Field order: EXTAPP|INTAPP; filesDir; cacheDir; externalFilesDir;
    // externalCacheDir; sharedExternalRoot.
    // ------------------------------------------------------------------

    public String getSystemDirs() {
        String result;
        android.content.pm.ApplicationInfo ai = getApplicationInfo();
        if ((ai.flags & android.content.pm.ApplicationInfo.FLAG_EXTERNAL_STORAGE)
                == android.content.pm.ApplicationInfo.FLAG_EXTERNAL_STORAGE) {
            result = "EXTAPP;";
        } else {
            result = "INTAPP;";
        }
        result = result.concat(dirPath(getFilesDir()) + ";");
        result = result.concat(dirPath(getCacheDir()) + ";");
        result = result.concat(dirPath(getExternalFilesDir(null)) + ";");
        result = result.concat(dirPath(getExternalCacheDir()) + ";");
        result = result.concat(sharedExternalRoot() + ";");
        return result;
    }

    private static String dirPath(File dir) {
        return dir != null ? dir.getPath() : "";
    }

    /**
     * Shared external-storage root (e.g. /storage/emulated/0) without the
     * deprecated Environment.getExternalStorageDirectory(). Derived from the
     * app-specific external files dir, which is always on the primary shared
     * volume. Returns "" when unavailable (scoped-storage devices).
     */
    private String sharedExternalRoot() {
        File ext = getExternalFilesDir(null);
        if (ext == null) return "";
        // .../Android/data/<pkg>/files -> walk up 4 levels to the volume root
        File root = ext;
        for (int i = 0; i < 4 && root != null; i++) root = root.getParentFile();
        return root != null ? root.getPath() : "";
    }

    // ------------------------------------------------------------------
    // GPS (foreground service)
    // ------------------------------------------------------------------

    public String queryGPSServer(final int parm) {
        if (GPSServer.GPS_PROVIDER_AVAILABLE == parm) {
            return m_hasGPS ? "YES" : "NO";
        }
        // GPS tracking runs in a real foreground service (declared in the
        // manifest) so it survives the Activity being backgrounded.
        // GPS_OFF never starts the service: just stop any running instance.
        if (GPSServer.GPS_OFF == parm) {
            GPSServer.stop(getApplicationContext());
            m_GPSServiceStarted = false;
            return "GPS_OFF OK";
        }
        m_GPSServer = GPSServer.obtain(getApplicationContext(), nativeLib, this);
        if (m_GPSServer == null) {
            Log.e(TAG, "queryGPSServer: service instance unavailable");
            return "service unavailable";
        }
        m_GPSServiceStarted = true;
        return m_GPSServer.doService(parm);
    }

    // ------------------------------------------------------------------
    // Bluetooth: discovery (BTScanHelper) + SPP serial (platform stack).
    // The old code used the external BluetoothSPP library, which is not
    // bundled; SPP is re-implemented here with BluetoothSocket so NMEA
    // over Bluetooth works without a new dependency.
    // ------------------------------------------------------------------

    public String hasBluetooth(final int parm) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        return (adapter == null) ? "No" : "Yes";
    }

    public String startBlueToothScan(final int parm) {
        try {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (!m_ScanHelperStarted) {
                        scanHelper = new BTScanHelper(OCPNActivity.this);
                        m_ScanHelperStarted = true;
                    }
                    scanHelper.doDiscovery();
                }
            });
            return "OK";
        } catch (Exception e) {
            Log.w(TAG, "startBlueToothScan failed", e);
            return "NOK";
        }
    }

    public String stopBlueToothScan(final int parm) {
        try {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (m_ScanHelperStarted) {
                        scanHelper.doDiscovery();
                        scanHelper.stopDiscovery();
                    }
                }
            });
            return "OK";
        } catch (Exception e) {
            Log.w(TAG, "stopBlueToothScan failed", e);
            return "NOK";
        }
    }

    public String getBlueToothScanResults(final int parm) {
        if (!m_ScanHelperStarted) {
            scanHelper = new BTScanHelper(this);
            m_ScanHelperStarted = true;
        }
        if (m_ScanHelperStarted) return scanHelper.getDiscoveredDevices();
        return "";
    }

    /** Connect to a Bluetooth SPP device (NMEA source). */
    public String startBTService(final String address) {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) return "NOK.NoAdapter";
            if (!adapter.isEnabled()) return "NOK.BTNotEnabled";
            final BluetoothDevice device = adapter.getRemoteDevice(address);
            if (device == null) return "NOK.BadAddress";
            closeBtSocket();
            m_btStopReader = false;
            Thread connectThread = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        BluetoothSocket socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                        m_btSocket = socket;
                        BluetoothAdapter.getDefaultAdapter().cancelDiscovery();
                        socket.connect();
                        Log.i(TAG, "BT SPP connected: " + device.getAddress());
                        startBtReader();
                    } catch (Exception e) {
                        Log.w(TAG, "BT SPP connect failed", e);
                        closeBtSocket();
                    }
                }
            });
            connectThread.start();
            return "OK";
        } catch (IllegalArgumentException e) {
            return "NOK.BadAddress";
        } catch (SecurityException e) {
            return "NOK.Permission";
        } catch (Exception e) {
            Log.w(TAG, "startBTService failed", e);
            return "NOK";
        }
    }

    public String stopBTService(final int parm) {
        closeBtSocket();
        return "OK";
    }

    public String sendBTMessage(final String payload) {
        try {
            BluetoothSocket socket = m_btSocket;
            if (socket == null || !socket.isConnected()) return "NOK.NotConnected";
            socket.getOutputStream().write(payload.getBytes("UTF-8"));
            socket.getOutputStream().flush();
            return "OK";
        } catch (Exception e) {
            Log.w(TAG, "sendBTMessage failed", e);
            return "NOK";
        }
    }

    private void startBtReader() {
        m_btReaderThread = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    InputStream in = m_btSocket.getInputStream();
                    byte[] buf = new byte[1024];
                    StringBuilder line = new StringBuilder();
                    int n;
                    while (!m_btStopReader && (n = in.read(buf)) != -1) {
                        for (int i = 0; i < n; i++) {
                            char c = (char) (buf[i] & 0xFF);
                            if (c == '\n') {
                                final String sentence = line.toString().trim();
                                line.setLength(0);
                                if (!sentence.isEmpty()) {
                                    safeNative(new Runnable() {
                                        @Override public void run() {
                                            nativeLib.processBTNMEA(sentence);
                                        }
                                    });
                                }
                            } else if (c != '\r') {
                                line.append(c);
                            }
                        }
                    }
                } catch (Exception e) {
                    if (!m_btStopReader) Log.w(TAG, "BT reader ended", e);
                }
            }
        });
        m_btReaderThread.start();
    }

    private void closeBtSocket() {
        m_btStopReader = true;
        try {
            if (m_btSocket != null) m_btSocket.close();
        } catch (Exception ignored) {
        }
        m_btSocket = null;
    }

    // ------------------------------------------------------------------
    // DownloadManager (chart downloads)
    // ------------------------------------------------------------------

    public String downloadFile(final String url, final String destination) {
        m_downloadRet = "";
        if (downloadBCReceiver == null) {
            downloadBCReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(action)) {
                        long downloadId = intent.getLongExtra(
                                DownloadManager.EXTRA_DOWNLOAD_ID, 0);
                        Query query = new Query();
                        query.setFilterById(downloadId);
                        Cursor c = m_dm.query(query);
                        if (c != null) {
                            try {
                                if (c.moveToFirst()) {
                                    String uriString = c.getString(c.getColumnIndex(
                                            DownloadManager.COLUMN_URI));
                                    int columnIndex = c.getColumnIndex(
                                            DownloadManager.COLUMN_STATUS);
                                    int status = c.getInt(columnIndex);
                                    if (DownloadManager.STATUS_SUCCESSFUL == status) {
                                        Log.i(TAG, "Download successful");
                                    }
                                    final int st = status;
                                    final String uri = uriString;
                                    safeNative(new Runnable() {
                                        @Override public void run() {
                                            nativeLib.setDownloadStatus(st, uri);
                                        }
                                    });
                                }
                            } finally {
                                c.close();
                            }
                        }
                    }
                }
            };
            IntentFilter filter = new IntentFilter(
                    DownloadManager.ACTION_DOWNLOAD_COMPLETE);
            // Targeting API 34+: context-registered receivers must declare
            // exportedness.
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(downloadBCReceiver, filter,
                        Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(downloadBCReceiver, filter);
            }
        }

        mutex = new Semaphore(0);
        runOnUiThread(new Runnable() {
            @Override public void run() {
                m_dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                Request request = new Request(Uri.parse(url));
                request.setDestinationUri(Uri.parse(destination));
                m_downloadRet = "PENDING";
                try {
                    m_enqueue = m_dm.enqueue(request);
                    m_downloadRet = "OK;" + String.valueOf(m_enqueue);
                } catch (Exception e) {
                    m_downloadRet = "NOK";
                    Log.w(TAG, "download enqueue failed", e);
                }
                mutex.release();
            }
        });

        try {
            mutex.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return m_downloadRet;
    }

    public String getDownloadStatus(final int id) {
        String ret = "NOSTAT";
        if (m_dm != null) {
            Query query = new Query();
            query.setFilterById(id);
            Cursor c = m_dm.query(query);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        int columnIndex = c.getColumnIndex(DownloadManager.COLUMN_STATUS);
                        int stat = c.getInt(columnIndex);
                        String sofarBytes = c.getString(c.getColumnIndex(
                                DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                        String totalBytes = c.getString(c.getColumnIndex(
                                DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                        ret = stat + ";" + sofarBytes + ";" + totalBytes;
                    }
                } finally {
                    c.close();
                }
            }
        }
        return ret;
    }

    public String cancelDownload(final int id) {
        if (m_dm != null) m_dm.remove(id);
        return "OK";
    }

    // ------------------------------------------------------------------
    // Sound
    // ------------------------------------------------------------------

    /**
     * Play the given file, invoking onSoundDone when completed.
     * @param sound String representation of an opaque pointer handed to
     *        onSoundDone().
     */
    public String playSound(final String fileName, final String sound) {
        if (mediaPlayer == null) {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        }
        runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    mediaPlayer.reset();
                    mediaPlayer.setDataSource(fileName);
                    mediaPlayer.prepare();
                    mediaPlayer.setOnCompletionListener(
                            new MediaPlayer.OnCompletionListener() {
                                @Override public void onCompletion(MediaPlayer mp) {
                                    // Unsigned decimal pointer string.
                                    // (BigInteger, not Long.parseUnsignedLong,
                                    // which needs API 26+; minSdk is 24.)
                                    final long ptr;
                                    try {
                                        ptr = new java.math.BigInteger(sound).longValue();
                                    } catch (Exception e) {
                                        return;
                                    }
                                    safeNative(new Runnable() {
                                        @Override public void run() {
                                            nativeLib.onSoundDone(ptr);
                                        }
                                    });
                                }
                            });
                    mediaPlayer.start();
                } catch (Exception e) {
                    Log.w(TAG, "playSound failed", e);
                }
            }
        });
        return "OK";
    }

    // ------------------------------------------------------------------
    // Multicast (AIS / NMEA multicast reception)
    // ------------------------------------------------------------------

    public String enableMulticast(final int benable) {
        try {
            if (benable != 0) {
                if (m_multicastLock == null) {
                    WifiManager wm = (WifiManager) getApplicationContext()
                            .getSystemService(Context.WIFI_SERVICE);
                    if (wm != null) {
                        m_multicastLock = wm.createMulticastLock("OCPN-Multicast");
                        m_multicastLock.setReferenceCounted(true);
                    }
                }
                if (m_multicastLock != null && !m_multicastLock.isHeld()) {
                    m_multicastLock.acquire();
                }
            } else if (m_multicastLock != null && m_multicastLock.isHeld()) {
                m_multicastLock.release();
            }
            return "OK";
        } catch (Exception e) {
            Log.w(TAG, "enableMulticast failed", e);
            return "NOK";
        }
    }

    // ------------------------------------------------------------------
    // File helpers: copy / delete / list / validate / unzip
    // ------------------------------------------------------------------

    public String SecureFileCopy(final String in, final String out) {
        try {
            InputStream inputStream = new FileInputStream(in);
            OutputStream outputStream = new FileOutputStream(out);
            copyStream(inputStream, outputStream);
            inputStream.close();
            outputStream.close();
            return "OK";
        } catch (Exception e) {
            Log.w(TAG, "SecureFileCopy failed", e);
            return "NOK";
        }
    }

    public String RemoveSystemFile(final String file) {
        try {
            if (new File(file).delete()) return "OK";
            return "NOK";
        } catch (Exception e) {
            return "NOK";
        }
    }

    public String isDirWritable(final String dir) {
        try {
            return new File(dir).canWrite() ? "YES" : "NO";
        } catch (Exception e) {
            return "NO";
        }
    }

    /**
     * Validate a download destination. "Pending" means a SAF dialog is
     * going to run (native treats it as not-yet-validated); anything else
     * means all is well.
     */
    public String validateWriteLocation(final String destination,
                                        final String unused, final int action,
                                        final int unused2) {
        try {
            File dest = new File(destination);
            File parent = dest.getParentFile();
            if (parent != null && parent.canWrite()) return "OK";
            if (dest.exists() && dest.canWrite()) return "OK";
            return "Pending";
        } catch (Exception e) {
            return "Pending";
        }
    }

    /** List files in {@code dir} matching a simple glob filespec ("*.gpx"). */
    public String getAllFilesWithFilespec(final String dir, final String filespec) {
        try {
            File d = new File(dir);
            File[] files = d.listFiles();
            if (files == null) return "";
            String regex = filespec.replace(".", "\\.").replace("*", ".*");
            StringBuilder sb = new StringBuilder();
            for (File f : files) {
                if (f.isFile() && f.getName().matches(regex)) {
                    if (sb.length() > 0) sb.append(";");
                    sb.append(f.getAbsolutePath());
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * SVG icon renderer. Native renders SVG with lunasvg now; the Java
     * renderer never existed. "NOK" makes native fall back to a blank
     * bitmap (see androidUTIL.cpp).
     */
    public String buildSVGIcon(final String filename, final String outPath,
                               final int width, final int height) {
        return "NOK";
    }

    // ------------------------------------------------------------------
    // Unzip (chart archives). Native polls getUnzipStatus until "DONE".
    // ------------------------------------------------------------------

    public String unzipFile(final String zipFile, final String destDir,
                            final String nStrip, final String bRemoveZip) {
        m_unzipStatus = "WORKING";
        final int strip;
        try {
            strip = Integer.parseInt(nStrip);
        } catch (NumberFormatException e) {
            return "NOK";
        }
        final boolean removeZip = "1".equals(bRemoveZip)
                || "true".equalsIgnoreCase(bRemoveZip);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    ZipInputStream zis = new ZipInputStream(
                            new FileInputStream(zipFile));
                    ZipEntry entry;
                    int count = 0;
                    while ((entry = zis.getNextEntry()) != null) {
                        String name = entry.getName();
                        // Strip leading path components (e.g. top-level dir).
                        for (int i = 0; i < strip; i++) {
                            int slash = name.indexOf('/');
                            if (slash < 0) {
                                name = null;
                                break;
                            }
                            name = name.substring(slash + 1);
                        }
                        if (name == null || name.isEmpty()) {
                            zis.closeEntry();
                            continue;
                        }
                        File out = new File(destDir, name);
                        if (entry.isDirectory()) {
                            out.mkdirs();
                        } else {
                            File parent = out.getParentFile();
                            if (parent != null) parent.mkdirs();
                            OutputStream os = new FileOutputStream(out);
                            copyStream(zis, os);
                            os.close();
                            count++;
                        }
                        zis.closeEntry();
                        if ((count % 50) == 0) {
                            m_unzipStatus = "WORKING " + count;
                        }
                    }
                    zis.close();
                    if (removeZip) new File(zipFile).delete();
                    m_unzipStatus = "DONE";
                } catch (Exception e) {
                    Log.w(TAG, "unzip failed", e);
                    m_unzipStatus = "ERROR " + e.getMessage();
                }
            }
        }).start();
        return "OK";
    }

    public String getUnzipStatus(final String unused) {
        return m_unzipStatus;
    }

    private static void copyStream(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
    }

    // ------------------------------------------------------------------
    // Color picker (native spin-polls isColorPickerDialogFinished).
    // Result: "color:<decimal int>" | "cancel:" | "no".
    // ------------------------------------------------------------------

    public String doColorPickerDialog(final int initialColor) {
        m_colorPickerResult = "no";
        runOnUiThread(new Runnable() {
            @Override public void run() {
                int r = (initialColor >> 16) & 0xFF;
                int g = (initialColor >> 8) & 0xFF;
                int b = initialColor & 0xFF;

                LinearLayout layout = new LinearLayout(OCPNActivity.this);
                layout.setOrientation(LinearLayout.VERTICAL);
                layout.setPadding(40, 20, 40, 20);

                final View preview = new View(OCPNActivity.this);
                preview.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 120));
                layout.addView(preview);

                final SeekBar[] bars = new SeekBar[3];
                final TextView[] labels = new TextView[3];
                final String[] names = {"R", "G", "B"};
                final int[] values = {r, g, b};
                for (int i = 0; i < 3; i++) {
                    final int idx = i;
                    labels[i] = new TextView(OCPNActivity.this);
                    labels[i].setText(names[i] + ": " + values[i]);
                    layout.addView(labels[i]);
                    bars[i] = new SeekBar(OCPNActivity.this);
                    bars[i].setMax(255);
                    bars[i].setProgress(values[i]);
                    bars[i].setOnSeekBarChangeListener(
                            new SeekBar.OnSeekBarChangeListener() {
                                @Override public void onProgressChanged(SeekBar sb,
                                        int progress, boolean fromUser) {
                                    values[idx] = progress;
                                    labels[idx].setText(names[idx] + ": " + progress);
                                    updatePreview(preview, values);
                                }
                                @Override public void onStartTrackingTouch(SeekBar sb) {}
                                @Override public void onStopTrackingTouch(SeekBar sb) {}
                            });
                    layout.addView(bars[i]);
                }
                updatePreview(preview, values);

                new AlertDialog.Builder(OCPNActivity.this)
                        .setTitle("Pick color")
                        .setView(layout)
                        .setPositiveButton(android.R.string.ok,
                                new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d, int which) {
                                        int color = 0xFF000000
                                                | (values[0] << 16)
                                                | (values[1] << 8)
                                                | values[2];
                                        // Unsigned decimal: native parses with ToLong.
                                        // (Long.toString, not Integer.toUnsignedString,
                                        // which needs API 26+; minSdk is 24.)
                                        m_colorPickerResult = "color:"
                                                + Long.toString(color & 0xFFFFFFFFL);
                                    }
                                })
                        .setNegativeButton(android.R.string.cancel,
                                new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d, int which) {
                                        m_colorPickerResult = "cancel:";
                                    }
                                })
                        .setOnCancelListener(new DialogInterface.OnCancelListener() {
                            @Override public void onCancel(DialogInterface dialog) {
                                m_colorPickerResult = "cancel:";
                            }
                        })
                        .show();
            }
        });
        return "OK";
    }

    private static void updatePreview(View preview, int[] values) {
        int color = 0xFF000000 | (values[0] << 16) | (values[1] << 8) | values[2];
        preview.setBackgroundColor(color);
    }

    public String isColorPickerDialogFinished() {
        return m_colorPickerResult;
    }

    // ------------------------------------------------------------------
    // Async HTTP POST (native spin-polls checkPostAsync).
    // Result: "ACTIVE" while running, then the response body ("" on error).
    // ------------------------------------------------------------------

    public String doHttpPostAsync(final String url, final String parms,
                                  final String stimeout) {
        m_postResult = "ACTIVE";
        new Thread(new Runnable() {
            @Override public void run() {
                HttpURLConnection conn = null;
                try {
                    int timeout = 30000;
                    try {
                        timeout = Integer.parseInt(stimeout);
                    } catch (NumberFormatException ignored) {
                    }
                    conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(timeout);
                    conn.setReadTimeout(timeout);
                    conn.setRequestProperty("Content-Type",
                            "application/x-www-form-urlencoded");
                    byte[] body = parms.getBytes("UTF-8");
                    conn.setFixedLengthStreamingMode(body.length);
                    OutputStream os = conn.getOutputStream();
                    os.write(body);
                    os.close();
                    InputStream in = conn.getInputStream();
                    StringBuilder sb = new StringBuilder();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        sb.append(new String(buf, 0, n, "UTF-8"));
                    }
                    in.close();
                    m_postResult = sb.toString();
                } catch (Exception e) {
                    Log.w(TAG, "doHttpPostAsync failed", e);
                    m_postResult = "";
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        }).start();
        return "OK";
    }

    public String checkPostAsync() {
        return m_postResult;
    }

    // ------------------------------------------------------------------
    // GRIB plugin activity (plugin only logs the return).
    // ------------------------------------------------------------------

    public String doGRIBActivity(final String json) {
        return "OK";
    }

    // ------------------------------------------------------------------
    // Chart migration: copy a chart tree from shared storage into the
    // app-private storage. Native drives it via:
    //   migrateSetup() -> "OK", then polls isSAFChooserFinished for the
    //   source folder; migrateFolder(src, dst) starts the copy; the
    //   dialog polls getMigrateStatus ("Counting..." / "Migrating d/t;.."
    //   / "Done" / "Error...").
    // ------------------------------------------------------------------

    public String migrateSetup() {
        m_safChooserResult = "no";
        return DoSAFPermissionDialog();
    }

    public String migrateFolder(final String src, final String dst) {
        m_migrateStatus = "Counting...";
        m_migrateCancelled = false;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    List<File> files = new ArrayList<>();
                    collectFiles(new File(src), files);
                    int total = files.size();
                    File destRoot = new File(dst);
                    destRoot.mkdirs();
                    File srcRoot = new File(src);
                    int done = 0;
                    for (File f : files) {
                        if (m_migrateCancelled) {
                            m_migrateStatus = "Cancelled";
                            return;
                        }
                        String rel = f.getAbsolutePath()
                                .substring(srcRoot.getAbsolutePath().length());
                        File out = new File(destRoot, rel);
                        File parent = out.getParentFile();
                        if (parent != null) parent.mkdirs();
                        InputStream in = new FileInputStream(f);
                        OutputStream os = new FileOutputStream(out);
                        copyStream(in, os);
                        in.close();
                        os.close();
                        done++;
                        m_migrateStatus = "Migrating " + done + "/" + total + ";"
                                + f.getName();
                    }
                    m_migrateStatus = "Done";
                } catch (Exception e) {
                    Log.w(TAG, "migrateFolder failed", e);
                    m_migrateStatus = "Error " + e.getMessage();
                }
            }
        }).start();
        return "OK";
    }

    private static void collectFiles(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) collectFiles(f, out);
            else out.add(f);
        }
    }

    public String getMigrateStatus() {
        return m_migrateStatus;
    }

    public String cancelMigration() {
        m_migrateCancelled = true;
        m_migrateStatus = "Cancelled";
        return "OK";
    }

    public String restartOCPNAfterMigrate() {
        try {
            Intent intent = getPackageManager()
                    .getLaunchIntentForPackage(getPackageName());
            if (intent != null) {
                android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                        this, 0, intent,
                        android.app.PendingIntent.FLAG_CANCEL_CURRENT
                                | android.app.PendingIntent.FLAG_IMMUTABLE);
                android.app.AlarmManager am = (android.app.AlarmManager)
                        getSystemService(Context.ALARM_SERVICE);
                am.set(android.app.AlarmManager.RTC,
                        System.currentTimeMillis() + 500, pi);
            }
        } catch (Exception e) {
            Log.w(TAG, "restartOCPNAfterMigrate failed", e);
        }
        finishAffinity();
        return "OK";
    }

    // ------------------------------------------------------------------
    // USB serial ports: no USB-serial driver is bundled in this build.
    // The methods exist so native calls resolve; callers treat the
    // empty/"NOK" results as "no serial ports".
    // ------------------------------------------------------------------

    public String scanSerialPorts(final int parm) {
        return "";
    }

    public String startSerialPort(final String portname, final String baudRate) {
        return "NOK";
    }

    public String stopSerialPort(final String portname) {
        return "OK";
    }

    public String writeSerialPort(final String portname, final String message) {
        return "NOK";
    }
}
