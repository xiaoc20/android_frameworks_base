/*
 * pd1610: QS 外观风格开关。
 *
 * 存储：Settings.System 的 pd1610_qs_style（设置 > 状态栏 > 控制中心样式）
 *       0 = 原生 (默认)
 *       1 = TouchWiz 4.4 风格（5 列、圆形图标、深色面板）
 *       2 = MIUI 10 风格（4 列、圆角方形图标、亮度条在底部）
 * 兼容：adb 直接写 persist.sys.pd1610.qs_style 也认（设置项没动过时用它）。
 *
 * 改完由 SystemUIApplication 里的 ContentObserver 自杀重启，布局按新风格重建。
 */
package com.android.systemui.qs;

import android.content.Context;
import android.os.SystemProperties;
import android.provider.Settings;

import com.android.systemui.R;

public final class QsStyle {
    public static final int NATIVE = 0;
    public static final int TOUCHWIZ = 1;
    public static final int MIUI = 2;
    /** 三星 Galaxy S5 (2014 TouchWiz) */
    public static final int S5 = 3;

    /** Settings.System 键名（LineageParts 的开关写这里） */
    public static final String SETTING_KEY = "pd1610_qs_style";
    /** adb 兜底用的属性 */
    private static final String PROP = "persist.sys.pd1610.qs_style";

    // 100x100 viewport（AdaptiveIconDrawable.MASK_SIZE）
    private static final String CIRCLE_MASK =
            "M50,0 C77.61,0 100,22.39 100,50 C100,77.61 77.61,100 50,100"
            + " C22.39,100 0,77.61 0,50 C0,22.39 22.39,0 50,0 Z";
    private static final String ROUNDED_RECT_MASK =
            "M28,0 L72,0 C87.46,0 100,12.54 100,28 L100,72 C100,87.46 87.46,100 72,100"
            + " L28,100 C12.54,100 0,87.46 0,72 L0,28 C0,12.54 12.54,0 28,0 Z";

    private QsStyle() {}

    // pd1610 v128: 「当前处于 S5 控制中心（解锁态 + S5 UI）」的进程级缓存。
    // 通知行在 inflate 线程上跑，那里 Dependency.get(StatusBarStateController)
    // 读到的是错的（实测 KEYGUARD），所以改由主线程算好、这里只读。
    private static volatile boolean sS5ShadeActive;

    public static void setS5ShadeActive(boolean active) {
        sS5ShadeActive = active;
    }

    public static boolean isS5ShadeActive() {
        return sS5ShadeActive;
    }

    public static int get(Context context) {
        int v = -1;
        if (context != null) {
            v = Settings.System.getInt(context.getContentResolver(), SETTING_KEY, -1);
        }
        if (v < 0) {
            // 设置项还没写过 ⇒ 兼容老办法（adb setprop）
            v = SystemProperties.getInt(PROP, NATIVE);
        }
        // pd1610 v117: 只剩「原生 / 三星 S5」两档；TouchWiz(1)、MIUI(2) 已下线
        // （设置里也列不出来了），旧值一律折回原生，别再把 UI 带进那条死路。
        return (v == S5) ? S5 : NATIVE;
    }

    public static boolean isTouchWiz(Context context) {
        return get(context) == TOUCHWIZ;
    }

    public static boolean isMiui(Context context) {
        return get(context) == MIUI;
    }

    /** QS 磁贴图标底色形状。原生沿用系统 config_icon_mask。 */
    public static String getIconMaskPath(Context context, int style) {
        if (style == TOUCHWIZ || style == S5) return CIRCLE_MASK;
        if (style == MIUI) return ROUNDED_RECT_MASK;
        return context.getResources().getString(
                com.android.internal.R.string.config_icon_mask);
    }

    /** 展开后的磁贴列数。 */
    public static int getColumns(Context context) {
        final int style = get(context);
        if (style == TOUCHWIZ || style == S5) return 5;
        if (style == MIUI) return 4;
        return Math.max(1,
                context.getResources().getInteger(R.integer.quick_settings_num_columns));
    }
}
