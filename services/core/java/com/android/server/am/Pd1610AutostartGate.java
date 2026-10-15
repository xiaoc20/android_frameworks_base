/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.app.ActivityManager;
import android.app.AppGlobals;
import android.content.Intent;
import android.content.pm.IPackageManager;
import android.os.Handler;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.server.ServiceThread;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * PD1610: 自启动 / 唤起闸门。
 *
 * 「划卡 = force-stop」和 AppFreezer 都只解决「进程已经起来之后怎么办」。
 * 真正让国产 App 复活的是**被广播 / Provider 从零拉起**：进程刚被杀掉，
 * 一条隐式广播或一次 ContentProvider 访问就把它重新 fork 出来。
 * MIUI 的对应件是 BroadcastQueueInjector（广播拦截）与
 * MiuiContentProviderControl（Provider 自启控制），本类就是这两者的最小版。
 *
 * SHIPPED OFF. 总闸关闭时，本类**一次白名单 IO、一次判定都不做** ——
 * 行为与打补丁前逐字节一致。
 *
 *   persist.sys.pd1610.autostart_gate     = false  # 总闸（默认关）
 *   persist.sys.pd1610.autostart_provider = false  # Provider 侧子闸（默认关）
 *   persist.sys.pd1610.autostart_allow    = ""     # 逗号分隔包名，追加白名单
 *
 * 拦的是「唤醒」，不是「运行」：只有在目标进程**根本不在跑**（冷启），
 * 或者它所在的 uid 已经**完全空闲**（温启）时才拦。任何前台 / 可见 /
 * 有前台服务 / 系统级白名单命中，一律放行。
 *
 * ⚠ 唯一的致命点：BOOT_COMPLETED 一族必须放行，漏了就是一个开不了机的包。
 *   本类用**三重**保护（见 isAlwaysAllowedBroadcast）：
 *     ① Intent.FLAG_RECEIVER_OFFLOAD / FLAG_RECEIVER_BOOT_UPGRADE
 *     ② ESSENTIAL_ACTIONS 显式动作集合
 *     ③ isProtectedBroadcast(action)（framework-res 把 BOOT_COMPLETED 声明成了
 *        <protected-broadcast>，所以这一条本身就够）
 *
 * 不覆盖：bind/startService 唤醒（走 ActiveServices.startProcessLocked，
 * 不在本类的两条路上）。那是另一半，留待下一轮。
 */
final class Pd1610AutostartGate {

    private static final String TAG = "Pd1610AutostartGate";
    private static final boolean DEBUG = false;

    /** 总闸。默认 false。 */
    static final String GATE_PROP = "persist.sys.pd1610.autostart_gate";

    /** Provider 侧子闸。默认 false（便于先只灰度广播那一半）。 */
    static final String PROVIDER_PROP = "persist.sys.pd1610.autostart_provider";

    /** 额外白名单，逗号分隔包名。默认空。 */
    static final String ALLOW_PROP = "persist.sys.pd1610.autostart_allow";

    /** 内置白名单文件（system 侧，随 ROM 出）。 */
    private static final String SYSTEM_XML = "/system/etc/sysconfig/pd1610_autostart.xml";

    /** 覆盖用白名单文件（data 侧，热更新用，非必需）。 */
    private static final String DATA_XML = "/data/system/pd1610_autostart.xml";

    /** 白名单 stat 的最小间隔，避免每次判定都打一次 syscall。 */
    private static final long STAT_INTERVAL_MS = 1000;

    /** isProtectedBroadcast 查询结果缓存上限。 */
    private static final int MAX_PROTECTED_CACHE = 256;

    /**
     * 内置白名单：**永不拦**。
     *   - android / systemui / settings：系统自己的门窗，拦了会拆掉用户回桌面的路；
     *   - 后面六个与 persist.sys.pd1610.swipe_kill_keep 的默认值保持一致
     *     （ActivityStackSupervisor 里那份），IM 的推送就是它们的命根子，
     *     用户明确不希望被划卡 / 被闸门动到。
     * 其余一律走 XML（见 device/vivo/pd1610/configs/pd1610_autostart.xml）。
     */
    private static final String[] BUILTIN_ALLOW = new String[] {
            "android",
            "com.android.systemui",
            "com.android.settings",
            // ---- 与 swipe_kill_keep 默认值对齐 ----
            "com.tencent.mm",                   // 微信
            "com.tencent.mobileqq",             // QQ
            "com.tencent.tim",                  // TIM
            "com.tencent.wework",               // 企业微信
            "com.alibaba.android.rimet",        // 钉钉
            "com.tencent.androidqqmail",        // QQ 邮箱
    };

    /**
     * 开机 / 系统必需动作：**永不拦**。这是 ① 之外的显式兜底，
     * 因为这些广播不一定会带上 FLAG_RECEIVER_OFFLOAD。
     */
    private static final String[] ESSENTIAL_ACTIONS = new String[] {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_PRE_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REMOVED,
            Intent.ACTION_PACKAGE_CHANGED,
            Intent.ACTION_PACKAGE_FULLY_REMOVED,
            Intent.ACTION_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_DATA_CLEARED,
            Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE,
            Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE,
            Intent.ACTION_USER_ADDED,
            Intent.ACTION_USER_REMOVED,
            Intent.ACTION_USER_STARTED,
            Intent.ACTION_USER_STOPPED,
            Intent.ACTION_USER_SWITCHED,
            Intent.ACTION_USER_BACKGROUND,
            Intent.ACTION_USER_FOREGROUND,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MEDIA_MOUNTED,
            Intent.ACTION_SHUTDOWN,
    };

    /** 只保护内存表；文件 IO 绝不在这把锁里做。 */
    private static final Object sLock = new Object();

    /** 白名单快照。发布后不再修改（写入方在后台线程，读取方是 AMS 调用线程）。 */
    private static volatile Set<String> sAllowPackages;

    /** 上次成功加载时，两份 XML 里较新的那个 mtime。 */
    private static volatile long sLoadedMtime = -1;

    /** 是否已经有后台线程在读盘，防重入。 */
    private static boolean sLoadScheduled;

    /** stat 节流。 */
    private static volatile long sNextStatTime;

    /** isProtectedBroadcast 的查询缓存。 */
    @GuardedBy("sLock")
    private static final Map<String, Boolean> sProtectedCache = new HashMap<>();

    /** 懒起的读盘线程；只有总闸打开后才会被创建。 */
    private static ServiceThread sLoadThread;
    private static Handler sLoadHandler;

    private Pd1610AutostartGate() {
    }

    // ======================================================================
    // 入口（BroadcastQueue / ActivityManagerService 各调一处）
    // ======================================================================

    /**
     * 广播投递闸门。在 manifest 接收器即将**拉起进程**之前调用。
     * 调用点：BroadcastQueue.processNextBroadcastLocked()，在
     * {@code if (!skip && !Intent.ACTION_SHUTDOWN...)} 之前独立成段
     * （**不要**挂到 getAppStartModeLocked 那个 if 链上 —— 那条链
     * 在 uid 正在跑时返回 NORMAL，会让本闸门永远不进）。
     *
     * @return true = 应跳过这次投递（拦），false = 放行
     */
    static boolean shouldBlockBroadcastWakeup(ActivityManagerService svc, int uid,
            String packageName, String processName, int targetSdkVersion, BroadcastRecord r) {

        if (!SystemProperties.getBoolean(GATE_PROP, false)) {
            return false;   // 总闸关：一次判定都不做
        }
        if (isAlwaysAllowedBroadcast(r)) {
            return false;
        }
        final boolean block = shouldBlockWakeup(svc, uid, packageName, processName,
                targetSdkVersion);
        if (block) {
            Slog.w(TAG, "pd1610: autostart blocked broadcast "
                    + (r != null ? r.intent : null) + " to " + packageName + "/" + processName);
        }
        return block;
    }

    /**
     * Provider 自启闸门。在 startProcessLocked("content provider") **之前**调用，
     * 且**只在要新拉进程时**调用 —— 已经在跑的进程不判，免得拦到前台 App
     * 自己的 binder 访问。
     * 调用点：ActivityManagerService.getContentProviderImpl() 里那个
     * "else 分支（checkTime(...before start process...); ... startProcessLocked）"
     * 的开头。注意上面那个同名 javadoc 里的花括号是字面量，不是代码。
     *
     * @return true = 应放弃这次拉起（拦），false = 放行（照常 startProcessLocked）
     */
    static boolean shouldBlockProviderStart(ActivityManagerService svc, int uid,
            String packageName, String processName, int targetSdkVersion) {

        if (!SystemProperties.getBoolean(GATE_PROP, false)) {
            return false;
        }
        if (!SystemProperties.getBoolean(PROVIDER_PROP, false)) {
            return false;   // Provider 侧子闸默认关
        }
        final boolean block = shouldBlockWakeup(svc, uid, packageName, processName,
                targetSdkVersion);
        if (block) {
            Slog.w(TAG, "pd1610: autostart blocked provider start for "
                    + packageName + "/" + processName + " (uid=" + uid + ")");
        }
        return block;
    }

    // ======================================================================
    // 核心判定
    // ======================================================================

    /**
     * 判定「这次拉起是不是一次非白名单应用的复活」。
     *
     * 只有两个信号算唤醒：
     *   cold     —— 目标进程根本不在跑（app == null / 没 attach / 已 killed）
     *   warmIdle —— 进程在跑，但它所属的 uid 已经完全空闲（UidRecord.idle）
     * 这两个正好对齐 getAppStartModeLocked() 的进闸条件（uidRec == null || uidRec.idle），
     * 语义上不另起一套。
     */
    private static boolean shouldBlockWakeup(ActivityManagerService svc, int uid,
            String packageName, String processName, int targetSdkVersion) {

        // ---- 绝不拦：系统 uid ----
        if (uid < Process.FIRST_APPLICATION_UID) {
            return false;
        }
        if (UserHandle.isCore(uid)) {
            return false;
        }
        if (packageName == null || processName == null) {
            return false;
        }

        // ---- 绝不拦：我们自己 ----
        final String self = (svc.mContext != null) ? svc.mContext.getPackageName() : null;
        if (self != null && self.equals(packageName)) {
            return false;
        }

        // ---- 绝不拦：白名单 ----
        ensureLoaded();
        final Set<String> allow = sAllowPackages;
        if (allow == null) {
            // 名单还没读完：一律不拦。宁可漏拦，不可误拦 ——
            // 这个窗口只有开机后头几毫秒。
            return false;
        }
        if (allow.contains(packageName)) {
            return false;
        }

        // ---- 绝不拦：持久应用 / 系统后台白名单 / doze 白名单 ----
        // appServicesRestrictedInBackgroundLocked() 一次覆盖三张表：
        //   isPackagePersistent()          (AMS:6034)
        //   uidOnBackgroundWhitelist()     (AMS:6043, 私有, 本类调不到)
        //   isOnDeviceIdleWhitelistLocked() (AMS:6052)
        // 返回 APP_START_MODE_NORMAL 就表示「三张之一命中」。
        // ★ 这里刻意不用 getAppStartModeLocked()：它在 uid 正在跑时返回 NORMAL，
        //   分不清「白名单」和「刚好在跑」，当白名单 oracle 是错的。
        if (svc.appServicesRestrictedInBackgroundLocked(uid, packageName, targetSdkVersion)
                == ActivityManager.APP_START_MODE_NORMAL) {
            return false;
        }

        // ---- 绝不拦：uid 当前不处于后台 ----
        final UidRecord uidRec = svc.mProcessList.getUidRecordLocked(uid);
        if (uidRec != null) {
            if (!ActivityManager.isProcStateBackground(uidRec.getCurProcState())) {
                return false;   // 前台 / 可见 / 有前台服务级别
            }
            if (uidRec.foregroundServices) {
                return false;   // uid 内有前台服务，必须放行
            }
        }

        // ---- 绝不拦：目标进程自己有前台服务 ----
        final ProcessRecord app = svc.getProcessRecordLocked(processName, uid, false);
        if (app != null && app.hasForegroundServices()) {
            return false;
        }

        // ---- 到这里才判「唤醒」 ----
        final boolean cold = (app == null || app.thread == null || app.killed);
        final boolean warmIdle = (uidRec != null && uidRec.idle);

        if (DEBUG) {
            Slog.d(TAG, "pd1610: autostart gate " + packageName + "/" + processName
                    + " uid=" + uid + " cold=" + cold + " warmIdle=" + warmIdle);
        }
        return cold || warmIdle;
    }

    // ======================================================================
    // 广播侧：永不拦的清单
    // ======================================================================

    /**
     * ⚠ BOOT_COMPLETED 一族的三重豁免。少写任何一条都可能出一个开不了机的包。
     */
    private static boolean isAlwaysAllowedBroadcast(BroadcastRecord r) {
        if (r == null || r.intent == null) {
            return true;    // 无从判断：放行
        }
        final Intent it = r.intent;

        // ① offload 队列 / 开机升级广播
        //    AMS.isOnOffloadQueue(flags) 本质就是 FLAG_RECEIVER_OFFLOAD（AMS:19230）
        if ((it.getFlags() & Intent.FLAG_RECEIVER_OFFLOAD) != 0) {
            return true;
        }
        if ((it.getFlags() & Intent.FLAG_RECEIVER_BOOT_UPGRADE) != 0) {
            return true;
        }

        final String action = it.getAction();
        if (action == null) {
            // 纯组件定向广播（显式 Intent）：正是我们要闸的东西，交给判定
            return false;
        }

        // ② 显式动作集合
        for (String a : ESSENTIAL_ACTIONS) {
            if (a.equals(action)) {
                return true;
            }
        }

        // ③ 系统保护广播（framework-res 的 <protected-broadcast>，
        //    含 BOOT_COMPLETED / LOCKED_BOOT_COMPLETED / MY_PACKAGE_REPLACED …）
        return isProtectedBroadcast(action);
    }

    /**
     * 查询是否系统保护广播，带缓存。查不到时**当作保护**（放行）——
     * 同一条「宁可漏拦」的原则。这个调用是 binder，所以必须缓存。
     */
    private static boolean isProtectedBroadcast(String action) {
        synchronized (sLock) {
            final Boolean cached = sProtectedCache.get(action);
            if (cached != null) {
                return cached;
            }
        }

        boolean prot;
        try {
            final IPackageManager pm = AppGlobals.getPackageManager();
            prot = pm.isProtectedBroadcast(action);
        } catch (RemoteException e) {
            return true;    // 查不到 -> 放行
        }

        synchronized (sLock) {
            if (sProtectedCache.size() < MAX_PROTECTED_CACHE) {
                sProtectedCache.put(action, prot);
            }
        }
        return prot;
    }

    // ======================================================================
    // 白名单：加载 / 热更新
    // ======================================================================

    /**
     * 保证白名单已加载，并按 mtime 变化触发重读。
     *
     * ⚠ 本方法会被 **AMS 持锁线程** 调用，所以这里**只做 stat，不读文件内容**。
     *   真正的解析丢给一个专用的 ServiceThread（照 AppFreezer 的写法），
     *   解析完再把不可变快照揉进 sAllowPackages。
     *   总闸关着的时候这个方法根本不会被调。
     */
    private static void ensureLoaded() {
        final long now = SystemClock.uptimeMillis();

        // stat 节流：1 秒内最多探一次
        if (sAllowPackages != null && now < sNextStatTime) {
            return;
        }
        sNextStatTime = now + STAT_INTERVAL_MS;

        final long mtime = Math.max(new File(SYSTEM_XML).lastModified(),
                new File(DATA_XML).lastModified());
        if (sAllowPackages != null && mtime == sLoadedMtime) {
            return;     // 没变化
        }

        synchronized (sLock) {
            if (sLoadScheduled) {
                return;     // 已有后台线程在读
            }
            sLoadScheduled = true;

            if (sLoadHandler == null) {
                sLoadThread = new ServiceThread(TAG, Process.THREAD_PRIORITY_BACKGROUND,
                        false /* allowIo */);
                sLoadThread.start();
                sLoadHandler = new Handler(sLoadThread.getLooper());
            }
            sLoadHandler.post(Pd1610AutostartGate::loadWhitelist);
        }
    }

    /**
     * 真正的读盘 + 解析。**运行在 sLoadThread 上，不持 AMS 锁。**
     */
    private static void loadWhitelist() {
        final Set<String> pkgs = new ArraySet<>();

        for (String p : BUILTIN_ALLOW) {
            pkgs.add(p);
        }
        // system 侧随 ROM 出的那份
        readAllowXml(new File(SYSTEM_XML), pkgs);
        // data 侧覆盖/追加（热更新入口，优先级更高；这里只是并集）
        readAllowXml(new File(DATA_XML), pkgs);
        // prop 追加
        final String extra = SystemProperties.get(ALLOW_PROP, "");
        if (!extra.isEmpty()) {
            for (String s : extra.split(",")) {
                final String t = s.trim();
                if (!t.isEmpty()) {
                    pkgs.add(t);
                }
            }
        }

        final long mtime = Math.max(new File(SYSTEM_XML).lastModified(),
                new File(DATA_XML).lastModified());

        synchronized (sLock) {
            sAllowPackages = pkgs;      // 不可变快照，一次发布
            sLoadedMtime = mtime;
            sLoadScheduled = false;
        }

        Slog.i(TAG, "pd1610: autostart allow-list loaded, " + pkgs.size()
                + " packages (builtin=" + BUILTIN_ALLOW.length
                + ", mtime=" + mtime + ")");
    }

    /**
     * 解析 <process package="…" name="…"/> 形式的白名单。
     * 格式对齐厂件 power_greeze_config.xml 的 <non-critical-processes> 段。
     * v1 只按 **package** 粒度匹配（name 先读进来不管，留作 v2）。
     */
    private static void readAllowXml(File f, Set<String> out) {
        if (!f.exists() || !f.canRead()) {
            return;
        }
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            final XmlPullParser p = Xml.newPullParser();
            p.setInput(in, null);
            int ev;
            while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (ev != XmlPullParser.START_TAG) {
                    continue;
                }
                if (!"process".equals(p.getName())) {
                    continue;
                }
                final String pkg = p.getAttributeValue(null, "package");
                if (pkg != null && !pkg.isEmpty()) {
                    out.add(pkg);
                }
            }
        } catch (Exception e) {
            Slog.w(TAG, "pd1610: failed to parse allow-list " + f + ": " + e);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
    }

    // ======================================================================
    // dumpsys（尚未接到 AMS.dump 上，留作调试入口）
    // ======================================================================

    static void dump(PrintWriter pw) {
        final Set<String> allow = sAllowPackages;
        pw.println("  Pd1610AutostartGate:");
        pw.println("    " + GATE_PROP + "=" + SystemProperties.get(GATE_PROP, "<unset>"));
        pw.println("    " + PROVIDER_PROP + "="
                + SystemProperties.get(PROVIDER_PROP, "<unset>"));
        pw.println("    " + ALLOW_PROP + "=" + SystemProperties.get(ALLOW_PROP, "<unset>"));
        pw.println("    allowListLoaded=" + (allow != null)
                + " size=" + (allow != null ? allow.size() : 0)
                + " mtime=" + sLoadedMtime);
    }
}
