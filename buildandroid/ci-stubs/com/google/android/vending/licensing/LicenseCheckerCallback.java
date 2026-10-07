package com.google.android.vending.licensing;
// CI compile-check stub for the Play Licensing client. Never packaged.
public interface LicenseCheckerCallback {
    int ERROR_INVALID_PACKAGE_NAME = 1;
    int ERROR_MISSING_PERMISSION = 2;
    int ERROR_NON_MATCHING_UID = 3;
    int ERROR_NOT_MARKET_MANAGED = 4;
    int ERROR_CHECK_IN_PROGRESS = 5;
    int ERROR_INVALID_PUBLIC_KEY = 6;
    void allow(int reason);
    void dontAllow(int reason);
    void applicationError(int errorCode);
}
