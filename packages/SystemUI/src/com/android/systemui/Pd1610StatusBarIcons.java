/*
 * pd1610: 状态栏电池 / WiFi 图标样式开关。
 *
 * 存储：Settings.System 的 pd1610_sb_icon_color
 *       0 = 经典（默认）—— 上个版本的实心平涂绿电池 + 三星白扇形 WiFi
 *       1 = 三星绿     —— 从 NOTE3/S5 包里提取重绘的描边电池 + 绿色 WiFi
 *       2 = 三星白     —— 同上，白色
 * 兼容：adb 直接写 persist.sys.pd1610.sb_icon_color 也认（设置项没动过时用它）。
 *
 * 改完由 SystemUIApplication 里的 ContentObserver 让 SystemUI 自杀重启。
 * 图标本体：
 *   经典 电池 pd1610_s5_battery(_charging)      WiFi pd1610_s5_wifi_signal(_noinout)（吃状态栏染色）
 *   绿/白 电池 pd1610_sb_battery_green/white    WiFi pd1610_sb_wifi_green/white（不吃染色）
 */
package com.android.systemui;

import android.content.Context;
import android.os.SystemProperties;
import android.provider.Settings;

public final class Pd1610StatusBarIcons {
    /** 上个版本的经典平涂样式 */
    public static final int CLASSIC = 0;
    /** 三星 S5 提取重绘 · 绿 */
    public static final int GREEN = 1;
    /** 三星 S5 提取重绘 · 白 */
    public static final int WHITE = 2;
    /** 无描边平涂 · 全绿 */
    public static final int FLAT_GREEN = 3;
    /** 无描边平涂 · 全白 */
    public static final int FLAT_WHITE = 4;

    /** Settings.System 键名（LineageParts 的开关写这里） */
    public static final String SETTING_KEY = "pd1610_sb_icon_color";
    /** adb 兜底用的属性 */
    private static final String PROP = "persist.sys.pd1610.sb_icon_color";

    private Pd1610StatusBarIcons() {}

    public static int get(Context context) {
        int v = -1;
        if (context != null) {
            v = Settings.System.getInt(context.getContentResolver(), SETTING_KEY, -1);
        }
        if (v < 0) {
            v = SystemProperties.getInt(PROP, CLASSIC);
        }
        if (v < CLASSIC || v > FLAT_WHITE) {
            v = CLASSIC;
        }
        return v;
    }

    public static boolean isClassic(Context context) {
        return get(context) == CLASSIC;
    }

    public static boolean isWhite(Context context) {
        final int v = get(context);
        return v == WHITE || v == FLAT_WHITE;
    }
}
