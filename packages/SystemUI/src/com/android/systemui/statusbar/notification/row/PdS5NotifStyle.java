/*
 * pd1610 S5 —— 通知行「深色控制中心」外观（只作用在解锁后的下拉里）
 *
 * 1) 【黑字】LOS 17.1 的通知模板用 TextAppearance.DeviceDefault.Notification.*，
 *    本树那套是给浅色通知卡用的黑字；我们把通知区刷成 #001c27 深色后整片字都
 *    看不见。Android 12 才有 NotificationColorUtil，17.1 没有 ⇒ 这里在
 *    RemoteViews 应用完之后遍历视图树，按 view id 刷成 S5 配色。
 * 2) 【行太大】原生模板 = 「应用名行(50dp) + 标题 + 正文」，正文列上下还留着
 *    46dp + 20dp 的大白，整行被撑到 106dp。S5 的行是「图标 + 标题 + 正文 + 时间」
 *    ~50dp ⇒ header 里的图标/时间搬进标题行、header GONE、留白收到 8dp。
 * 3) 【锁屏要原生】用户要求锁屏通知保持原生样式 ⇒ 只在 StatusBarState.SHADE
 *    时套；锁屏/锁屏下拉时把改动过的每一处按 tag 里存的原始值还原。
 *
 * 所有改动都先存原值（颜色/字体/可见性/LayoutParams/搬走的父子关系），
 * 状态一变就能逐字节还原，不需要重新 inflate。
 */
package com.android.systemui.statusbar.notification.row;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.Dependency;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QsStyle;
import com.android.systemui.statusbar.StatusBarState;

public final class PdS5NotifStyle {

    private static final int TAG_ORIG_COLOR = 0x7f0f1620;
    private static final int TAG_ORIG_TF = 0x7f0f1621;
    private static final int TAG_ORIG_VIS = 0x7f0f1622;
    private static final int TAG_ORIG_LP = 0x7f0f1623;
    private static final int TAG_MOVED_PARENT = 0x7f0f1624;
    private static final int TAG_MOVED_INDEX = 0x7f0f1625;
    private static final int TAG_MOVED_LP = 0x7f0f1626;
    private static final int TAG_COMPACTED = 0x7f0f1627;

    private static final int C_TITLE = 0xffffffff;    // 标题：纯白
    private static final int C_BODY = 0xff82afd0;     // 正文：钢蓝（原机截图取样）
    private static final int C_INFO = 0xff9eb6c0;     // 应用名 / 时间
    private static final int C_FALLBACK = 0xffcfe0ea; // 其它深色字兜底

    private PdS5NotifStyle() {
    }

    public static void apply(View root) {
        if (root == null || root.getContext() == null) {
            return;
        }
        final Context context = root.getContext();
        if (QsStyle.get(context) != QsStyle.S5) {
            return;
        }
        // 先把上一次套的样式全部还原，再决定这次要不要套
        restore(root);
        if (!isShade()) {
            // 锁屏 / 锁屏下拉：保持原生
            return;
        }
        recolor(root);
        compact(root);
    }

    private static boolean isShade() {
        try {
            return Dependency.get(StatusBarStateController.class).getState()
                    == StatusBarState.SHADE;
        } catch (Throwable t) {
            return true;
        }
    }

    private static int dp(View v, int value) {
        return (int) (value * v.getResources().getDisplayMetrics().density);
    }

    private static boolean isDark(int color) {
        double luma = 0.2126 * Color.red(color) + 0.7152 * Color.green(color)
                + 0.0722 * Color.blue(color);
        return luma < 96;
    }

    private static boolean hasText(View v) {
        return v instanceof TextView && ((TextView) v).getText().length() > 0;
    }

    // ---------------------------------------------------------------- 还原

    private static void restore(View v) {
        if (v.getTag(TAG_MOVED_PARENT) instanceof ViewGroup) {
            final ViewGroup parent = (ViewGroup) v.getTag(TAG_MOVED_PARENT);
            final Object index = v.getTag(TAG_MOVED_INDEX);
            final Object lp = v.getTag(TAG_MOVED_LP);
            if (v.getParent() instanceof ViewGroup) {
                ((ViewGroup) v.getParent()).removeView(v);
            }
            final int idx = index instanceof Integer ? (Integer) index : parent.getChildCount();
            if (lp instanceof ViewGroup.LayoutParams) {
                parent.addView(v, Math.min(idx, parent.getChildCount()),
                        (ViewGroup.LayoutParams) lp);
            } else {
                parent.addView(v);
            }
            v.setTag(TAG_MOVED_PARENT, null);
            v.setTag(TAG_MOVED_INDEX, null);
            v.setTag(TAG_MOVED_LP, null);
        }
        // 搬回去之后要允许下一次 compact() 再搬一次
        v.setTag(TAG_COMPACTED, null);
        if (v.getTag(TAG_ORIG_LP) instanceof ViewGroup.LayoutParams) {
            v.setLayoutParams((ViewGroup.LayoutParams) v.getTag(TAG_ORIG_LP));
            v.setTag(TAG_ORIG_LP, null);
        }
        if (v.getTag(TAG_ORIG_VIS) instanceof Integer) {
            v.setVisibility((Integer) v.getTag(TAG_ORIG_VIS));
            v.setTag(TAG_ORIG_VIS, null);
        }
        if (v instanceof TextView && v.getTag(TAG_ORIG_COLOR) instanceof Integer) {
            final TextView tv = (TextView) v;
            tv.setTextColor((Integer) v.getTag(TAG_ORIG_COLOR));
            final Object tf = v.getTag(TAG_ORIG_TF);
            tv.setTypeface(tf instanceof Typeface ? (Typeface) tf : null);
            v.setTag(TAG_ORIG_COLOR, null);
            v.setTag(TAG_ORIG_TF, null);
        }
        if (v instanceof ViewGroup) {
            final ViewGroup g = (ViewGroup) v;
            for (int i = g.getChildCount() - 1; i >= 0; i--) {
                restore(g.getChildAt(i));
            }
        }
    }

    // ---------------------------------------------------------------- 配色

    private static void recolor(View v) {
        if (v instanceof TextView) {
            final TextView tv = (TextView) v;
            final int id = tv.getId();
            int color = 0;
            boolean bold = false;
            if (id == com.android.internal.R.id.title) {
                color = C_TITLE;
                bold = true;
            } else if (id == com.android.internal.R.id.text
                    || id == com.android.internal.R.id.text_line_1) {
                color = C_BODY;
            } else if (id == com.android.internal.R.id.info
                    || id == com.android.internal.R.id.time
                    || id == com.android.internal.R.id.chronometer
                    || id == com.android.internal.R.id.app_name_text
                    || id == com.android.internal.R.id.header_text
                    || id == com.android.internal.R.id.header_text_secondary) {
                color = C_INFO;
            } else if (isDark(tv.getCurrentTextColor())) {
                color = C_FALLBACK;
            }
            if (color != 0) {
                if (tv.getTag(TAG_ORIG_COLOR) == null) {
                    tv.setTag(TAG_ORIG_COLOR, tv.getCurrentTextColor());
                    tv.setTag(TAG_ORIG_TF, tv.getTypeface());
                }
                tv.setTextColor(color);
                if (bold) {
                    tv.setTypeface(Typeface.create(tv.getTypeface(), Typeface.BOLD));
                }
            }
        }
        if (v instanceof ViewGroup) {
            final ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                recolor(g.getChildAt(i));
            }
        }
    }

    // ---------------------------------------------------------------- 排版

    private static void compact(View v) {
        if (v.getId() == com.android.internal.R.id.notification_header
                && v.getParent() instanceof View) {
            compactTemplate(v, (View) v.getParent());
        }
        if (v instanceof ViewGroup) {
            final ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                compact(g.getChildAt(i));
            }
        }
    }

    private static void saveVisibility(View v) {
        if (v.getTag(TAG_ORIG_VIS) == null) {
            v.setTag(TAG_ORIG_VIS, v.getVisibility());
        }
    }

    /**
     * 复制一份「同类」的 LayoutParams。
     * ⚠ 不能一律 new MarginLayoutParams：父容器是 LinearLayout 时它做
     *   `(LinearLayout.LayoutParams) child.getLayoutParams()` 会 ClassCastException
     *   （整台 SystemUI 崩在 measureVertical）。
     */
    private static ViewGroup.LayoutParams copyLp(ViewGroup.LayoutParams lp) {
        if (lp instanceof LinearLayout.LayoutParams) {
            return new LinearLayout.LayoutParams((LinearLayout.LayoutParams) lp);
        }
        if (lp instanceof FrameLayout.LayoutParams) {
            return new FrameLayout.LayoutParams((FrameLayout.LayoutParams) lp);
        }
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            return new ViewGroup.MarginLayoutParams((ViewGroup.MarginLayoutParams) lp);
        }
        return lp == null ? null : new ViewGroup.LayoutParams(lp);
    }

    private static void saveLayoutParams(View v) {
        if (v.getTag(TAG_ORIG_LP) == null && v.getLayoutParams() != null) {
            v.setTag(TAG_ORIG_LP, v.getLayoutParams());
        }
    }

    private static void compactTemplate(View header, View scope) {
        final View line1 = scope.findViewById(com.android.internal.R.id.line1);
        final View title = scope.findViewById(com.android.internal.R.id.title);
        final View text = scope.findViewById(com.android.internal.R.id.text);
        final View mainColumn =
                scope.findViewById(com.android.internal.R.id.notification_main_column);

        final boolean hasTitle = hasText(title);
        final boolean hasBody = hasText(text);

        if (!hasTitle && !hasBody) {
            // 只有应用名/正文挤在 header 一行的通知（「正在通过 USB 为此设备充电」之类）：
            // 正文列完全空着，却还占 46dp+20dp 的留白 ⇒ 整列收掉，只留 header 一行。
            if (mainColumn != null && mainColumn.getVisibility() != View.GONE) {
                saveVisibility(mainColumn);
                mainColumn.setVisibility(View.GONE);
            }
            return;
        }
        if (line1 == null || title == null || !(line1 instanceof LinearLayout)) {
            // MediaStyle 之类没有 header/line1 的模板：保持原样，只刷颜色
            return;
        }
        if (header.getVisibility() != View.GONE) {
            saveVisibility(header);
            header.setVisibility(View.GONE);
        }

        final ViewGroup row = (ViewGroup) line1;
        if (line1.getTag(TAG_COMPACTED) == null) {
            line1.setTag(TAG_COMPACTED, Boolean.TRUE);
            final int iconSize = dp(scope, 20);

            final View icon = header.findViewById(com.android.internal.R.id.icon);
            if (icon != null && icon.getParent() instanceof ViewGroup) {
                final ViewGroup parent = (ViewGroup) icon.getParent();
                icon.setTag(TAG_MOVED_PARENT, parent);
                icon.setTag(TAG_MOVED_INDEX, parent.indexOfChild(icon));
                icon.setTag(TAG_MOVED_LP, icon.getLayoutParams());
                parent.removeView(icon);
                final LinearLayout.LayoutParams lp =
                        new LinearLayout.LayoutParams(iconSize, iconSize);
                lp.gravity = Gravity.CENTER_VERTICAL;
                lp.setMarginEnd(dp(scope, 10));
                row.addView(icon, 0, lp);
            }

            final View time = header.findViewById(com.android.internal.R.id.time);
            if (time != null && time.getParent() instanceof ViewGroup) {
                final ViewGroup parent = (ViewGroup) time.getParent();
                time.setTag(TAG_MOVED_PARENT, parent);
                time.setTag(TAG_MOVED_INDEX, parent.indexOfChild(time));
                time.setTag(TAG_MOVED_LP, time.getLayoutParams());
                parent.removeView(time);
                final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.gravity = Gravity.CENTER_VERTICAL;
                lp.setMarginStart(dp(scope, 8));
                row.addView(time, lp);
            }

            // 标题吃掉剩余宽度，长标题被省略号截断
            final ViewGroup.LayoutParams tlp = title.getLayoutParams();
            if (tlp instanceof LinearLayout.LayoutParams) {
                saveLayoutParams(title);
                final LinearLayout.LayoutParams nlp =
                        new LinearLayout.LayoutParams((LinearLayout.LayoutParams) tlp);
                nlp.width = 0;
                nlp.weight = 1;
                title.setLayoutParams(nlp);
            }

            // 正文跟标题左对齐（缩进一个图标宽）
            if (text != null) {
                final ViewGroup.LayoutParams lp = text.getLayoutParams();
                if (lp instanceof ViewGroup.MarginLayoutParams) {
                    saveLayoutParams(text);
                    final ViewGroup.MarginLayoutParams mlp =
                            (ViewGroup.MarginLayoutParams) copyLp(lp);
                    mlp.setMarginStart(iconSize + dp(scope, 10));
                    text.setLayoutParams(mlp);
                }
            }
        }

        // 原生给「应用名行 + 标题 + 正文」留的 46dp/20dp 上下白，S5 用不着
        if (mainColumn != null) {
            final ViewGroup.LayoutParams lp = mainColumn.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                final ViewGroup.MarginLayoutParams cur = (ViewGroup.MarginLayoutParams) lp;
                final int pad = dp(scope, 8);
                if (cur.topMargin != pad || cur.bottomMargin != pad) {
                    saveLayoutParams(mainColumn);
                    final ViewGroup.MarginLayoutParams mlp =
                            (ViewGroup.MarginLayoutParams) copyLp(cur);
                    mlp.topMargin = pad;
                    mlp.bottomMargin = pad;
                    mainColumn.setLayoutParams(mlp);
                }
            }
        }
    }
}
