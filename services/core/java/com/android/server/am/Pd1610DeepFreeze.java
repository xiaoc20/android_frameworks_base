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

import android.os.Process;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.Xml;

import com.android.server.ServiceThread;

import org.xmlpull.v1.XmlPullParser;

import android.os.Handler;

import java.io.File;
import java.io.FileInputStream;
import java.io.PrintWriter;
import java.util.Map;
import java.util.Set;

/**
 * PD1610: 深度冻结（deep freeze）—— 把「有常驻服务绑着、所以永远掉不进 cached」
 * 的国产 App 也真正冻住，但**保留它的推送进程**。
 *
 * 背景（2026-10-16 实测）：
 *   本树 AppFreezer 的冻结条件是 curAdj >= CACHED_APP_MIN_ADJ(900)，
 *   而 B站/微博 这类 App 后台常年挂着一个 bound/started service ⇒ oom_adj 卡在
 *   500~800 ⇒ **冻结器一辈子碰不到它**。同一道缝也是 checkExcessivePowerUsageLocked
 *   的缝（它只查 setProcState >= PROCESS_STATE_HOME）。于是「后台不管」。
 *   设备侧手工对照：只冻 main/:download/:web、放过 :pushservice，
 *   B站 uid CPU 11.8 s → 1.0 s（−92%），全机 busy 10.0% → 5.0%，
 *   无 ANR/无广播超时，解冻后是**温启动**（进程没被杀，am_on_resume 同一 pid）。
 *
 * SHIPPED OFF. 总闸关闭时本类不做任何判定，冻结器行为与补丁前逐字节一致。
 *
 *   persist.sys.pd1610.deepfreeze        = false  # 总闸（默认关）
 *   persist.sys.pd1610.deepfreeze_allow  = ""     # 逗号分隔包名，追加允许名单
 *
 * 允许名单里的包，其进程会走「深度冻结」那套规则（见 AppFreezer.freezeProcess）：
 *   ✔ 忽略「client 是本包自己」的 service 绑定 —— 同包互绑的进程要么一起冻，
 *     要么一起醒，绑着不构成「有人在等它」；
 *   ✘ 但有三种仍然不冻：
 *       ① 进程名命中豁免表（推送进程，默认 :push / :pushservice / :mipush / …）
 *       ② 进程里有 startRequested 的 service（真的有人在让它干活，比如正在下载）
 *       ③ 有**外来的、还活着的** client 绑在它的 service 上
 *
 * ⚠ 唯一不能省的是「豁免推送进程」：冻了推送进程 = 这个 App 收不到任何消息，
 *   而且没有任何东西能把它叫醒。MIUI 靠内核钩子（Millet/binder freeze）解决，
 *   我们这条路不需要内核：推送进程活着 ⇒ 收到消息 ⇒ startService/广播拉起主进程
 *   ⇒ AMS 里的 thaw 钩子（见 BroadcastQueue/ActiveServices/AMS 里的
 *   Pd1610DeepFreeze.thawFor）先解冻再投递。
 */
final class Pd1610DeepFreeze {

    private static final String TAG = "Pd1610DeepFreeze";
    private static final boolean DEBUG = false;

    /** 总闸。默认 false。 */
    static final String ARM_PROP = "persist.sys.pd1610.deepfreeze";

    /** 额外允许名单，逗号分隔包名。默认空。 */
    static final String ALLOW_PROP = "persist.sys.pd1610.deepfreeze_allow";

    /** 允许名单文件（system 侧，随 ROM 出）。 */
    private static final String SYSTEM_XML = "/system/etc/sysconfig/pd1610_deepfreeze.xml";

    /** 覆盖/追加用（data 侧，热更新，非必需）。 */
    private static final String DATA_XML = "/data/system/pd1610_deepfreeze.xml";

    /** 属性读取节流：hot path（每次 oom adj 计算）不能每回都打 JNI。 */
    private static final long PROP_TTL_MS = 1000;

    /** 名单 stat 节流。 */
    private static final long STAT_INTERVAL_MS = 1000;

    /**
     * 内置允许名单：**只放「自带独立推送进程、冻了也不会漏消息」的 App**。
     *
     * 这两个（B站 / 微博）是 2026-10-16 现场量出来的：两者的推送进程都是
     *   <pkg>:pushservice  ←  com.xiaomi.push.service.XMPushService（MiPush）
     * 冻掉主进程之后，MiPush 那条 600 s 的心跳还在（实测没断），
     * 有消息时它会 startService 主进程 ⇒ 我们解冻 ⇒ 正常收。
     *
     * ⛔ 不要往里加「只有自己长连接、没有第三方推送」的 App（比如某些 IM）：
     *   冻住 = 那条 TCP 不再被读 = 真·漏消息，而且没人能叫醒它。
     */
    private static final String[] BUILTIN_ALLOW = new String[] {
            "tv.danmaku.bili",      // B站（用户点名要深的）
            "com.sina.weibo",       // 微博（同上）
    };

    /**
     * 内置豁免进程名后缀：命中就**永不冻**。
     * 这些都是各家 SDK 的推送 / 常驻进程名，冻掉等于把这个 App 的消息通道拆了。
     */
    private static final String[] BUILTIN_EXEMPT_SUFFIX = new String[] {
            ":push",            // 微信/QQ邮箱 等
            ":pushservice",     // MiPush（B站/微博 实测）
            ":pushcore",        // 华为/荣耀
            ":mipush",
            ":msf",             // QQ 的 MSF
            ":daemon",
            ":remote",
    };

    /** 只保护内存表；读盘绝不在锁里做。 */
    private static final Object sLock = new Object();

    /** 允许包 → 该包额外的豁免进程后缀（XML 的 exempt 属性）。不可变快照。 */
    private static volatile Map<String, Set<String>> sAllow;

    private static volatile long sLoadedMtime = -1;
    private static boolean sLoadScheduled;
    private static volatile long sNextStatTime;

    /** 总闸的缓存值，避免 hot path 每次读属性。 */
    private static volatile boolean sArmedCache;
    private static volatile long sArmedNextRead;

    private static ServiceThread sLoadThread;
    private static Handler sLoadHandler;

    // ======================================================================
    // 总闸
    // ======================================================================

    /**
     * 深度冻结是否开着。**这是每次 oom adj 计算都会走的路**，所以带 1 s 缓存；
     * 关着的时候本类一次名单 IO、一次判定都不做。
     */
    static boolean isArmed() {
        final long now = SystemClock.uptimeMillis();
        if (now < sArmedNextRead) {
            return sArmedCache;
        }
        final boolean armed = SystemProperties.getBoolean(ARM_PROP, false);
        sArmedCache = armed;
        sArmedNextRead = now + PROP_TTL_MS;
        return armed;
    }

    // ======================================================================
    // 身份
    // ======================================================================

    /**
     * 进程所属包名。
     * 孤立进程（webview sandbox 之类）的 info.packageName 指向宿主 APK
     * （com.android.webview），不是 App 自己的包 —— 那种进程天然不在允许名单里，
     * 也就不冻。这是故意的：它的 binder 对端一冻，它自己就阻塞在 binder 上，
     * 本来也不烧 CPU。
     */
    static String packageOf(ProcessRecord app) {
        if (app == null) {
            return null;
        }
        if (app.info != null && app.info.packageName != null) {
            return app.info.packageName;
        }
        final String n = app.processName;
        if (n == null) {
            return null;
        }
        final int colon = n.indexOf(':');
        return colon > 0 ? n.substring(0, colon) : n;
    }

    /**
     * 深冻的 oom_adj 阈值：SERVICE_B_ADJ(800)。
     * 冻结与解冻**必须用同一个值**，否则会来回抽（800 冻上、< 900 立刻又解开）。
     * 取 800 而不是 500 的理由：700 是 PREVIOUS_APP_ADJ，回切上一个 App 的那一档
     * 不能冻，否则「按一下回上个 App」就要走解冻路径。800 这一档恰好是
     * 「被 cached client 绑着的 service」，也正是国产 App 僵尸进程的落点。
     */
    static int deepFreezeAdj() {
        return ProcessList.SERVICE_B_ADJ;
    }

    // ======================================================================
    // 名单
    // ======================================================================

    /** 这个包是否允许深度冻结。会顺手把名单读起来（异步）。 */
    static boolean isDeepPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        ensureLoaded();
        final Map<String, Set<String>> allow = sAllow;
        if (allow == null) {
            // 名单还没读出来（开机头一秒）：**保守放行**，按老行为走。
            for (String p : BUILTIN_ALLOW) {
                if (p.equals(pkg)) {
                    return true;
                }
            }
            return false;
        }
        return allow.containsKey(pkg);
    }

    /**
     * 这个进程是否**永不冻**（推送进程）。pkg 为 null 时只看内置后缀表。
     */
    static boolean isExemptProcess(String pkg, String processName) {
        if (processName == null || processName.isEmpty()) {
            return false;
        }
        // 主进程（进程名 == 包名）永远不是推送进程。
        if (pkg != null && processName.equals(pkg)) {
            return false;
        }
        final int colon = processName.lastIndexOf(':');
        if (colon < 0) {
            return false;
        }
        final String suffix = processName.substring(colon);
        for (String s : BUILTIN_EXEMPT_SUFFIX) {
            if (suffix.equals(s)) {
                return true;
            }
        }
        // XML 里逐包追加的豁免（<app package="…" exempt=":foo,:bar"/>）
        final Map<String, Set<String>> allow = sAllow;
        if (allow != null && pkg != null) {
            final Set<String> extra = allow.get(pkg);
            if (extra != null) {
                for (String s : extra) {
                    if (suffix.equals(s)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ======================================================================
    // 解冻钩子
    // ======================================================================

    /**
     * 有活要派给这个进程了 —— 先解冻再派。
     *
     * **这是深度冻结能成立的关键**：冻住的进程不处理任何 binder，
     * 而多数「派活」路径并不会改变目标的 oom_adj（已经 500 的进程再被
     * startService 一次，adj 还是 500），于是 updateAppFreezeStateLocked()
     * 根本不会替我们解冻 ⇒ 那条 startService 会一直躺在队列里。
     *
     * 调用方必须持有 AMS 锁（unfreezeAppLocked 自己带 @GuardedBy("mService")）。
     * 没开总闸、或进程本来就没冻着，直接返回 —— 零开销。
     */
    static void thawFor(ActivityManagerService service, ProcessRecord app, String why) {
        if (app == null || !app.frozen) {
            return;
        }
        if (!isArmed()) {
            // 关闸之后残留的冻结（比如刚 setprop 关掉）：还是要解，别让它永远躺着。
            if (DEBUG) {
                Slog.d(TAG, "thawing " + app.processName + " (" + why + ") with deep freeze off");
            }
        }
        service.mOomAdjuster.mAppFreezer.unfreezeAppLocked(app);
        Slog.i(TAG, "pd1610: thawed " + app.processName + " (pid " + app.pid
                + ") for " + why);
    }

    // ======================================================================
    // 名单 IO（照 Pd1610AutostartGate 的写法：stat 在调用线程，读盘在后台线程）
    // ======================================================================

    private static void ensureLoaded() {
        final long now = SystemClock.uptimeMillis();
        if (sAllow != null && now < sNextStatTime) {
            return;
        }
        sNextStatTime = now + STAT_INTERVAL_MS;

        final long mtime = Math.max(new File(SYSTEM_XML).lastModified(),
                new File(DATA_XML).lastModified());
        if (sAllow != null && mtime == sLoadedMtime) {
            return;
        }

        synchronized (sLock) {
            if (sLoadScheduled) {
                return;
            }
            sLoadScheduled = true;

            if (sLoadHandler == null) {
                sLoadThread = new ServiceThread(TAG, Process.THREAD_PRIORITY_BACKGROUND,
                        false /* allowIo */);
                sLoadThread.start();
                sLoadHandler = new Handler(sLoadThread.getLooper());
            }
            sLoadHandler.post(Pd1610DeepFreeze::load);
        }
    }

    /** 真正的读盘 + 解析。不持 AMS 锁。 */
    private static void load() {
        final Map<String, Set<String>> allow = new ArrayMap<>();

        for (String p : BUILTIN_ALLOW) {
            allow.put(p, null);
        }
        readXml(new File(SYSTEM_XML), allow);
        readXml(new File(DATA_XML), allow);

        final String extra = SystemProperties.get(ALLOW_PROP, "");
        if (!extra.isEmpty()) {
            for (String s : extra.split(",")) {
                final String t = s.trim();
                if (!t.isEmpty() && !allow.containsKey(t)) {
                    allow.put(t, null);
                }
            }
        }

        final long mtime = Math.max(new File(SYSTEM_XML).lastModified(),
                new File(DATA_XML).lastModified());

        synchronized (sLock) {
            sAllow = allow;
            sLoadedMtime = mtime;
            sLoadScheduled = false;
        }

        Slog.i(TAG, "pd1610: deep-freeze allow-list loaded, " + allow.size()
                + " packages (builtin=" + BUILTIN_ALLOW.length + ", mtime=" + mtime + ")");
    }

    /**
     * 解析 <app package="…" exempt=":push,:foo"/>。
     * 文件名与元素名都从零起，故意**不复用** autostart 那份（语义不同：
     * 那份是「不许拦」，这份是「可以冻」，混在一份文件里早晚会改错）。
     */
    private static void readXml(File f, Map<String, Set<String>> out) {
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
                if (ev != XmlPullParser.START_TAG || !"app".equals(p.getName())) {
                    continue;
                }
                final String pkg = p.getAttributeValue(null, "package");
                if (pkg == null || pkg.isEmpty()) {
                    continue;
                }
                Set<String> ex = out.get(pkg);
                final String attrs = p.getAttributeValue(null, "exempt");
                if (attrs != null && !attrs.isEmpty()) {
                    if (ex == null) {
                        ex = new ArraySet<>();
                        out.put(pkg, ex);
                    }
                    for (String s : attrs.split(",")) {
                        final String t = s.trim();
                        if (!t.isEmpty()) {
                            ex.add(t);
                        }
                    }
                } else if (!out.containsKey(pkg)) {
                    out.put(pkg, null);
                }
            }
        } catch (Exception e) {
            Slog.w(TAG, "pd1610: failed to parse deep-freeze allow-list " + f + ": " + e);
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
    // dumpsys
    // ======================================================================

    static void dump(PrintWriter pw) {
        final Map<String, Set<String>> allow = sAllow;
        pw.println("  Pd1610DeepFreeze:");
        pw.println("    " + ARM_PROP + "=" + SystemProperties.get(ARM_PROP, "<unset>")
                + " (armed=" + isArmed() + ")");
        pw.println("    " + ALLOW_PROP + "=" + SystemProperties.get(ALLOW_PROP, "<unset>"));
        pw.println("    allowLoaded=" + (allow != null)
                + " size=" + (allow != null ? allow.size() : 0)
                + " mtime=" + sLoadedMtime);
        if (allow != null) {
            for (Map.Entry<String, Set<String>> e : allow.entrySet()) {
                pw.println("      " + e.getKey()
                        + (e.getValue() != null ? " exempt=" + e.getValue() : ""));
            }
        }
        pw.println("      <sweep> exemptSuffixes=" + String.join(",", BUILTIN_EXEMPT_SUFFIX));
    }
}
