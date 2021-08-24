/*
 * Copyright 2017 The Android Open Source Project
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

package androidx.work.impl.background.systemjob;

import static androidx.annotation.VisibleForTesting.PACKAGE_PRIVATE;

import android.app.job.JobInfo;
import android.content.ComponentName;
import android.content.Context;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.PersistableBundle;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ContentUriTriggers;
import androidx.work.Logger;
import androidx.work.NetworkType;
import androidx.work.impl.WorkManagerImpl;
import androidx.work.impl.model.WorkSpec;

/**
 * Converts a {@link WorkSpec} into a JobInfo.
 *
 * @hide
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
@RequiresApi(api = WorkManagerImpl.MIN_JOB_SCHEDULER_API_LEVEL)
class SystemJobInfoConverter {
    private static final String TAG = Logger.tagWithPrefix("SystemJobInfoConverter");

    static final String EXTRA_WORK_SPEC_ID = "EXTRA_WORK_SPEC_ID";
    static final String EXTRA_IS_PERIODIC = "EXTRA_IS_PERIODIC";

    private final ComponentName mWorkServiceComponent;

    @VisibleForTesting(otherwise = PACKAGE_PRIVATE)
    SystemJobInfoConverter(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        mWorkServiceComponent = new ComponentName(appContext, SystemJobService.class);
    }

    /**
     * Converts a {@link WorkSpec} into a {@link JobInfo}.
     *
     * Note: All {@link JobInfo} are set to persist on reboot.
     *
     * @param workSpec The {@link WorkSpec} to convert
     * @param jobId The {@code jobId} to use. This is useful when de-duping jobs on reschedule.
     * @return The {@link JobInfo} representing the same information as the {@link WorkSpec}
     */
    JobInfo convert(WorkSpec workSpec, int jobId) {
        Constraints constraints = workSpec.constraints;
        PersistableBundle extras = new PersistableBundle();
        extras.putString(EXTRA_WORK_SPEC_ID, workSpec.id);
        extras.putBoolean(EXTRA_IS_PERIODIC, workSpec.isPeriodic());
        JobInfo.Builder builder = new JobInfo.Builder(jobId, mWorkServiceComponent)
                .setRequiresCharging(constraints.requiresCharging())
                .setRequiresDeviceIdle(constraints.requiresDeviceIdle())
                .setExtras(extras);

        setRequiredNetwork(builder, constraints.getRequiredNetworkType());

        if (!constraints.requiresDeviceIdle()) {
            // Device Idle and Backoff Criteria cannot be set together
            int backoffPolicy = workSpec.backoffPolicy == BackoffPolicy.LINEAR
                    ? JobInfo.BACKOFF_POLICY_LINEAR : JobInfo.BACKOFF_POLICY_EXPONENTIAL;
            builder.setBackoffCriteria(workSpec.backoffDelayDuration, backoffPolicy);
        }

        long nextRunTime = workSpec.calculateNextRunTime();
        long now = System.currentTimeMillis();
        long offset = Math.max(nextRunTime - now, 0);

        if (Build.VERSION.SDK_INT <= 28) {
            // Before API 29, Jobs needed at least one constraint. Therefore before API 29 we
            // always setMinimumLatency to make sure we have at least one constraint.
            // See aosp/5434530 & b/6771687
            builder.setMinimumLatency(offset);
        } else  {
            if (offset > 0) {
                // Only set a minimum latency when applicable.
                builder.setMinimumLatency(offset);
            } else {
                builder.setImportantWhileForeground(true);
            }
        }

        if (Build.VERSION.SDK_INT >= 24 && constraints.hasContentUriTriggers()) {
            ContentUriTriggers contentUriTriggers = constraints.getContentUriTriggers();
            for (ContentUriTriggers.Trigger trigger : contentUriTriggers.getTriggers()) {
                builder.addTriggerContentUri(convertContentUriTrigger(trigger));
            }
            builder.setTriggerContentUpdateDelay(constraints.getTriggerContentUpdateDelay());
            builder.setTriggerContentMaxDelay(constraints.getTriggerMaxContentDelay());
        }

        // We don't want to persist these jobs because we reschedule these jobs on BOOT_COMPLETED.
        // That way ForceStopRunnable correctly reschedules Jobs when necessary.
        builder.setPersisted(false);
        if (Build.VERSION.SDK_INT >= 26) {
            builder.setRequiresBatteryNotLow(constraints.requiresBatteryNotLow());
            builder.setRequiresStorageNotLow(constraints.requiresStorageNotLow());
        }
        return builder.build();
    }

    @RequiresApi(24)
    private static JobInfo.TriggerContentUri convertContentUriTrigger(
            ContentUriTriggers.Trigger trigger) {
        int flag = trigger.shouldTriggerForDescendants()
                ? JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS : 0;
        return new JobInfo.TriggerContentUri(trigger.getUri(), flag);
    }

    /**
     * Adds the required network capabilities on the {@link JobInfo.Builder} instance.
     *
     * @param builder     The instance of {@link JobInfo.Builder}.
     * @param networkType The {@link NetworkType} instance.
     */
    static void setRequiredNetwork(
            @NonNull JobInfo.Builder builder,
            @NonNull NetworkType networkType) {

        if (Build.VERSION.SDK_INT >= 30 && networkType == NetworkType.TEMPORARILY_UNMETERED) {
            NetworkRequest networkRequest = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)
                    .build();

            builder.setRequiredNetwork(networkRequest);
        } else {
            builder.setRequiredNetworkType(convertNetworkType(networkType));
        }
    }

    /**
     * Converts {@link NetworkType} into {@link JobInfo}'s network values.
     *
     * @param networkType The {@link NetworkType} network type
     * @return The {@link JobInfo} network type
     */
    @SuppressWarnings("MissingCasesInEnumSwitch")
    static int convertNetworkType(NetworkType networkType) {
        switch(networkType) {
            case NOT_REQUIRED:
                return JobInfo.NETWORK_TYPE_NONE;
            case CONNECTED:
                return JobInfo.NETWORK_TYPE_ANY;
            case UNMETERED:
                return JobInfo.NETWORK_TYPE_UNMETERED;
            case NOT_ROAMING:
                if (Build.VERSION.SDK_INT >= 24) {
                    return JobInfo.NETWORK_TYPE_NOT_ROAMING;
                }
                break;
            case METERED:
                if (Build.VERSION.SDK_INT >= 26) {
                    return JobInfo.NETWORK_TYPE_METERED;
                }
                break;
        }
        Logger.get().debug(TAG, String.format(
                "API version too low. Cannot convert network type value %s", networkType));
        return JobInfo.NETWORK_TYPE_ANY;
    }
}