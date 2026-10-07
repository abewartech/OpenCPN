package org.opencpn;

import android.location.OnNmeaMessageListener;
import android.util.Log;

import org.opencpn.OCPNNativeLib;

/**
 * Forwards raw NMEA sentences from Android's GNSS stack to the native layer.
 *
 * <p>Uses {@link OnNmeaMessageListener} (API 24+). The old
 * {@code GpsStatus.NmeaListener} was deprecated in API 24 and must not be used
 * with minSdk 24+.
 */
public class OCPNGpsNmeaListener implements OnNmeaMessageListener {

    private static final String TAG = "OCPN-NMEA";

    private final OCPNNativeLib mNativeLib;

    public OCPNGpsNmeaListener(OCPNNativeLib nativelib) {
        this.mNativeLib = nativelib;
    }

    @Override
    public void onNmeaMessage(String nmea, long timestamp) {
        if (nmea == null || mNativeLib == null) return;
        try {
            mNativeLib.processNMEA(nmea);
        } catch (Exception e) {
            Log.w(TAG, "processNMEA failed", e);
        }
    }
}
