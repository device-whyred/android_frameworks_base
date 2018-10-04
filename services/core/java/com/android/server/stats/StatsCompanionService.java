/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.server.stats;

import static com.android.internal.util.Preconditions.checkNotNull;

import android.annotation.Nullable;
import android.app.ActivityManagerInternal;
import android.app.AlarmManager;
import android.app.AlarmManager.OnAlarmListener;
import android.app.ProcessMemoryState;
import android.app.StatsManager;
import android.bluetooth.BluetoothActivityEnergyInfo;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.UidTraffic;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.hardware.fingerprint.FingerprintManager;
import android.net.NetworkStats;
import android.net.wifi.IWifiManager;
import android.net.wifi.WifiActivityEnergyInfo;
import android.os.BatteryStatsInternal;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileUtils;
import android.os.IBinder;
import android.os.IStatsCompanionService;
import android.os.IStatsManager;
import android.os.IStoraged;
import android.os.IThermalEventListener;
import android.os.IThermalService;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.StatFs;
import android.os.StatsDimensionsValue;
import android.os.StatsLogEventWrapper;
import android.os.SynchronousResultReceiver;
import android.os.SystemClock;
import android.os.Temperature;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.StorageManager;
import android.telephony.ModemActivityInfo;
import android.telephony.TelephonyManager;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;
import android.util.StatsLog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.procstats.IProcessStats;
import com.android.internal.app.procstats.ProcessStats;
import com.android.internal.net.NetworkStatsFactory;
import com.android.internal.os.BinderCallsStats.ExportedCallStat;
import com.android.internal.os.KernelCpuSpeedReader;
import com.android.internal.os.KernelUidCpuActiveTimeReader;
import com.android.internal.os.KernelUidCpuClusterTimeReader;
import com.android.internal.os.KernelUidCpuFreqTimeReader;
import com.android.internal.os.KernelUidCpuTimeReader;
import com.android.internal.os.KernelWakelockReader;
import com.android.internal.os.KernelWakelockStats;
import com.android.internal.os.LooperStats;
import com.android.internal.os.PowerProfile;
import com.android.internal.os.StoragedUidIoStatsReader;
import com.android.internal.util.DumpUtils;
import com.android.server.BinderCallsStatsService;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.SystemServiceManager;
import com.android.server.storage.DiskStatsFileLogger;
import com.android.server.storage.DiskStatsLoggingService;

import libcore.io.IoUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Helper service for statsd (the native stats management service in cmds/statsd/).
 * Used for registering and receiving alarms on behalf of statsd.
 *
 * @hide
 */
public class StatsCompanionService extends IStatsCompanionService.Stub {
    /**
     * How long to wait on an individual subsystem to return its stats.
     */
    private static final long EXTERNAL_STATS_SYNC_TIMEOUT_MILLIS = 2000;
    private static final long MILLIS_IN_A_DAY = TimeUnit.DAYS.toMillis(1);

    public static final String RESULT_RECEIVER_CONTROLLER_KEY = "controller_activity";
    public static final String CONFIG_DIR = "/data/misc/stats-service";

    static final String TAG = "StatsCompanionService";
    static final boolean DEBUG = false;

    public static final int CODE_DATA_BROADCAST = 1;
    public static final int CODE_SUBSCRIBER_BROADCAST = 1;
    /**
     * The last report time is provided with each intent registered to
     * StatsManager#setFetchReportsOperation. This allows easy de-duping in the receiver if
     * statsd is requesting the client to retrieve the same statsd data. The last report time
     * corresponds to the last_report_elapsed_nanos that will provided in the current
     * ConfigMetricsReport, and this timestamp also corresponds to the
     * current_report_elapsed_nanos of the most recently obtained ConfigMetricsReport.
     */
    public static final String EXTRA_LAST_REPORT_TIME = "android.app.extra.LAST_REPORT_TIME";
    public static final int DEATH_THRESHOLD = 10;

    private final Context mContext;
    private final AlarmManager mAlarmManager;
    @GuardedBy("sStatsdLock")
    private static IStatsManager sStatsd;
    private static final Object sStatsdLock = new Object();

    private final OnAlarmListener mAnomalyAlarmListener = new AnomalyAlarmListener();
    private final OnAlarmListener mPullingAlarmListener = new PullingAlarmListener();
    private final OnAlarmListener mPeriodicAlarmListener = new PeriodicAlarmListener();
    private final BroadcastReceiver mAppUpdateReceiver;
    private final BroadcastReceiver mUserUpdateReceiver;
    private final ShutdownEventReceiver mShutdownEventReceiver;
    private final KernelWakelockReader mKernelWakelockReader = new KernelWakelockReader();
    private final KernelWakelockStats mTmpWakelockStats = new KernelWakelockStats();
    private IWifiManager mWifiManager = null;
    private TelephonyManager mTelephony = null;
    private final StatFs mStatFsData = new StatFs(Environment.getDataDirectory().getAbsolutePath());
    private final StatFs mStatFsSystem =
            new StatFs(Environment.getRootDirectory().getAbsolutePath());
    private final StatFs mStatFsTemp =
            new StatFs(Environment.getDownloadCacheDirectory().getAbsolutePath());
    @GuardedBy("sStatsdLock")
    private final HashSet<Long> mDeathTimeMillis = new HashSet<>();
    @GuardedBy("sStatsdLock")
    private final HashMap<Long, String> mDeletedFiles = new HashMap<>();

    private KernelUidCpuTimeReader mKernelUidCpuTimeReader = new KernelUidCpuTimeReader();
    private KernelCpuSpeedReader[] mKernelCpuSpeedReaders;
    private KernelUidCpuFreqTimeReader mKernelUidCpuFreqTimeReader =
            new KernelUidCpuFreqTimeReader();
    private KernelUidCpuActiveTimeReader mKernelUidCpuActiveTimeReader =
            new KernelUidCpuActiveTimeReader();
    private KernelUidCpuClusterTimeReader mKernelUidCpuClusterTimeReader =
            new KernelUidCpuClusterTimeReader();
    private StoragedUidIoStatsReader mStoragedUidIoStatsReader =
            new StoragedUidIoStatsReader();

    private static IThermalService sThermalService;
    private File mBaseDir =
            new File(SystemServiceManager.ensureSystemDir(), "stats_companion");

    public StatsCompanionService(Context context) {
        super();
        mContext = context;
        mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        mBaseDir.mkdirs();
        mAppUpdateReceiver = new AppUpdateReceiver();
        mUserUpdateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                synchronized (sStatsdLock) {
                    sStatsd = fetchStatsdService();
                    if (sStatsd == null) {
                        Slog.w(TAG, "Could not access statsd for UserUpdateReceiver");
                        return;
                    }
                    try {
                        // Pull the latest state of UID->app name, version mapping.
                        // Needed since the new user basically has a version of every app.
                        informAllUidsLocked(context);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Failed to inform statsd latest update of all apps", e);
                        forgetEverythingLocked();
                    }
                }
            }
        };
        mShutdownEventReceiver = new ShutdownEventReceiver();
        if (DEBUG) Slog.d(TAG, "Registered receiver for ACTION_PACKAGE_REPLACED and ADDED.");
        PowerProfile powerProfile = new PowerProfile(context);
        final int numClusters = powerProfile.getNumCpuClusters();
        mKernelCpuSpeedReaders = new KernelCpuSpeedReader[numClusters];
        int firstCpuOfCluster = 0;
        for (int i = 0; i < numClusters; i++) {
            final int numSpeedSteps = powerProfile.getNumSpeedStepsInCpuCluster(i);
            mKernelCpuSpeedReaders[i] = new KernelCpuSpeedReader(firstCpuOfCluster,
                    numSpeedSteps);
            firstCpuOfCluster += powerProfile.getNumCoresInCpuCluster(i);
        }
        // use default throttling in
        // frameworks/base/core/java/com/android/internal/os/KernelCpuProcReader
        mKernelUidCpuFreqTimeReader.setThrottleInterval(0);
        long[] freqs = mKernelUidCpuFreqTimeReader.readFreqs(powerProfile);
        mKernelUidCpuClusterTimeReader.setThrottleInterval(0);
        mKernelUidCpuActiveTimeReader.setThrottleInterval(0);

        // Enable push notifications of throttling from vendor thermal
        // management subsystem via thermalservice.
        IBinder b = ServiceManager.getService("thermalservice");

        if (b != null) {
            sThermalService = IThermalService.Stub.asInterface(b);
            try {
                sThermalService.registerThermalEventListener(
                        new ThermalEventListener());
                Slog.i(TAG, "register thermal listener successfully");
            } catch (RemoteException e) {
                // Should never happen.
                Slog.e(TAG, "register thermal listener error");
            }
        } else {
            Slog.e(TAG, "cannot find thermalservice, no throttling push notifications");
        }
    }

    @Override
    public void sendDataBroadcast(IBinder intentSenderBinder, long lastReportTimeNs) {
        enforceCallingPermission();
        IntentSender intentSender = new IntentSender(intentSenderBinder);
        Intent intent = new Intent();
        intent.putExtra(EXTRA_LAST_REPORT_TIME, lastReportTimeNs);
        try {
            intentSender.sendIntent(mContext, CODE_DATA_BROADCAST, intent, null, null);
        } catch (IntentSender.SendIntentException e) {
            Slog.w(TAG, "Unable to send using IntentSender");
        }
    }

    @Override
    public void sendSubscriberBroadcast(IBinder intentSenderBinder, long configUid, long configKey,
            long subscriptionId, long subscriptionRuleId, String[] cookies,
            StatsDimensionsValue dimensionsValue) {
        enforceCallingPermission();
        IntentSender intentSender = new IntentSender(intentSenderBinder);
        Intent intent =
                new Intent()
                        .putExtra(StatsManager.EXTRA_STATS_CONFIG_UID, configUid)
                        .putExtra(StatsManager.EXTRA_STATS_CONFIG_KEY, configKey)
                        .putExtra(StatsManager.EXTRA_STATS_SUBSCRIPTION_ID, subscriptionId)
                        .putExtra(StatsManager.EXTRA_STATS_SUBSCRIPTION_RULE_ID, subscriptionRuleId)
                        .putExtra(StatsManager.EXTRA_STATS_DIMENSIONS_VALUE, dimensionsValue);

        ArrayList<String> cookieList = new ArrayList<>(cookies.length);
        for (String cookie : cookies) {
            cookieList.add(cookie);
        }
        intent.putStringArrayListExtra(
                StatsManager.EXTRA_STATS_BROADCAST_SUBSCRIBER_COOKIES, cookieList);

        if (DEBUG) {
            Slog.d(TAG,
                    String.format("Statsd sendSubscriberBroadcast with params {%d %d %d %d %s %s}",
                            configUid, configKey, subscriptionId, subscriptionRuleId,
                            Arrays.toString(cookies),
                            dimensionsValue));
        }
        try {
            intentSender.sendIntent(mContext, CODE_SUBSCRIBER_BROADCAST, intent, null, null);
        } catch (IntentSender.SendIntentException e) {
            Slog.w(TAG,
                    "Unable to send using IntentSender from uid " + configUid
                            + "; presumably it had been cancelled.");
        }
    }

    private final static int[] toIntArray(List<Integer> list) {
        int[] ret = new int[list.size()];
        for (int i = 0; i < ret.length; i++) {
            ret[i] = list.get(i);
        }
        return ret;
    }

    private final static long[] toLongArray(List<Long> list) {
        long[] ret = new long[list.size()];
        for (int i = 0; i < ret.length; i++) {
            ret[i] = list.get(i);
        }
        return ret;
    }

    // Assumes that sStatsdLock is held.
    @GuardedBy("sStatsdLock")
    private final void informAllUidsLocked(Context context) throws RemoteException {
        UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
        PackageManager pm = context.getPackageManager();
        final List<UserInfo> users = um.getUsers(true);
        if (DEBUG) {
            Slog.d(TAG, "Iterating over " + users.size() + " profiles.");
        }

        List<Integer> uids = new ArrayList<>();
        List<Long> versions = new ArrayList<>();
        List<String> apps = new ArrayList<>();

        // Add in all the apps for every user/profile.
        for (UserInfo profile : users) {
            List<PackageInfo> pi =
                    pm.getInstalledPackagesAsUser(PackageManager.MATCH_KNOWN_PACKAGES, profile.id);
            for (int j = 0; j < pi.size(); j++) {
                if (pi.get(j).applicationInfo != null) {
                    uids.add(pi.get(j).applicationInfo.uid);
                    versions.add(pi.get(j).getLongVersionCode());
                    apps.add(pi.get(j).packageName);
                }
            }
        }
        sStatsd.informAllUidData(toIntArray(uids), toLongArray(versions), apps.toArray(new
                String[apps.size()]));
        if (DEBUG) {
            Slog.d(TAG, "Sent data for " + uids.size() + " apps");
        }
    }

    private final static class AppUpdateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            /**
             * App updates actually consist of REMOVE, ADD, and then REPLACE broadcasts. To avoid
             * waste, we ignore the REMOVE and ADD broadcasts that contain the replacing flag.
             * If we can't find the value for EXTRA_REPLACING, we default to false.
             */
            if (!intent.getAction().equals(Intent.ACTION_PACKAGE_REPLACED)
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                return; // Keep only replacing or normal add and remove.
            }
            if (DEBUG) Slog.d(TAG, "StatsCompanionService noticed an app was updated.");
            synchronized (sStatsdLock) {
                if (sStatsd == null) {
                    Slog.w(TAG, "Could not access statsd to inform it of an app update");
                    return;
                }
                try {
                    if (intent.getAction().equals(Intent.ACTION_PACKAGE_REMOVED)) {
                        Bundle b = intent.getExtras();
                        int uid = b.getInt(Intent.EXTRA_UID);
                        boolean replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
                        if (!replacing) {
                            // Don't bother sending an update if we're right about to get another
                            // intent for the new version that's added.
                            PackageManager pm = context.getPackageManager();
                            String app = intent.getData().getSchemeSpecificPart();
                            sStatsd.informOnePackageRemoved(app, uid);
                        }
                    } else {
                        PackageManager pm = context.getPackageManager();
                        Bundle b = intent.getExtras();
                        int uid = b.getInt(Intent.EXTRA_UID);
                        String app = intent.getData().getSchemeSpecificPart();
                        PackageInfo pi = pm.getPackageInfo(app, PackageManager.MATCH_ANY_USER);
                        sStatsd.informOnePackage(app, uid, pi.getLongVersionCode());
                    }
                } catch (Exception e) {
                    Slog.w(TAG, "Failed to inform statsd of an app update", e);
                }
            }
        }
    }

    public final static class AnomalyAlarmListener implements OnAlarmListener {
        @Override
        public void onAlarm() {
            Slog.i(TAG, "StatsCompanionService believes an anomaly has occurred at time "
                    + System.currentTimeMillis() + "ms.");
            synchronized (sStatsdLock) {
                if (sStatsd == null) {
                    Slog.w(TAG, "Could not access statsd to inform it of anomaly alarm firing");
                    return;
                }
                try {
                    // Two-way call to statsd to retain AlarmManager wakelock
                    sStatsd.informAnomalyAlarmFired();
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to inform statsd of anomaly alarm firing", e);
                }
            }
            // AlarmManager releases its own wakelock here.
        }
    }

    public final static class PullingAlarmListener implements OnAlarmListener {
        @Override
        public void onAlarm() {
            if (DEBUG) {
                Slog.d(TAG, "Time to poll something.");
            }
            synchronized (sStatsdLock) {
                if (sStatsd == null) {
                    Slog.w(TAG, "Could not access statsd to inform it of pulling alarm firing.");
                    return;
                }
                try {
                    // Two-way call to statsd to retain AlarmManager wakelock
                    sStatsd.informPollAlarmFired();
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to inform statsd of pulling alarm firing.", e);
                }
            }
        }
    }

    public final static class PeriodicAlarmListener implements OnAlarmListener {
        @Override
        public void onAlarm() {
            if (DEBUG) {
                Slog.d(TAG, "Time to trigger periodic alarm.");
            }
            synchronized (sStatsdLock) {
                if (sStatsd == null) {
                    Slog.w(TAG, "Could not access statsd to inform it of periodic alarm firing.");
                    return;
                }
                try {
                    // Two-way call to statsd to retain AlarmManager wakelock
                    sStatsd.informAlarmForSubscriberTriggeringFired();
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to inform statsd of periodic alarm firing.", e);
                }
            }
            // AlarmManager releases its own wakelock here.
        }
    }

    public final static class ShutdownEventReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            /**
             * Skip immediately if intent is not relevant to device shutdown.
             */
            if (!intent.getAction().equals(Intent.ACTION_REBOOT)
                    && !(intent.getAction().equals(Intent.ACTION_SHUTDOWN)
                    && (intent.getFlags() & Intent.FLAG_RECEIVER_FOREGROUND) != 0)) {
                return;
            }

            Slog.i(TAG, "StatsCompanionService noticed a shutdown.");
            synchronized (sStatsdLock) {
                if (sStatsd == null) {
                    Slog.w(TAG, "Could not access statsd to inform it of a shutdown event.");
                    return;
                }
                try {
                    sStatsd.informDeviceShutdown();
                } catch (Exception e) {
                    Slog.w(TAG, "Failed to inform statsd of a shutdown event.", e);
                }
            }
        }
    }

    @Override // Binder call
    public void setAnomalyAlarm(long timestampMs) {
        enforceCallingPermission();
        if (DEBUG) Slog.d(TAG, "Setting anomaly alarm for " + timestampMs);
        final long callingToken = Binder.clearCallingIdentity();
        try {
            // using ELAPSED_REALTIME, not ELAPSED_REALTIME_WAKEUP, so if device is asleep, will
            // only fire when it awakens.
            // AlarmManager will automatically cancel any previous mAnomalyAlarmListener alarm.
            mAlarmManager.setExact(AlarmManager.ELAPSED_REALTIME, timestampMs, TAG + ".anomaly",
                    mAnomalyAlarmListener, null);
        } finally {
            Binder.restoreCallingIdentity(callingToken);
        }
    }

    @Override // Binder call
    public void cancelAnomalyAlarm() {
        enforceCallingPermission();
        if (DEBUG) Slog.d(TAG, "Cancelling anomaly alarm");
        final long callingToken = Binder.clearCallingIdentity();
        try {
            mAlarmManager.cancel(mAnomalyAlarmListener);
        } finally {
            Binder.restoreCallingIdentity(callingToken);
        }
    }

    @Override // Binder call
    public void setAlarmForSubscriberTriggering(long timestampMs) {
        enforceCallingPermission();
        if (DEBUG) {
            Slog.d(TAG,
                    "Setting periodic alarm in about " + (timestampMs
                            - SystemClock.elapsedRealtime()));
        }
        final long callingToken = Binder.clearCallingIdentity();
        try {
            // using ELAPSED_REALTIME, not ELAPSED_REALTIME_WAKEUP, so if device is asleep, will
            // only fire when it awakens.
            mAlarmManager.setExact(AlarmManager.ELAPSED_REALTIME, timestampMs, TAG + ".periodic",
                    mPeriodicAlarmListener, null);
        } finally {
            Binder.restoreCallingIdentity(callingToken);
        }
    }

    @Override // Binder call
    public void cancelAlarmForSubscriberTriggering() {
        enforceCallingPermission();
        if (DEBUG) {
            Slog.d(TAG, "Cancelling periodic alarm");
        }
        final long callingToken = Binder.clearCallingIdentity();
        try {
            mAlarmManager.cancel(mPeriodicAlarmListener);
        } finally {
            Binder.restoreCallingIdentity(callingToken);
        }
    }

    @Override // Binder call
    public void setPullingAlarm(long nextPullTimeMs) {
        enforceCallingPermission();
        if (DEBUG) {
            Slog.d(TAG, "Setting pulling alarm in about "
                    + (nextPullTimeMs - SystemClock.elapsedRealtime()));
        }
        final long callingToken = Binder.clearCallingIdentity();
        try {
            // using ELAPSED_REALTIME, not ELAPSED_REALTIME_WAKEUP, so if device is asleep, will
            // only fire when it awakens.
            mAlarmManager.setExact(AlarmManager.ELAPSED_REALTIME, nextPullTimeMs, TAG + ".pull",
                    mPullingAlarmListener, null);
        } finally {
            Binder.restoreCallingIdentity(callingToken);
        }
    }

    @Override // Binder call
    public void cancelPullingAlarm() {
        enforceCallingPermission();
        if (DEBUG) {
            Slog.d(TAG, "Cancelling pulling alarm");
        }
        final long callingToken = Binder.clearCallingIdentity();
        try {
            mAlarmManager.cancel(mPullingAlarmListener);
        } finally {
            Binder.restoreCallingIdentity(callingToken);
        }
    }

    private void addNetworkStats(
            int tag, List<StatsLogEventWrapper> ret, NetworkStats stats, boolean withFGBG) {
        int size = stats.size();
        long elapsedNanos = SystemClock.elapsedRealtimeNanos();
        long wallClockNanos = SystemClock.currentTimeMicro() * 1000L;
        NetworkStats.Entry entry = new NetworkStats.Entry(); // For recycling
        for (int j = 0; j < size; j++) {
            stats.getValues(j, entry);
            StatsLogEventWrapper e = new StatsLogEventWrapper(tag, elapsedNanos, wallClockNanos);
            e.writeInt(entry.uid);
            if (withFGBG) {
                e.writeInt(entry.set);
            }
            e.writeLong(entry.rxBytes);
            e.writeLong(entry.rxPackets);
            e.writeLong(entry.txBytes);
            e.writeLong(entry.txPackets);
            ret.add(e);
        }
    }

    /**
     * Allows rollups per UID but keeping the set (foreground/background) slicing.
     * Adapted from groupedByUid in frameworks/base/core/java/android/net/NetworkStats.java
     */
    private NetworkStats rollupNetworkStatsByFGBG(NetworkStats stats) {
        final NetworkStats ret = new NetworkStats(stats.getElapsedRealtime(), 1);

        final NetworkStats.Entry entry = new NetworkStats.Entry();
        entry.iface = NetworkStats.IFACE_ALL;
        entry.tag = NetworkStats.TAG_NONE;
        entry.metered = NetworkStats.METERED_ALL;
        entry.roaming = NetworkStats.ROAMING_ALL;

        int size = stats.size();
        NetworkStats.Entry recycle = new NetworkStats.Entry(); // Used for retrieving values
        for (int i = 0; i < size; i++) {
            stats.getValues(i, recycle);

            // Skip specific tags, since already counted in TAG_NONE
            if (recycle.tag != NetworkStats.TAG_NONE) continue;

            entry.set = recycle.set; // Allows slicing by background/foreground
            entry.uid = recycle.uid;
            entry.rxBytes = recycle.rxBytes;
            entry.rxPackets = recycle.rxPackets;
            entry.txBytes = recycle.txBytes;
            entry.txPackets = recycle.txPackets;
            // Operations purposefully omitted since we don't use them for statsd.
            ret.combineValues(entry);
        }
        return ret;
    }

    /**
     * Helper method to extract the Parcelable controller info from a
     * SynchronousResultReceiver.
     */
    private static <T extends Parcelable> T awaitControllerInfo(
            @Nullable SynchronousResultReceiver receiver) {
        if (receiver == null) {
            return null;
        }

        try {
            final SynchronousResultReceiver.Result result =
                    receiver.awaitResult(EXTERNAL_STATS_SYNC_TIMEOUT_MILLIS);
            if (result.bundle != null) {
                // This is the final destination for the Bundle.
                result.bundle.setDefusable(true);

                final T data = result.bundle.getParcelable(
                        RESULT_RECEIVER_CONTROLLER_KEY);
                if (data != null) {
                    return data;
                }
            }
            Slog.e(TAG, "no controller energy info supplied for " + receiver.getName());
        } catch (TimeoutException e) {
            Slog.w(TAG, "timeout reading " + receiver.getName() + " stats");
        }
        return null;
    }

    private void pullKernelWakelock(
            int tagId, long elapsedNanos, long wallClockNanos,
            List<StatsLogEventWrapper> pulledData) {
        final KernelWakelockStats wakelockStats =
                mKernelWakelockReader.readKernelWakelockStats(mTmpWakelockStats);
        for (Map.Entry<String, KernelWakelockStats.Entry> ent : wakelockStats.entrySet()) {
            String name = ent.getKey();
            KernelWakelockStats.Entry kws = ent.getValue();
            StatsLogEventWrapper e = new StatsLogEventWrapper(tagId, elapsedNanos, wallClockNanos);
            e.writeString(name);
            e.writeInt(kws.mCount);
            e.writeInt(kws.mVersion);
            e.writeLong(kws.mTotalTime);
            pulledData.add(e);
        }
    }

    private void pullWifiBytesTransfer(
            int tagId, long elapsedNanos, long wallClockNanos,
            List<StatsLogEventWrapper> pulledData) {
        long token = Binder.clearCallingIdentity();
        try {
            // TODO: Consider caching the following call to get BatteryStatsInternal.
            BatteryStatsInternal bs = LocalServices.getService(BatteryStatsInternal.class);
            String[] ifaces = bs.getWifiIfaces();
            if (ifaces.length == 0) {
                return;
            }
            NetworkStatsFactory nsf = new NetworkStatsFactory();
            // Combine all the metrics per Uid into one record.
            NetworkStats stats =
                    nsf.readNetworkStatsDetail(NetworkStats.UID_ALL, ifaces, NetworkStats.TAG_NONE,
                            null)
                            .groupedByUid();
            addNetworkStats(tagId, pulledData, stats, false);
        } catch (java.io.IOException e) {
            Slog.e(TAG, "Pulling netstats for wifi bytes has error", e);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void pullWifiBytesTransferByFgBg(
            int tagId, long elapsedNanos, long wallClockNanos,
            List<StatsLogEventWrapper> pulledData) {
        long token = Binder.clearCallingIdentity();
        try {
            BatteryStatsInternal bs = LocalServices.getService(BatteryStatsInternal.class);
            String[] ifaces = bs.getWifiIfaces();
            if (ifaces.length == 0) {
                return;
            }
            NetworkStatsFactory nsf = new NetworkStatsFactory();
            NetworkStats stats = rollupNetworkStatsByFGBG(
                    nsf.readNetworkStatsDetail(NetworkStats.UID_ALL, ifaces, NetworkStats.TAG_NONE,
                            null));
            addNetworkStats(tagId, pulledData, stats, true);
        } catch (java.io.IOException e) {
            Slog.e(TAG, "Pulling netstats for wifi bytes w/ fg/bg has error", e);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void pullMobileBytesTransfer(
            int tagId, long elapsedNanos, long wallClockNanos,
            List<StatsLogEventWrapper> pulledData) {
        long token = Binder.clearCallingIdentity();
        try {
            BatteryStatsInternal bs = LocalServices.getService(BatteryStatsInternal.class);
            String[] ifaces = bs.getMobileIfaces();
            if (ifaces.length == 0) {
                return;
            }
            NetworkStatsFactory nsf = new NetworkStatsFactory();
            // Combine all the metrics per Uid into one record.
            NetworkStats stats =
                    nsf.readNetworkStatsDetail(NetworkStats.UID_ALL, ifaces, NetworkStats.TAG_NONE,
                            null)
                            .groupedByUid();
            addNetworkStats(tagId, pulledData, stats, false);
        } catch (java.io.IOException e) {
            Slog.e(TAG, "Pulling netstats for mobile bytes has error", e);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void pullBluetoothBytesTransfer(
            int tagId, long elapsedNanos, long wallClockNanos,
            List<StatsLogEventWrapper> pulledData) {
        BluetoothActivityEnergyInfo info = pullBluetoothData();
        if (info.getUidTraffic() != null) {
            for (UidTraffic traffic : info.getUidTraffic()) {
                StatsLogEventWrapper e = new StatsLogEventWrapper(tagId, elapsedNanos,
                        wallClockNanos);
                e.writeInt(traffic.getUid());
                e.writeLong(traffic.getRxBytes());
                e.writeLong(traffic.getTxBytes());
                pulledData.add(e);
            }
        }
    }

    private void pullMobileBytesTransferByFgBg(
            int tagId, long elapsedNanos, long wallClockNanos,
            List<StatsLogEventWrapper> pulledData) {
        long token = Binder.clearCallingIdentity();
        try {
            BatteryStatsInternal bs = LocalServices.getService(BatteryStatsInternal.class);
            String[] ifaces = bs.getMobileIfaces();
            if (ifaces.length == 0) {
                return;
            }
            NetworkStatsFactory nsf = new NetworkStatsFactory();
            NetworkStats stats = rollupNetworkStatsByFGBG(
                    nsf.readNetworkStatsDetail(NetworkStats.UID_ALL, ifaces, NetworkStats.TAG_NONE,
                            null));
            addNetworkStats(tagId, pulledData, stats, true);
        } catch (java.io.IOException e) {
            Slog.e(TAG, "Pulling netstats for mobile bytes w/ fg/bg has error", e);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void pullCpuTimePerFreq(
            int tagId, long elapsedNanos, long wallClockNanos,
            List<StatsLogEventWrapper> pulledData) {
        for (int cluster = 0; cluster < mKernelCpuSpeedReaders.length; cluster++) {
            long[] clusterTimeMs = mKernelCpuSpeedReaders[cluster].readAbsolute();
            if (clusterTimeMs != null) {
                for (int speed = clusterTimeMs.length - 1; speed >= 0; --speed) {
                    StatsLogEventWrapper e = new StatsLogEventWrapper(tagId, elapsedNanos,
                            wallClockNanos);
                    e.writeInt(cluster);
                    e.writeInt(speed);
                    e.writeLong(clusterTimeMs[speed]);
                    pulledData.add(e);
                }
            }
        }
    }

    private void pullKernelUidCpuTime(
            int tagId, long elapsedNanos, long wallClockNanos,
            List<StatsLogEventWrapper> pulledData) {
        mKernelUidCpuTimeReader.readAbsolute((uid, userTimeUs, systemTimeUs) -> {
            StatsLogEventWrapper e = new StatsLogEventWrapper(tagId, elapsedNanos, wallClockNanos);
            e.writeInt(uid);
            e.writeLong(userTimeUs);
            e.writeLong(systemTimeUs);
            pulledData.add(e);
        });
    }

    private void pullKernelUidCpuFreqTime(
            int tagId, long elapsedNanos, long wallClockNanos,
            List<StatsLogEventWrapper> pulledData) {
        mKernelUidCpuFreqTimeReader.readAbsolute((uid, cpuFreqTimeMs) -> {
            for (int freqIndex = 0; freqIndex < cpuFreqTimeMs.length; ++freqIndex) {
                if (cpuFreqTimeMs[freqIndex] != 0) {
                    StatsLogEventWrapper e = new StatsLogEventWrapper(tagId, elapsedNanos,
                            wallClockNanos);
                    e.writeInt(uid);
                    e.writeInt(freqIndex);
                    e.writeLong(cpuFreqTimeMs[freqIndex]);
                    pulledData.add(e);
                }
            }
        });
    }

    private void pullKernelUidCpuClusterTime(
            int tagId, long elapsedNanos, long wallClockNanos,
            List<StatsLogEventWrapper> pulledData) {
        mKernelUidCpuClusterTimeReader.readAbsolute((uid, cpuClusterTimesMs) -> {
            for (int i = 0; i < cpuClusterTimesMs.length; i++) {
                StatsLogEventWrapper e = new StatsLogEventWrapper(tagId, elapsedNanos,
                        wallClockNanos);
                e.writeInt(uid);
                e.writeInt(i);
                e.writeLong(cpuClusterTimesMs[i]);
                pulledData.add(e);
            }
        });
    }

    private void pullKernelUidCpuActiveTime(
            int tagId, long elapsedNanos, long wallClockNanos,
            List<StatsLogEventWrapper> pulledData) {
        mKernelUidCpuActiveTimeReader.readAbsolute((uid, cpuActiveTimesMs) -> {
            StatsLogEventWrapper e = new StatsLogEventWrapper(tagId, elapsedNanos, wallClockNanos);
            e.writeInt(uid);
            e.writeLong((long) cpuActiveTimesMs);
            pulledData.add(e);
        });
    }

    private void pullWifiActivityInfo(
            int tagId, long elapsedNanos, long wallClockNanos,
            List<StatsLogEventWrapper> pulledData) {
        long token = Binder.clearCallingIdentity();
        if (mWifiManager == null) {
            mWifiManager =
                    IWifiManager.Stub.asInterface(ServiceManager.getService(Context.WIFI_SERVICE));
        }
        if (mWifiManager != null) {
            try {
                SynchronousResultReceiver wifiReceiver = new SynchronousResultReceiver("wifi");
                mWifiManager.requestActivityInfo(wifiReceiver);
                final WifiActivityEnergyInfo wifiInfo = awaitControllerInfo(wifiReceiver);
                StatsLogEventWrapper e = new StatsLogEventWrapper(tagId, elapsedNanos,
                        wallClockNanos);
                e.writeLong(wifiInfo.getTimeStamp());
                e.writeInt(wifiInfo.getStackState());
                e.writeLong(wifiInfo.getControllerTxTimeMillis());
                e.writeLong(wifiInfo.getControllerRxTimeMillis());
                e.writeLong(wifiInfo.getControllerIdleTimeMillis());
                e.writeLong(wifiInfo.getControllerEnergyUsed());
                pulledData.add(e);
            } catch (RemoteException e) {
                Slog.e(TAG,
                        "Pulling wifiManager for wifi controller activity energy info has error",
                        e);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }

    private void pullModemActivityInfo(
            int tagId, long elapsedNanos, long wallClockNanos,
            List<StatsLogEventWrapper> pulledData) {
        long token = Binder.clearCallingIdentity();
        if (mTelephony == null) {
            mTelephony = TelephonyManager.from(mContext);
        }
        if (mTelephony != null) {
            SynchronousResultReceiver modemReceiver = new SynchronousResultReceiver("telephony");
            mTelephony.requestModemActivityInfo(modemReceiver);
            final ModemActivityInfo modemInfo = awaitControllerInfo(modemReceiver);
            StatsLogEventWrapper e = new StatsLogEventWrapper(tagId, elapsedNanos, wallClockNanos);
            e.writeLong(modemInfo.getTimestamp());
            e.writeLong(modemInfo.getSleepTimeMillis());
            e.writeLong(modemInfo.getIdleTimeMillis());
            e.writeLong(modemInfo.getTxTimeMillis()[0]);
            e.writeLong(modemInfo.getTxTimeMillis()[1]);
            e.writeLong(modemInfo.getTxTimeMillis()[2]);
            e.writeLong(modemInfo.getTxTimeMillis()[3]);
            e.writeLong(modemInfo.getTxTimeMillis()[4]);
            e.writeLong(modemInfo.getRxTimeMillis());
            e.writeLong(modemInfo.getEnergyUsed());
            pulledData.add(e);
        }
    }

    private void pullBluetoothActivityInfo(
            int tagId, long elapsedNanos, long wallClockNanos,
            List<StatsLogEventWrapper> pulledData) {
        BluetoothActivityEnergyInfo info = pullBluetoothData();
        StatsLogEventWrapper e = new StatsLogEventWrapper(tagId, elapsedNanos, wallClockNanos);
        e.writeLong(info.getTimeStamp());
        e.writeInt(info.getBluetoothStackState());
        e.writeLong(info.getControllerTxTimeMillis());
        e.writeLong(info.getControllerRxTimeMillis());
        e.writeLong(info.getControllerIdleTimeMillis());
        e.writeLong(info.getControllerEnergyUsed());
        pulledData.add(e);
    }

    private synchronized BluetoothActivityEnergyInfo pullBluetoothData() {
        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            SynchronousResultReceiver bluetoothReceiver = new SynchronousResultReceiver(
                    "bluetooth");
            adapter.requestControllerActivityEnergyInfo(bluetoothReceiver);
            return awaitControllerInfo(bluetoothReceiver);
        } else {
            Slog.e(TAG, "Failed to get bluetooth adapter!");
            return null;
        }
    }

    private void pullSystemElapsedRealtime(
            int tagId, long elapsedNanos, long wallClockNanos,
            List<StatsLogEventWrapper> pulledData) {
        StatsLogEventWrapper e = new StatsLogEventWrapper(tagId, elapsedNanos, wallClockNanos);
        e.writeLong(SystemClock.elapsedRealtime());
        pulledData.add(e);
    }

    private void pullSystemUpTime(int tagId, long elapsedNanos, long wallClockNanos,
            List<StatsLogEventWrapper> pulledData) {
        StatsLogEventWrapper e = new StatsLogEventWrapper(tagId, elapsedNanos, wallClockNanos);
        e.writeLong(SystemClock.uptimeMillis());
        pulledData.add(e);
    }

    private void pullProcessMemoryState(
            int tagId, long elapsedNanos, long wallClockNanos,
            List<StatsLogEventWrapper> pulledData) {
        List<ProcessMemoryState> processMemoryStates =
                LocalServices.getService(
                        ActivityManagerInternal.class).getMemoryStateForProcesses();
        for (ProcessMemoryState processMemoryState : processMemoryStates) {
            StatsLogEventWrapper e = new StatsLogEventWrapper(tagId, elapsedNanos, wallClockNanos);
            e.writeInt(processMemoryState.uid);
            e.writeString(processMemoryState.processName);
            e.writeInt(processMemoryState.oomScore);
            e.writeLong(processMemoryState.pgfault);
            e.writeLong(processMemoryState.pgmajfault);
            e.writeLong(processMemoryState.rssInBytes);
            e.writeLong(processMemoryState.cacheInBytes);
            e.writeLong(processMemoryState.swapInBytes);
            e.writeLong(processMemoryState.rssHighWatermarkInBytes);
            pulledData.add(e);
        }
    }

    private void pullBinderCallsStats(
            int tagId, long elapsedNanos, long wallClockNanos,
            List<StatsLogEventWrapper> pulledData) {
        BinderCallsStatsService.Internal binderStats =
                LocalServices.getService(BinderCallsStatsService.Internal.class);
        if (binderStats == null) {
            return;
        }

        List<ExportedCallStat> callStats = binderStats.getExportedCallStats();
        binderStats.reset();
        for (ExportedCallStat callStat : callStats) {
            StatsLogEventWrapper e = new StatsLogEventWrapper(tagId, elapsedNanos, wallClockNanos);
            e.writeInt(callStat.uid);
            e.writeString(callStat.className);
            e.writeString(callStat.methodName);
            e.writeLong(callStat.callCount);
            e.writeLong(callStat.exceptionCount);
            e.writeLong(callStat.latencyMicros);
            e.writeLong(callStat.maxLatencyMicros);
            e.writeLong(callStat.cpuTimeMicros);
            e.writeLong(callStat.maxCpuTimeMicros);
            e.writeLong(callStat.maxReplySizeBytes);
            e.writeLong(callStat.maxRequestSizeBytes);
            e.writeLong(callStat.recordedCallCount);
            e.writeInt(callStat.screenInteractive ? 1 : 0);
            pulledData.add(e);
        }
    }

    private void pullBinderCallsStatsExceptions(
            int tagId, long elapsedNanos, long wallClockNanos,
            List<StatsLogEventWrapper> pulledData) {
        BinderCallsStatsService.Internal binderStats =
                LocalServices.getService(BinderCallsStatsService.Internal.class);
        if (binderStats == null) {
            return;
        }

        ArrayMap<String, Integer> exceptionStats = binderStats.getExportedExceptionStats();
        // TODO: decouple binder calls exceptions with the rest of the binder calls data so that we
        // can reset the exception stats.
        for (Entry<String, Integer> entry : exceptionStats.entrySet()) {
            StatsLogEventWrapper e = new StatsLogEventWrapper(tagId, elapsedNanos, wallClockNanos);
            e.writeString(entry.getKey());
            e.writeInt(entry.getValue());
            pulledData.add(e);
        }
    }

    private void pullLooperStats(int tagId, long elapsedNanos, long wallClockNanos,
            List<StatsLogEventWrapper> pulledData) {
        LooperStats looperStats = LocalServices.getService(LooperStats.class);
        if (looperStats == null) {
            return;
        }

        List<LooperStats.ExportedEntry> entries = looperStats.getEntries();
        looperStats.reset();
        for (LooperStats.ExportedEntry entry : entries) {
            StatsLogEventWrapper e = new StatsLogEventWrapper(tagId, elapsedNanos, wallClockNanos);
            e.writeInt(entry.workSourceUid);
            e.writeString(entry.handlerClassName);
            e.writeString(entry.threadName);
            e.writeString(entry.messageName);
            e.writeLong(entry.messageCount);
            e.writeLong(entry.exceptionCount);
            e.writeLong(entry.recordedMessageCount);
            e.writeLong(entry.totalLatencyMicros);
            e.writeLong(entry.cpuUsageMicros);
            e.writeBoolean(entry.isInteractive);
            pulledData.add(e);
        }
    }

    private void pullDiskStats(int tagId, long elapsedNanos, long wallClockNanos,
            List<StatsLogEventWrapper> pulledData) {
        // Run a quick-and-dirty performance test: write 512 bytes
        byte[] junk = new byte[512];
        for (int i = 0; i < junk.length; i++) junk[i] = (byte) i;  // Write nonzero bytes

        File tmp = new File(Environment.getDataDirectory(), "system/statsdperftest.tmp");
        FileOutputStream fos = null;
        IOException error = null;

        long before = SystemClock.elapsedRealtime();
        try {
            fos = new FileOutputStream(tmp);
            fos.write(junk);
        } catch (IOException e) {
            error = e;
        } finally {
            try {
                if (fos != null) fos.close();
            } catch (IOException e) {
                // Do nothing.
            }
        }

        long latency = SystemClock.elapsedRealtime() - before;
        if (tmp.exists()) tmp.delete();

        if (error != null) {
            Slog.e(TAG, "Error performing diskstats latency test");
            latency = -1;
        }
        // File based encryption.
        boolean fileBased = StorageManager.isFileEncryptedNativeOnly();

        //Recent disk write speed. Binder call to storaged.
        int writeSpeed = -1;
        try {
            IBinder binder = ServiceManager.getService("storaged");
            if (binder == null) {
                Slog.e(TAG, "storaged not found");
            }
            IStoraged storaged = IStoraged.Stub.asInterface(binder);
            writeSpeed = storaged.getRecentPerf();
        } catch (RemoteException e) {
            Slog.e(TAG, "storaged not found");
        }

        // Add info pulledData.
        StatsLogEventWrapper e = new StatsLogEventWrapper(tagId, elapsedNanos, wallClockNanos);
        e.writeLong(latency);
        e.writeBoolean(fileBased);
        e.writeInt(writeSpeed);
        pulledData.add(e);
    }

    private void pullDirectoryUsage(int tagId, long elapsedNanos, long wallClockNanos,
            List<StatsLogEventWrapper> pulledData) {
        StatFs statFsData = new StatFs(Environment.getDataDirectory().getAbsolutePath());
        StatFs statFsSystem = new StatFs(Environment.getRootDirectory().getAbsolutePath());
        StatFs statFsCache = new StatFs(Environment.getDownloadCacheDirectory().getAbsolutePath());

        StatsLogEventWrapper e = new StatsLogEventWrapper(tagId, elapsedNanos, wallClockNanos);
        e.writeInt(StatsLog.DIRECTORY_USAGE__DIRECTORY__DATA);
        e.writeLong(statFsData.getAvailableBytes());
        e.writeLong(statFsData.getTotalBytes());
        pulledData.add(e);

        e = new StatsLogEventWrapper(tagId, elapsedNanos, wallClockNanos);
        e.writeInt(StatsLog.DIRECTORY_USAGE__DIRECTORY__CACHE);
        e.writeLong(statFsCache.getAvailableBytes());
        e.writeLong(statFsCache.getTotalBytes());
        pulledData.add(e);

        e = new StatsLogEventWrapper(tagId, elapsedNanos, wallClockNanos);
        e.writeInt(StatsLog.DIRECTORY_USAGE__DIRECTORY__SYSTEM);
        e.writeLong(statFsSystem.getAvailableBytes());
        e.writeLong(statFsSystem.getTotalBytes());
        pulledData.add(e);
    }

    private void pullAppSize(int tagId, long elapsedNanos, long wallClockNanos,
            List<StatsLogEventWrapper> pulledData) {
        try {
            String jsonStr = IoUtils.readFileAsString(DiskStatsLoggingService.DUMPSYS_CACHE_PATH);
            JSONObject json = new JSONObject(jsonStr);
            long cache_time = json.optLong(DiskStatsFileLogger.LAST_QUERY_TIMESTAMP_KEY, -1L);
            JSONArray pkg_names = json.getJSONArray(DiskStatsFileLogger.PACKAGE_NAMES_KEY);
            JSONArray app_sizes = json.getJSONArray(DiskStatsFileLogger.APP_SIZES_KEY);
            JSONArray app_data_sizes = json.getJSONArray(DiskStatsFileLogger.APP_DATA_KEY);
            JSONArray app_cache_sizes = json.getJSONArray(DiskStatsFileLogger.APP_CACHES_KEY);
            // Sanity check: Ensure all 4 lists have the same length.
            int length = pkg_names.length();
            if (app_sizes.length() != length || app_data_sizes.length() != length
                    || app_cache_sizes.length() != length) {
                Slog.e(TAG, "formatting error in diskstats cache file!");
                return;
            }
            for (int i = 0; i < length; i++) {
                StatsLogEventWrapper e =
                        new StatsLogEventWrapper(tagId, elapsedNanos, wallClockNanos);
                e.writeString(pkg_names.getString(i));
                e.writeLong(app_sizes.optLong(i, -1L));
                e.writeLong(app_data_sizes.optLong(i, -1L));
                e.writeLong(app_cache_sizes.optLong(i, -1L));
                e.writeLong(cache_time);
                pulledData.add(e);
            }
        } catch (IOException | JSONException e) {
            Slog.e(TAG, "exception reading diskstats cache file", e);
        }
    }

    private void pullCategorySize(int tagId, long elapsedNanos, long wallClockNanos,
            List<StatsLogEventWrapper> pulledData) {
        try {
            String jsonStr = IoUtils.readFileAsString(DiskStatsLoggingService.DUMPSYS_CACHE_PATH);
            JSONObject json = new JSONObject(jsonStr);
            long cacheTime = json.optLong(DiskStatsFileLogger.LAST_QUERY_TIMESTAMP_KEY, -1L);

            StatsLogEventWrapper e = new StatsLogEventWrapper(tagId, elapsedNanos, wallClockNanos);
            e.writeInt(StatsLog.CATEGORY_SIZE__CATEGORY__APP_SIZE);
            e.writeLong(json.optLong(DiskStatsFileLogger.APP_SIZE_AGG_KEY, -1L));
            e.writeLong(cacheTime);
            pulledData.add(e);

            e = new StatsLogEventWrapper(tagId, elapsedNanos, wallClockNanos);
            e.writeInt(StatsLog.CATEGORY_SIZE__CATEGORY__APP_DATA_SIZE);
            e.writeLong(json.optLong(DiskStatsFileLogger.APP_DATA_SIZE_AGG_KEY, -1L));
            e.writeLong(cacheTime);
            pulledData.add(e);

            e = new StatsLogEventWrapper(tagId, elapsedNanos, wallClockNanos);
            e.writeInt(StatsLog.CATEGORY_SIZE__CATEGORY__APP_CACHE_SIZE);
            e.writeLong(json.optLong(DiskStatsFileLogger.APP_CACHE_AGG_KEY, -1L));
            e.writeLong(cacheTime);
            pulledData.add(e);

            e = new StatsLogEventWrapper(tagId, elapsedNanos, wallClockNanos);
            e.writeInt(StatsLog.CATEGORY_SIZE__CATEGORY__PHOTOS);
            e.writeLong(json.optLong(DiskStatsFileLogger.PHOTOS_KEY, -1L));
            e.writeLong(cacheTime);
            pulledData.add(e);

            e = new StatsLogEventWrapper(tagId, elapsedNanos, wallClockNanos);
            e.writeInt(StatsLog.CATEGORY_SIZE__CATEGORY__VIDEOS);
            e.writeLong(json.optLong(DiskStatsFileLogger.VIDEOS_KEY, -1L));
            e.writeLong(cacheTime);
            pulledData.add(e);

            e = new StatsLogEventWrapper(tagId, elapsedNanos, wallClockNanos);
            e.writeInt(StatsLog.CATEGORY_SIZE__CATEGORY__AUDIO);
            e.writeLong(json.optLong(DiskStatsFileLogger.AUDIO_KEY, -1L));
            e.writeLong(cacheTime);
            pulledData.add(e);

            e = new StatsLogEventWrapper(tagId, elapsedNanos, wallClockNanos);
            e.writeInt(StatsLog.CATEGORY_SIZE__CATEGORY__DOWNLOADS);
            e.writeLong(json.optLong(DiskStatsFileLogger.DOWNLOADS_KEY, -1L));
            e.writeLong(cacheTime);
            pulledData.add(e);

            e = new StatsLogEventWrapper(tagId, elapsedNanos, wallClockNanos);
            e.writeInt(StatsLog.CATEGORY_SIZE__CATEGORY__SYSTEM);
            e.writeLong(json.optLong(DiskStatsFileLogger.SYSTEM_KEY, -1L));
            e.writeLong(cacheTime);
            pulledData.add(e);

            e = new StatsLogEventWrapper(tagId, elapsedNanos, wallClockNanos);
            e.writeInt(StatsLog.CATEGORY_SIZE__CATEGORY__OTHER);
            e.writeLong(json.optLong(DiskStatsFileLogger.MISC_KEY, -1L));
            e.writeLong(cacheTime);
            pulledData.add(e);
        } catch (IOException | JSONException e) {
            Slog.e(TAG, "exception reading diskstats cache file", e);
        }
    }

    private void pullNumFingerprints(int tagId, long elapsedNanos, long wallClockNanos,
            List<StatsLogEventWrapper> pulledData) {
        FingerprintManager fingerprintManager = mContext.getSystemService(FingerprintManager.class);
        if (fingerprintManager == null) {
            return;
        }
        UserManager userManager = mContext.getSystemService(UserManager.class);
        if (userManager == null) {
            return;
        }
        final long token = Binder.clearCallingIdentity();
        for (UserInfo user : userManager.getUsers()) {
            final int userId = user.getUserHandle().getIdentifier();
            final int numFingerprints = fingerprintManager.getEnrolledFingerprints(userId).size();
            StatsLogEventWrapper e = new StatsLogEventWrapper(tagId, elapsedNanos, wallClockNanos);
            e.writeInt(userId);
            e.writeInt(numFingerprints);
            pulledData.add(e);
        }
        Binder.restoreCallingIdentity(token);
    }

    long mLastProcStatsHighWaterMark = readProcStatsHighWaterMark();

    private long readProcStatsHighWaterMark() {
        try {
            File[] files = mBaseDir.listFiles();
            if (files == null || files.length == 0) {
                return 0;
            }
            if (files.length > 1) {
                Log.e(TAG, "Only 1 file expected for high water mark. Found " + files.length);
            }
            return Long.valueOf(files[0].getName());
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to get procstats high watermark file.", e);
        } catch (NumberFormatException e) {
            Log.e(TAG, "Failed to parse file name.", e);
        }
        return 0;
    }

    private IProcessStats mProcessStats =
            IProcessStats.Stub.asInterface(ServiceManager.getService(ProcessStats.SERVICE_NAME));

    private void pullProcessStats(
            int tagId, long elapsedNanos, long wallClockNanos,
            List<StatsLogEventWrapper> pulledData) {
        try {
            List<ParcelFileDescriptor> statsFiles = new ArrayList<>();
            long highWaterMark = mProcessStats.getCommittedStats(
                    mLastProcStatsHighWaterMark, ProcessStats.REPORT_ALL, true, statsFiles);
            if (statsFiles.size() != 1) {
                return;
            }
            InputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(statsFiles.get(0));
            int[] len = new int[1];
            byte[] stats = readFully(stream, len);
            StatsLogEventWrapper e = new StatsLogEventWrapper(tagId, elapsedNanos, wallClockNanos);
            e.writeStorage(Arrays.copyOf(stats, len[0]));
            pulledData.add(e);
            new File(mBaseDir.getAbsolutePath() + "/" + mLastProcStatsHighWaterMark).delete();
            mLastProcStatsHighWaterMark = highWaterMark;
            new File(
                    mBaseDir.getAbsolutePath() + "/" + mLastProcStatsHighWaterMark).createNewFile();
        } catch (IOException e) {
            Log.e(TAG, "Getting procstats failed: ", e);
        } catch (RemoteException e) {
            Log.e(TAG, "Getting procstats failed: ", e);
        } catch (SecurityException e) {
            Log.e(TAG, "Getting procstats failed: ", e);
        }
    }

    static byte[] readFully(InputStream stream, int[] outLen) throws IOException {
        int pos = 0;
        final int initialAvail = stream.available();
        byte[] data = new byte[initialAvail > 0 ? (initialAvail + 1) : 16384];
        while (true) {
            int amt = stream.read(data, pos, data.length - pos);
            if (DEBUG) {
                Slog.i(TAG, "Read " + amt + " bytes at " + pos + " of avail " + data.length);
            }
            if (amt < 0) {
                if (DEBUG) {
                    Slog.i(TAG, "**** FINISHED READING: pos=" + pos + " len=" + data.length);
                }
                outLen[0] = pos;
                return data;
            }
            pos += amt;
            if (pos >= data.length) {
                byte[] newData = new byte[pos + 16384];
                if (DEBUG) {
                    Slog.i(TAG, "Copying " + pos + " bytes to new array len " + newData.length);
                }
                System.arraycopy(data, 0, newData, 0, pos);
                data = newData;
            }
        }
    }

    private void pullPowerProfile(
            int tagId, long elapsedNanos, long wallClockNanos,
            List<StatsLogEventWrapper> pulledData) {
        PowerProfile powerProfile = new PowerProfile(mContext);
        checkNotNull(powerProfile);

        StatsLogEventWrapper e = new StatsLogEventWrapper(tagId, elapsedNanos,
                wallClockNanos);
        ProtoOutputStream proto = new ProtoOutputStream();
        powerProfile.writeToProto(proto);
        proto.flush();
        e.writeStorage(proto.getBytes());
        pulledData.add(e);
    }

    private void pullDiskIo(int tagId, long elapsedNanos, final long wallClockNanos,
            List<StatsLogEventWrapper> pulledData) {
        mStoragedUidIoStatsReader.readAbsolute((uid, fgCharsRead, fgCharsWrite, fgBytesRead,
                fgBytesWrite, bgCharsRead, bgCharsWrite, bgBytesRead, bgBytesWrite,
                fgFsync, bgFsync) -> {
            StatsLogEventWrapper e = new StatsLogEventWrapper(tagId, elapsedNanos,
                    wallClockNanos);
            e.writeInt(uid);
            e.writeLong(fgCharsRead);
            e.writeLong(fgCharsWrite);
            e.writeLong(fgBytesRead);
            e.writeLong(fgBytesWrite);
            e.writeLong(bgCharsRead);
            e.writeLong(bgCharsWrite);
            e.writeLong(bgBytesRead);
            e.writeLong(bgBytesWrite);
            e.writeLong(fgFsync);
            e.writeLong(bgFsync);
            pulledData.add(e);
        });
    }

    /**
     * Pulls various data.
     */
    @Override // Binder call
    public StatsLogEventWrapper[] pullData(int tagId) {
        enforceCallingPermission();
        if (DEBUG) {
            Slog.d(TAG, "Pulling " + tagId);
        }
        List<StatsLogEventWrapper> ret = new ArrayList<>();
        long elapsedNanos = SystemClock.elapsedRealtimeNanos();
        long wallClockNanos = SystemClock.currentTimeMicro() * 1000L;
        switch (tagId) {
            case StatsLog.WIFI_BYTES_TRANSFER: {
                pullWifiBytesTransfer(tagId, elapsedNanos, wallClockNanos, ret);
                break;
            }
            case StatsLog.MOBILE_BYTES_TRANSFER: {
                pullMobileBytesTransfer(tagId, elapsedNanos, wallClockNanos, ret);
                break;
            }
            case StatsLog.WIFI_BYTES_TRANSFER_BY_FG_BG: {
                pullWifiBytesTransferByFgBg(tagId, elapsedNanos, wallClockNanos, ret);
                break;
            }
            case StatsLog.MOBILE_BYTES_TRANSFER_BY_FG_BG: {
                pullMobileBytesTransferByFgBg(tagId, elapsedNanos, wallClockNanos, ret);
                break;
            }
            case StatsLog.BLUETOOTH_BYTES_TRANSFER: {
                pullBluetoothBytesTransfer(tagId, elapsedNanos, wallClockNanos, ret);
                break;
            }
            case StatsLog.KERNEL_WAKELOCK: {
                pullKernelWakelock(tagId, elapsedNanos, wallClockNanos, ret);
                break;
            }
            case StatsLog.CPU_TIME_PER_FREQ: {
                pullCpuTimePerFreq(tagId, elapsedNanos, wallClockNanos, ret);
                break;
            }
            case StatsLog.CPU_TIME_PER_UID: {
                pullKernelUidCpuTime(tagId, elapsedNanos, wallClockNanos, ret);
                break;
            }
            case StatsLog.CPU_TIME_PER_UID_FREQ: {
                pullKernelUidCpuFreqTime(tagId, elapsedNanos, wallClockNanos, ret);
                break;
            }
            case StatsLog.CPU_CLUSTER_TIME: {
                pullKernelUidCpuClusterTime(tagId, elapsedNanos, wallClockNanos, ret);
                break;
            }
            case StatsLog.CPU_ACTIVE_TIME: {
                pullKernelUidCpuActiveTime(tagId, elapsedNanos, wallClockNanos, ret);
                break;
            }
            case StatsLog.WIFI_ACTIVITY_INFO: {
                pullWifiActivityInfo(tagId, elapsedNanos, wallClockNanos, ret);
                break;
            }
            case StatsLog.MODEM_ACTIVITY_INFO: {
                pullModemActivityInfo(tagId, elapsedNanos, wallClockNanos, ret);
                break;
            }
            case StatsLog.BLUETOOTH_ACTIVITY_INFO: {
                pullBluetoothActivityInfo(tagId, elapsedNanos, wallClockNanos, ret);
                break;
            }
            case StatsLog.SYSTEM_UPTIME: {
                pullSystemUpTime(tagId, elapsedNanos, wallClockNanos, ret);
                break;
            }
            case StatsLog.SYSTEM_ELAPSED_REALTIME: {
                pullSystemElapsedRealtime(tagId, elapsedNanos, wallClockNanos, ret);
                break;
            }
            case StatsLog.PROCESS_MEMORY_STATE: {
                pullProcessMemoryState(tagId, elapsedNanos, wallClockNanos, ret);
                break;
            }
            case StatsLog.BINDER_CALLS: {
                pullBinderCallsStats(tagId, elapsedNanos, wallClockNanos, ret);
                break;
            }
            case StatsLog.BINDER_CALLS_EXCEPTIONS: {
                pullBinderCallsStatsExceptions(tagId, elapsedNanos, wallClockNanos, ret);
                break;
            }
            case StatsLog.LOOPER_STATS: {
                pullLooperStats(tagId, elapsedNanos, wallClockNanos, ret);
                break;
            }
            case StatsLog.DISK_STATS: {
                pullDiskStats(tagId, elapsedNanos, wallClockNanos, ret);
                break;
            }
            case StatsLog.DIRECTORY_USAGE: {
                pullDirectoryUsage(tagId, elapsedNanos, wallClockNanos, ret);
                break;
            }
            case StatsLog.APP_SIZE: {
                pullAppSize(tagId, elapsedNanos, wallClockNanos, ret);
                break;
            }
            case StatsLog.CATEGORY_SIZE: {
                pullCategorySize(tagId, elapsedNanos, wallClockNanos, ret);
                break;
            }
            case StatsLog.NUM_FINGERPRINTS: {
                pullNumFingerprints(tagId, elapsedNanos, wallClockNanos, ret);
                break;
            }
            case StatsLog.PROC_STATS: {
                pullProcessStats(tagId, elapsedNanos, wallClockNanos, ret);
                break;
            }
            case StatsLog.DISK_IO: {
                pullDiskIo(tagId, elapsedNanos, wallClockNanos, ret);
                break;
            }
            case StatsLog.POWER_PROFILE: {
                pullPowerProfile(tagId, elapsedNanos, wallClockNanos, ret);
                break;
            }
            default:
                Slog.w(TAG, "No such tagId data as " + tagId);
                return null;
        }
        return ret.toArray(new StatsLogEventWrapper[ret.size()]);
    }

    @Override // Binder call
    public void statsdReady() {
        enforceCallingPermission();
        if (DEBUG) {
            Slog.d(TAG, "learned that statsdReady");
        }
        sayHiToStatsd(); // tell statsd that we're ready too and link to it
        mContext.sendBroadcastAsUser(new Intent(StatsManager.ACTION_STATSD_STARTED)
                        .addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND),
                UserHandle.SYSTEM, android.Manifest.permission.DUMP);
    }

    @Override
    public void triggerUidSnapshot() {
        enforceCallingPermission();
        synchronized (sStatsdLock) {
            final long token = Binder.clearCallingIdentity();
            try {
                informAllUidsLocked(mContext);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to trigger uid snapshot.", e);
            } finally {
                restoreCallingIdentity(token);
            }
        }
    }

    private void enforceCallingPermission() {
        if (Binder.getCallingPid() == Process.myPid()) {
            return;
        }
        mContext.enforceCallingPermission(android.Manifest.permission.STATSCOMPANION, null);
    }

    // Lifecycle and related code

    /**
     * Fetches the statsd IBinder service
     */
    private static IStatsManager fetchStatsdService() {
        return IStatsManager.Stub.asInterface(ServiceManager.getService("stats"));
    }

    public static final class Lifecycle extends SystemService {
        private StatsCompanionService mStatsCompanionService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            mStatsCompanionService = new StatsCompanionService(getContext());
            try {
                publishBinderService(Context.STATS_COMPANION_SERVICE,
                        mStatsCompanionService);
                if (DEBUG) Slog.d(TAG, "Published " + Context.STATS_COMPANION_SERVICE);
            } catch (Exception e) {
                Slog.e(TAG, "Failed to publishBinderService", e);
            }
        }

        @Override
        public void onBootPhase(int phase) {
            super.onBootPhase(phase);
            if (phase == PHASE_THIRD_PARTY_APPS_CAN_START) {
                mStatsCompanionService.systemReady();
            }
        }
    }

    /**
     * Now that the android system is ready, StatsCompanion is ready too, so inform statsd.
     */
    private void systemReady() {
        if (DEBUG) Slog.d(TAG, "Learned that systemReady");
        sayHiToStatsd();
    }

    /**
     * Tells statsd that statscompanion is ready. If the binder call returns, link to
     * statsd.
     */
    private void sayHiToStatsd() {
        synchronized (sStatsdLock) {
            if (sStatsd != null) {
                Slog.e(TAG, "Trying to fetch statsd, but it was already fetched",
                        new IllegalStateException(
                                "sStatsd is not null when being fetched"));
                return;
            }
            sStatsd = fetchStatsdService();
            if (sStatsd == null) {
                Slog.i(TAG,
                        "Could not yet find statsd to tell it that StatsCompanion is "
                                + "alive.");
                return;
            }
            if (DEBUG) Slog.d(TAG, "Saying hi to statsd");
            try {
                sStatsd.statsCompanionReady();
                // If the statsCompanionReady two-way binder call returns, link to statsd.
                try {
                    sStatsd.asBinder().linkToDeath(new StatsdDeathRecipient(), 0);
                } catch (RemoteException e) {
                    Slog.e(TAG, "linkToDeath(StatsdDeathRecipient) failed", e);
                    forgetEverythingLocked();
                }
                // Setup broadcast receiver for updates.
                IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_REPLACED);
                filter.addAction(Intent.ACTION_PACKAGE_ADDED);
                filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
                filter.addDataScheme("package");
                mContext.registerReceiverAsUser(mAppUpdateReceiver, UserHandle.ALL, filter,
                        null,
                        null);

                // Setup receiver for user initialize (which happens once for a new user)
                // and
                // if a user is removed.
                filter = new IntentFilter(Intent.ACTION_USER_INITIALIZE);
                filter.addAction(Intent.ACTION_USER_REMOVED);
                mContext.registerReceiverAsUser(mUserUpdateReceiver, UserHandle.ALL,
                        filter, null, null);

                // Setup receiver for device reboots or shutdowns.
                filter = new IntentFilter(Intent.ACTION_REBOOT);
                filter.addAction(Intent.ACTION_SHUTDOWN);
                mContext.registerReceiverAsUser(
                        mShutdownEventReceiver, UserHandle.ALL, filter, null, null);
                final long token = Binder.clearCallingIdentity();
                try {
                    // Pull the latest state of UID->app name, version mapping when
                    // statsd starts.
                    informAllUidsLocked(mContext);
                } finally {
                    restoreCallingIdentity(token);
                }
                Slog.i(TAG, "Told statsd that StatsCompanionService is alive.");
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to inform statsd that statscompanion is ready", e);
                forgetEverythingLocked();
            }
        }
    }

    private class StatsdDeathRecipient implements IBinder.DeathRecipient {
        @Override
        public void binderDied() {
            Slog.i(TAG, "Statsd is dead - erase all my knowledge.");
            synchronized (sStatsdLock) {
                long now = SystemClock.elapsedRealtime();
                for (Long timeMillis : mDeathTimeMillis) {
                    long ageMillis = now - timeMillis;
                    if (ageMillis > MILLIS_IN_A_DAY) {
                        mDeathTimeMillis.remove(timeMillis);
                    }
                }
                for (Long timeMillis : mDeletedFiles.keySet()) {
                    long ageMillis = now - timeMillis;
                    if (ageMillis > MILLIS_IN_A_DAY * 7) {
                        mDeletedFiles.remove(timeMillis);
                    }
                }
                mDeathTimeMillis.add(now);
                if (mDeathTimeMillis.size() >= DEATH_THRESHOLD) {
                    mDeathTimeMillis.clear();
                    File[] configs = FileUtils.listFilesOrEmpty(new File(CONFIG_DIR));
                    if (configs.length > 0) {
                        String fileName = configs[0].getName();
                        if (configs[0].delete()) {
                            mDeletedFiles.put(now, fileName);
                        }
                    }
                }
                forgetEverythingLocked();
            }
        }
    }

    @GuardedBy("StatsCompanionService.sStatsdLock")
    private void forgetEverythingLocked() {
        sStatsd = null;
        mContext.unregisterReceiver(mAppUpdateReceiver);
        mContext.unregisterReceiver(mUserUpdateReceiver);
        mContext.unregisterReceiver(mShutdownEventReceiver);
        cancelAnomalyAlarm();
        cancelPullingAlarm();
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, writer)) return;

        synchronized (sStatsdLock) {
            writer.println(
                    "Number of configuration files deleted: " + mDeletedFiles.size());
            if (mDeletedFiles.size() > 0) {
                writer.println("  timestamp, deleted file name");
            }
            long lastBootMillis =
                    SystemClock.currentThreadTimeMillis() - SystemClock.elapsedRealtime();
            for (Long elapsedMillis : mDeletedFiles.keySet()) {
                long deletionMillis = lastBootMillis + elapsedMillis;
                writer.println(
                        "  " + deletionMillis + ", " + mDeletedFiles.get(elapsedMillis));
            }
        }
    }

    // Thermal event received from vendor thermal management subsystem
    private static final class ThermalEventListener extends IThermalEventListener.Stub {
        @Override
        public void notifyThrottling(boolean isThrottling, Temperature temp) {
            StatsLog.write(StatsLog.THERMAL_THROTTLING, temp.getType(),
                    isThrottling ? 1 : 0, temp.getValue());
        }
    }
}
