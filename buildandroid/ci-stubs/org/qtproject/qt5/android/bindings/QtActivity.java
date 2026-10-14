package org.qtproject.qt5.android.bindings;

import android.app.Activity;

/**
 * Compile-check stub for Qt's QtActivity.
 * The real class is provided by androiddeployqt at APK build time;
 * this stub only satisfies javac for the standalone Java compile check.
 * Never packaged.
 *
 * The declarations below mirror the real Qt 5.15.2 QtActivity where it
 * matters for subclasses: onCreate() is PUBLIC there (unlike Activity's
 * protected one), so a subclass override must be public too — javac
 * rejects a protected override ("weaker access privileges"). This stub
 * exists precisely to catch that class of error locally: CI once failed
 * on exactly this because the stub did not declare onCreate().
 */
public class QtActivity extends Activity {
    @Override
    public void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }
}
