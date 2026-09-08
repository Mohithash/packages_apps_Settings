/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings.deviceinfo;

import android.content.Context;
import android.os.UserManager;
import android.os.storage.StorageManager;
import android.text.format.Formatter;

import androidx.annotation.VisibleForTesting;
import androidx.preference.Preference;

import com.android.settings.R;
import com.android.settings.Utils;
import com.android.settings.core.BasePreferenceController;
import com.android.settings.dashboard.profileselector.ProfileSelectFragment.ProfileType;
import com.android.settings.deviceinfo.storage.StorageCacheHelper;
import com.android.settings.flags.Flags;
import com.android.settings.widget.HomepagePreferenceLayoutHelper;
import com.android.settingslib.deviceinfo.PrivateStorageInfo;
import com.android.settingslib.deviceinfo.StorageManagerVolumeProvider;
import com.android.settingslib.utils.ThreadUtils;

import java.text.NumberFormat;
import java.util.concurrent.Future;

public class TopLevelStoragePreferenceController extends BasePreferenceController {

    private final StorageManager mStorageManager;
    private final StorageManagerVolumeProvider mStorageManagerVolumeProvider;

    public TopLevelStoragePreferenceController(Context context, String preferenceKey) {
        super(context, preferenceKey);
        mStorageManager = mContext.getSystemService(StorageManager.class);
        mStorageManagerVolumeProvider = new StorageManagerVolumeProvider(mStorageManager);
    }

    @Override
    public int getAvailabilityStatus() {
        return AVAILABLE;
    }

    @Override
    protected void refreshSummary(Preference preference) {
        if (preference == null) {
            return;
        }

        refreshSummaryThread(preference);
    }

    @VisibleForTesting
    protected Future refreshSummaryThread(Preference preference) {
        int userId = Utils.getCurrentUserIdOfType(
                mContext.getSystemService(UserManager.class), ProfileType.PERSONAL);
        final StorageCacheHelper storageCacheHelper = new StorageCacheHelper(mContext, userId);
        long cachedUsedSize = storageCacheHelper.retrieveUsedSize();
        long cachedTotalSize = storageCacheHelper.retrieveCachedSize().totalSize;
        if (cachedUsedSize != 0 && cachedTotalSize != 0) {
            final String percentage = formatPercentage(cachedUsedSize, cachedTotalSize);
            setTrailing(preference, percentage);
            preference.setSummary(getSummary(percentage, cachedUsedSize, cachedTotalSize));
        }

        return ThreadUtils.postOnBackgroundThread(() -> {
            final PrivateStorageInfo info = PrivateStorageInfo.getPrivateStorageInfo(
                    getStorageManagerVolumeProvider());

            long usedBytes = info.totalBytes - info.freeBytes;
            storageCacheHelper.cacheUsedSize(usedBytes);
            ThreadUtils.postOnMainThread(() -> {
                final String percentage = formatPercentage(usedBytes, info.totalBytes);
                setTrailing(preference, percentage);
                preference.setSummary(getSummary(percentage, usedBytes, info.totalBytes));
            });
        });
    }

    /** Puts the used percentage in the homepage row's trailing slot. */
    private void setTrailing(Preference preference, String percentage) {
        if (!(preference instanceof HomepagePreferenceLayoutHelper.HomepagePreferenceLayout)) {
            return;
        }
        ((HomepagePreferenceLayoutHelper.HomepagePreferenceLayout) preference).getHelper()
                .setTrailingValue(percentage);
    }

    @VisibleForTesting
    protected StorageManagerVolumeProvider getStorageManagerVolumeProvider() {
        return mStorageManagerVolumeProvider;
    }

    /**
     * Formats the used percentage. The homepage row's trailing value and the summary show the same
     * number, so it is formatted once and handed to both.
     */
    private String formatPercentage(long usedBytes, long totalBytes) {
        if (Flags.storageSummaryPercentageAlignment()) {
            NumberFormat numberFormat = NumberFormat.getIntegerInstance();
            int percentValue = totalBytes == 0L ? 0
                    : (int) ((((double) usedBytes) / totalBytes) * 100);
            String localizedDigits = numberFormat.format(percentValue);

            // Wrap digits in the dedicated percentage formatting resource
            // This allows the L10n team to control sign placement visually.
            return mContext.getString(R.string.storage_percentage_format, localizedDigits);
        } else {
            NumberFormat percentageFormat = NumberFormat.getPercentInstance();

            return totalBytes == 0L ? "0"
                    : percentageFormat.format(((double) usedBytes) / totalBytes);
        }
    }

    private String getSummary(String percentage, long usedBytes, long totalBytes) {
        return mContext.getString(R.string.storage_toplevel_summary, percentage,
                Formatter.formatFileSize(mContext, totalBytes - usedBytes));
    }
}
