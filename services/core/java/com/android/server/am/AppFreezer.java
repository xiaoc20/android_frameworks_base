/* Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.am;

import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.server.ServiceThread;

import java.io.FileReader;
import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * PD1610: cgroup v1 freezer driver for cached processes.
 *
 * This is a deliberately small backport of the freezer half of Android 11's
 * CachedAppOptimizer (freezeAppAsync / unfreezeAppLocked / FreezeHandler).
 * The app-compaction half is left alone -- this tree already has AppCompactor,
 * and the freezer deserves to be enable-able and debuggable on its own.
 *
 * SHIPPED OFF.  Nothing here freezes anything unless BOTH:
 *   - persist.sys.pd1610.freezer is "true", and
 *   - the kernel exposes a *killable* freezer (freezer.killable reads '1').
 *
 * The second condition is not a nicety: a process frozen by a non-killable
 * freezer ignores SIGKILL, so lmkd's kills and killProcessGroup() would block
 * forever.  If the kernel side is missing we refuse to run at all.
 *
 * Deliberate differences from A11 (see the OomAdjuster wiring too):
 *   - No shouldNotFreeze flag.  A11 sets that from the BIND_WAIVE_PRIORITY
 *     branch of computeOomAdjLocked; rather than rework that loop we ask the
 *     process itself, right before freezing it, whether anything is still
 *     bound to its services.  Strictly more conservative.
 *   - No EventLog / FrameworkStatsLog.  EventLogTags.AM_FREEZE and
 *     FrameworkStatsLog.APP_FREEZE_CHANGED do not exist in Android 10.
 *   - No DeviceConfig toggle.  A read-only system property keeps the knobs to
 *     one place and makes "did I leave this on?" answerable from a shell.
 */
public final class AppFreezer {

    private static final String TAG = "AppFreezer";
    private static final boolean DEBUG = false;

    /** Enable knob, read once at boot.  Default: off. */
    static final String FREEZER_PROP = "persist.sys.pd1610.freezer";

    /** Written by init.pd1610.rc; presence + value '1' means freeze is safe. */
    private static final String KILLABLE_PATH = "/dev/freezer/frozen/freezer.killable";

    static final int SET_FROZEN_PROCESS_MSG = 1;

    /** Grace period between deciding to freeze and actually freezing. */
    static final int FREEZE_TIMEOUT_MS = 500;
    static final int DO_FREEZE = 1;

    private final ActivityManagerService mService;
    private final ServiceThread mThread;

    private Handler mHandler;
    private boolean mSupported;
    private volatile boolean mEnabled;

    AppFreezer(ActivityManagerService service) {
        mService = service;
        mThread = new ServiceThread(TAG, Process.THREAD_PRIORITY_BACKGROUND,
                false /* allowIo */);
    }

    /**
     * Called once, from OomAdjuster.initSettings().  Never throws: a freezer
     * that cannot be supported must degrade to "no freezer", not to a boot
     * loop.
     */
    void init() {
        mSupported = isFreezerSupported();
        final boolean wanted = SystemProperties.getBoolean(FREEZER_PROP, false);

        if (!mSupported) {
            mEnabled = false;
            if (wanted) {
                Slog.w(TAG, FREEZER_PROP + "=true but " + KILLABLE_PATH
                        + " is missing or killability is off; freezer stays disabled");
            }
            return;
        }

        mEnabled = wanted;
        if (mEnabled) {
            Slog.i(TAG, "app freezer enabled");
            mThread.start();
            /* PD1610 fix: 这里必须是 FreezeHandler。普通 Handler 会把
               SET_FROZEN_PROCESS_MSG 丢进默认的空 handleMessage()，于是
               freezeProcess()（唯一调用 Process.setProcessFrozen(pid, uid,
               true) 的地方）成了死代码 —— 属性开了也一个进程都冻不上。
               必须留在 mThread.start() 之后：ServiceThread.getLooper() 会
               阻塞等 looper 建好，线程没活时返回 null，Handler(null) 会抛
               IllegalArgumentException。与 A11
               CachedAppOptimizer.java:479 的顺序一致。 */
            mHandler = new FreezeHandler();
        } else {
            Slog.i(TAG, "app freezer supported but disabled (" + FREEZER_PROP + "=false)");
        }
    }

    /**
     * Determines whether the freezer is correctly supported by this system.
     * Verbatim from A11 CachedAppOptimizer.isFreezerSupported(), same path.
     */
    public static boolean isFreezerSupported() {
        boolean supported = false;
        FileReader fr = null;

        try {
            fr = new FileReader(KILLABLE_PATH);
            int i = fr.read();

            if ((char) i == '1') {
                supported = true;
            } else {
                Slog.w(TAG, "Freezer killability is turned off, disabling freezer");
            }
        } catch (java.io.FileNotFoundException e) {
            Slog.d(TAG, "Freezer.killable not present, disabling freezer");
        } catch (Exception e) {
            Slog.d(TAG, "Unable to read freezer.killable, disabling freezer: " + e.toString());
        }

        if (fr != null) {
            try {
                fr.close();
            } catch (java.io.IOException e) {
                Slog.e(TAG, "Exception closing freezer.killable: " + e.toString());
            }
        }

        return supported;
    }

    /** Whether the freezer is enabled.  Callers gate on this. */
    public boolean useFreezer() {
        return mEnabled;
    }

    @GuardedBy("mService")
    void freezeAppAsync(ProcessRecord app) {
        if (mHandler == null) {
            return;
        }

        mHandler.removeMessages(SET_FROZEN_PROCESS_MSG, app);

        mHandler.sendMessageDelayed(
                mHandler.obtainMessage(SET_FROZEN_PROCESS_MSG, DO_FREEZE, 0, app),
                FREEZE_TIMEOUT_MS);
    }

    @GuardedBy("mService")
    void unfreezeAppLocked(ProcessRecord app) {
        if (mHandler != null) {
            mHandler.removeMessages(SET_FROZEN_PROCESS_MSG, app);
        }

        // Deliberately NOT gated on mEnabled: if an app is frozen we must be
        // able to thaw it even if something went wrong with the knob.
        if (!app.frozen) {
            return;
        }

        try {
            Process.setProcessFrozen(app.pid, app.uid, false);

            app.freezeUnfreezeTime = SystemClock.uptimeMillis();
            app.frozen = false;

            if (DEBUG) {
                Slog.d(TAG, "unfroze " + app.pid + " " + app.processName);
            }
        } catch (Exception e) {
            Slog.e(TAG, "Unable to unfreeze " + app.pid + " " + app.processName
                    + ". Any related user experience might be hanged.", e);
        }
    }

    /**
     * Conservative stand-in for A11's app.shouldNotFreeze: a process that is
     * still bound by a client is doing work on someone else's behalf, so leave
     * it running even though its oom_adj says "cached".
     */
    private static boolean hasActiveServiceConnections(ProcessRecord app) {
        for (int i = app.services.size() - 1; i >= 0; i--) {
            ServiceRecord sr = app.services.valueAt(i);
            if (sr != null && sr.getConnections().size() > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * PD1610: 进程所属包名。见 Pd1610DeepFreeze.packageOf()。
     */
    private static String packageOf(ProcessRecord app) {
        return Pd1610DeepFreeze.packageOf(app);
    }

    /**
     * PD1610: 这个进程里有没有**真的被 start 过**的 service。
     * startRequested=true 意味着有人明确要求它跑（下载、播放、录音、录音笔…），
     * 冻下去就是把它手里的活掐了 —— 这不是「闲置」，所以不冻。
     * 只被 bind 的 service（B站 的 :download 就是这种，startRequested=false）
     * 不算：那只是个僵尸绑定，对端一冻它就该跟着睡。
     */
    private static boolean hasStartedService(ProcessRecord app) {
        for (int i = app.services.size() - 1; i >= 0; i--) {
            ServiceRecord sr = app.services.valueAt(i);
            if (sr != null && sr.startRequested) {
                return true;
            }
        }
        return false;
    }

    /**
     * PD1610: 这个进程的 service 上，有没有**外来的、还活着的** client。
     *
     * 与 hasActiveServiceConnections() 的区别就是「外来」和「活着」两个词：
     *   - client 是本进程自己（自绑，B站 main 与它自己的 IjkMediaPlayerLocalService）
     *     ⇒ 不算，同一个进程不存在「被别人等」；
     *   - client 是同包、且不会被冻的进程（推送进程，豁免名表命中）
     *     ⇒ **算**，它真在等这个 service 回话，冻了它就把推送卡住；
     *   - client 已经冻着 ⇒ 不算，反正它自己也不跑；
     *   - 其余（别的包 / 解析不出）⇒ 算，保守。
     */
    private static boolean hasForeignServiceConnections(ProcessRecord app, String pkg) {
        for (int i = app.services.size() - 1; i >= 0; i--) {
            final ServiceRecord sr = app.services.valueAt(i);
            if (sr == null) {
                continue;
            }
            final ArrayMap<IBinder, ArrayList<ConnectionRecord>> byBinder = sr.getConnections();
            for (int b = byBinder.size() - 1; b >= 0; b--) {
                final ArrayList<ConnectionRecord> conns = byBinder.valueAt(b);
                for (int c = conns.size() - 1; c >= 0; c--) {
                    final ConnectionRecord cr = conns.get(c);
                    final ProcessRecord client = cr.binding != null ? cr.binding.client : null;
                    if (client == null) {
                        return true;            // 解析不出来 ⇒ 当外人，保守
                    }
                    if (client == app) {
                        continue;               // 自绑自己
                    }
                    if (client.frozen) {
                        continue;               // 对方也冻着，不用替它保活
                    }
                    final String cpkg = packageOf(client);
                    if (pkg != null && pkg.equals(cpkg)
                            && !Pd1610DeepFreeze.isExemptProcess(cpkg, client.processName)) {
                        continue;               // 同包、且它也会被冻 ⇒ 一起
                    }
                    return true;
                }
            }
        }
        return false;
    }

    private final class FreezeHandler extends Handler {
        private FreezeHandler() {
            super(mThread.getLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case SET_FROZEN_PROCESS_MSG:
                    freezeProcess((ProcessRecord) msg.obj);
                    break;
                default:
                    return;
            }
        }

        private void freezeProcess(ProcessRecord proc) {
            final int pid;
            final String name;
            final boolean frozen;
            boolean deepFrozen = false;

            synchronized (mService) {
                if (!mEnabled) {
                    return;
                }

                pid = proc.pid;
                name = proc.processName;

                if (pid == 0 || proc.frozen) {
                    // Already frozen, or not a real process: either one being
                    // launched or one being killed.
                    return;
                }

                // ---- PD1610: 深度冻结（默认关）--------------------------------
                // 允许名单里的包（自带独立推送进程的国产 App，见 Pd1610DeepFreeze）
                // 放宽两处：
                //   ① 阈值 900 → SERVICE_B_ADJ(800)。这类 App 后台常年挂着一个
                //      bound service，oom_adj 掉不到 900，老的阈值一辈子够不着它。
                //      800 这一档正好是「被 cached client 绑着的 service」，而仍
                //      在 PREVIOUS_APP_ADJ(700) 之上 ⇒ 回切上一个 App 依然不冻。
                //   ② 「还绑着 client 就不冻」→ 只拦**外来**的活 client；同包自绑
                //      （B站 main ↔ :download/:web）不算，它们一起冻一起醒。
                // 仍然不冻：推送进程（豁免名表）、有 startRequested service 的
                // 进程（真有人在让它干活）、有外来活 client 的进程。
                final String pkg = packageOf(proc);
                final boolean deep = Pd1610DeepFreeze.isArmed()
                        && Pd1610DeepFreeze.isDeepPackage(pkg);
                final int freezeAdj = deep
                        ? ProcessList.SERVICE_B_ADJ : ProcessList.CACHED_APP_MIN_ADJ;

                // Re-check everything: the process may have moved on during the
                // FREEZE_TIMEOUT_MS grace period.
                if (proc.curAdj < freezeAdj) {
                    if (DEBUG) {
                        Slog.d(TAG, "Skipping freeze for process " + pid + " " + name
                                + " curAdj = " + proc.curAdj + " < " + freezeAdj);
                    }
                    return;
                }

                if (deep) {
                    if (Pd1610DeepFreeze.isExemptProcess(pkg, name)) {
                        if (DEBUG) {
                            Slog.d(TAG, "Skipping deep freeze for push process " + name);
                        }
                        return;
                    }
                    if (hasStartedService(proc)) {
                        if (DEBUG) {
                            Slog.d(TAG, "Skipping deep freeze for " + name
                                    + ": somebody actually started a service in it");
                        }
                        return;
                    }
                    if (hasForeignServiceConnections(proc, pkg)) {
                        if (DEBUG) {
                            Slog.d(TAG, "Skipping deep freeze for " + name
                                    + ": a live client outside this app is bound to it");
                        }
                        return;
                    }
                } else if (hasActiveServiceConnections(proc)) {
                    if (DEBUG) {
                        Slog.d(TAG, "Skipping freeze for process " + pid + " " + name
                                + ": still has bound clients");
                    }
                    return;
                }

                try {
                    Process.setProcessFrozen(pid, proc.uid, true);

                    proc.freezeUnfreezeTime = SystemClock.uptimeMillis();
                    proc.frozen = true;
                } catch (Exception e) {
                    Slog.w(TAG, "Unable to freeze " + pid + " " + name, e);
                }

                frozen = proc.frozen;
                deepFrozen = frozen && deep;
            }

            if (frozen) {
                if (DEBUG || deepFrozen) {
                    // 深度冻结按 INFO 记：这是唯一一条「本来不该冻、我们冻了」的
                    // 路径，现场排查时希望不打开 DEBUG 就能看到。
                    Slog.i(TAG, "froze " + pid + " " + name + (deepFrozen ? " (deep)" : ""));
                }
            }
        }
    }

    @GuardedBy("mService")
    void dump(PrintWriter pw) {
        pw.println("  AppFreezer:");
        pw.println("    supported=" + mSupported + " enabled=" + mEnabled);
        pw.println("    " + FREEZER_PROP + "="
                + SystemProperties.get(FREEZER_PROP, "<unset>"));
        Pd1610DeepFreeze.dump(pw);
    }
}
