package com.android.settings.system;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.pm.PackageManager;
import android.ext.LogViewerApp;
import android.text.TextUtils;
import android.util.Log;

import androidx.preference.Preference;

import com.android.settings.core.BasePreferenceController;
import com.android.settings.ext.ExtSettingControllerHelper;

public class LogcatLinkPrefController extends BasePreferenceController {

    private static final String TAG = "LogcatLinkPrefController";

    public LogcatLinkPrefController(Context context, String preferenceKey) {
        super(context, preferenceKey);
    }

    @Override
    public int getAvailabilityStatus() {
        // Check the upstream (system-user / global setting) status first so that a
        // DISABLED_FOR_USER state is not masked by the resolve check below.
        final int status = ExtSettingControllerHelper.getGlobalSettingAvailability(mContext);
        if (status != AVAILABLE) {
            return status;
        }
        // BestROM does not necessarily ship app.grapheneos.logviewer
        // (device/xiaomi/peridot/debloat/Android.bp). Hide the row instead of
        // letting the click throw ActivityNotFoundException.
        if (mContext.getPackageManager().resolveActivity(
                LogViewerApp.getLogcatIntent(), PackageManager.MATCH_DEFAULT_ONLY) == null) {
            return UNSUPPORTED_ON_DEVICE;
        }
        return AVAILABLE;
    }

    @Override
    public boolean handlePreferenceTreeClick(Preference preference) {
        if (!TextUtils.equals(preference.getKey(), getPreferenceKey())) {
            return false;
        }

        try {
            mContext.startActivity(LogViewerApp.getLogcatIntent());
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "no activity for " + LogViewerApp.getPackageName(), e);
        }
        return true;
    }
}
