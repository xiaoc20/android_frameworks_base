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
    // pd1610 v72: 我们写进去的那个颜色；restore 时用它判断「这层色还是不是我们的」
    private static final int TAG_S5_COLOR = 0x7f0f1628;
    // pd1610 v72: 我们挂过长按监听的图标（还原时要摘掉）
    private static final int TAG_S5_ICON = 0x7f0f1629;

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
        // 先把上一次套的样式全部还原，再决定这次要不要套
        // pd1610 v61: restore 必须排在 QsStyle 闸门【之前】—— 否则从 S5 切回原生时
        // 这里直接 return，之前刷的颜色就永远留着了（实测切 qs=1 后锁屏仍是 S5 配色）。
        restore(root);
        if (QsStyle.get(context) != QsStyle.S5) {
            return;
        }
        if (!isShade()) {
            // 锁屏 / 锁屏下拉：保持原生
            return;
        }
        // pd1610 v74: MediaStyle（媒体通知）整条保持原生 —— 它的模板结构和普通通知不同，
        // 拆行头/搬图标会把整块布局顶歪（实测 s1.png：绿块从 x≈275 一路吃到屏幕右缘）。
        // 媒体卡自带底色，原生黑字在它上面是对的，刷白反而更花 ⇒ 颜色也不碰。
        if (root.findViewById(com.android.internal.R.id.media_actions) != null) {
            return;
        }
        recolor(root);
        // pd1610 v64: 子行也收，但只收 header、不搬 icon/time —— 见 compactTemplate()
        compact(root, isChildRow(root));
    }

    /**
     * pd1610 v58: 这一坨视图是不是「分组里的子行」的内容。
     *
     * 子行不能 compact：AOSP 的 NotificationHeaderUtil 处处假定行头里还有
     * R.id.icon / R.id.time（sanitizeHeader() 里直接 `time.setVisibility(...)`、
     * mGreyApplicator 里 `icon.getDrawable().mutate()`）。compact 会把这两个
     * 从行头搬进标题行，于是分组里【增删一条通知】就会 NPE 打死整个 SystemUI
     * （实测两次：NotificationHeaderUtil.java:79 与 :218）。
     * 子行只靠 recolor 就够看清了，行高保持原生。
     */
    /** pd1610 v72: 从内容视图往上找所属的 ExpandableNotificationRow。 */
    private static ExpandableNotificationRow findRow(View v) {
        View p = v;
        while (p != null) {
            if (p instanceof ExpandableNotificationRow) {
                return (ExpandableNotificationRow) p;
            }
            final android.view.ViewParent parent = p.getParent();
            p = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    private static boolean isChildRow(View v) {
        View p = v;
        while (p != null) {
            if (p instanceof ExpandableNotificationRow) {
                return ((ExpandableNotificationRow) p).isChildInGroup();
            }
            final android.view.ViewParent parent = p.getParent();
            p = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    /**
     * pd1610 v53: 分组头（「应用名 + 展开箭头」那一条）是
     * NotificationChildrenContainer 自己 RemoteViews.apply 到容器上的，
     * 不在通知的 content view 里 ⇒ apply() 永远走不到它，一直是原生黑字，
     * 在深色控制中心上几乎看不见。
     * 这里只刷色，不做 compact（S5 的分组头本来就是这样一条）。
     */
    public static void applyHeader(View header) {
        if (header == null || header.getContext() == null) {
            return;
        }
        // pd1610 v61: 同 apply() —— 必须先还原，再判风格
        restore(header);
        if (QsStyle.get(header.getContext()) != QsStyle.S5) {
            return;
        }
        if (!isShade()) {
            return;
        }
        recolor(header);
    }

    private static boolean isShade() {
        try {
            // pd1610 v65: 锁屏下拉是 SHADE_LOCKED，不是 SHADE。原来只认 SHADE
            // ⇒ 锁屏下拉时走 restore 留原生黑字，而面板底是 S5 的 #001c27
            // ⇒ 黑字压深底（用户 2026-10-18 报的 bug）。SHADE_LOCKED 同样是「控制中心」。
            final int state = Dependency.get(StatusBarStateController.class).getState();
            return state == StatusBarState.SHADE || state == StatusBarState.SHADE_LOCKED;
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

    /** 子树里还有没有「看得见、带字」的 TextView。 */
    private static boolean hasTextDeep(View v) {
        if (v.getVisibility() != View.VISIBLE) {
            return false;
        }
        if (hasText(v)) {
            return true;
        }
        if (v instanceof ViewGroup) {
            final ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (hasTextDeep(g.getChildAt(i))) {
                    return true;
                }
            }
        }
        return false;
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
        if (v.getTag(TAG_S5_ICON) != null) {
            v.setOnLongClickListener(null);
            v.setTag(TAG_S5_ICON, null);
        }
        if (v instanceof TextView && v.getTag(TAG_ORIG_COLOR) instanceof Integer) {
            final TextView tv = (TextView) v;
            // pd1610 v72: ⚠ 只在「这层色还是我们写的那层」时才还原。
            // 系统重新绑定过（锁屏/切换状态）就会把这几个 view 刷成原生色，
            // 此时硬写 TAG_ORIG_COLOR 会把刚刷好的原生灰/蓝踩回上一次存下的黑
            // —— 实测就是锁屏通知变纯黑那一条（k1 vs n2 像素对比）。
            final Object s5 = v.getTag(TAG_S5_COLOR);
            if (!(s5 instanceof Integer) || tv.getCurrentTextColor() == (Integer) s5) {
                tv.setTextColor((Integer) v.getTag(TAG_ORIG_COLOR));
                final Object tf = v.getTag(TAG_ORIG_TF);
                tv.setTypeface(tf instanceof Typeface ? (Typeface) tf : null);
            }
            v.setTag(TAG_ORIG_COLOR, null);
            v.setTag(TAG_ORIG_TF, null);
            v.setTag(TAG_S5_COLOR, null);
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
            } else if (id == com.android.systemui.R.id.notification_title) {
                // pd1610 v63: 折叠分组 / 悬浮通知的单行视图（HybridNotificationView）
                color = C_TITLE;
                bold = true;
            } else if (id == com.android.internal.R.id.text
                    || id == com.android.internal.R.id.text_line_1
                    || id == com.android.systemui.R.id.notification_text) {
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
                tv.setTag(TAG_S5_COLOR, color);
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

    private static void compact(View v, boolean childRow) {
        if (v.getId() == com.android.internal.R.id.notification_header
                && v.getParent() instanceof View) {
            compactTemplate(v, (View) v.getParent(), childRow);
        }
        if (v instanceof ViewGroup) {
            final ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                compact(g.getChildAt(i), childRow);
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

    private static void compactTemplate(View header, View scope, boolean childRow) {
        if (childRow) {
            // pd1610 v64: 分组子行只把「应用名 + 时间」那一行 header 收掉，正文列留白同样
            // 收到 8dp；行高 420 -> ~300 px。
            // ⛔ 绝不搬 R.id.icon / R.id.time：AOSP NotificationHeaderUtil 默认它们还在
            //    header 里（mGreyApplicator 的 icon.getDrawable()、sanitizeHeader 的
            //    time.setVisibility）—— 一搬，展开分组/增删子通知/展开态清除就 NPE
            //    打死 SystemUI（v58 前实测两次）。
            if (header.getVisibility() != View.GONE) {
                saveVisibility(header);
                header.setVisibility(View.GONE);
            }
            tightenMainColumn(scope);
            return;
        }
        final View line1 = scope.findViewById(com.android.internal.R.id.line1);
        final View title = scope.findViewById(com.android.internal.R.id.title);
        final View text = scope.findViewById(com.android.internal.R.id.text);
        final View mainColumn =
                scope.findViewById(com.android.internal.R.id.notification_main_column);

        // pd1610 v52: 会话(MessagingStyle)/媒体等非标准模板里没有 R.id.title / R.id.text
        // 这两个坑位，之前会一路走到下面「正文列整列 GONE」的分支 ⇒ 整条通知内容被藏掉
        // （实测会话通知只剩一行应用名、行高 264）。标准模板才有这两个坑位，别的一律不碰。
        if (title == null || text == null) {
            return;
        }

        final boolean hasTitle = hasText(title);
        final boolean hasBody = hasText(text);

        if (!hasTitle && !hasBody) {
            // 只有应用名/正文挤在 header 一行的通知（「正在通过 USB 为此设备充电」之类）：
            // 正文列完全空着，却还占 46dp+20dp 的留白 ⇒ 整列收掉，只留 header 一行。
            // v52: 再加一道保险 —— 正文列里只要还有带字的 TextView 就不收。
            if (mainColumn != null && mainColumn.getVisibility() != View.GONE
                    && !hasTextDeep(mainColumn)) {
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
                // pd1610 v72: 长按图标 = 展开/收起这条通知。
                // S5 行头（含原生那个展开箭头）是 GONE 的 ⇒ 用户没有任何入口把一条
                // 通知展开，「浏览过就自动折叠」这条功能根本触发不到（用户：我没感觉到）。
                // 用长按而不是单击：单击仍然是「打开应用」的语义。
                final ExpandableNotificationRow enr = findRow(row);
                if (enr != null) {
                    icon.setTag(TAG_S5_ICON, Boolean.TRUE);
                    icon.setOnLongClickListener(new View.OnLongClickListener() {
                        @Override
                        public boolean onLongClick(View v) {
                            enr.setUserExpanded(!enr.isExpanded());
                            return true;
                        }
                    });
                }
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

        tightenMainColumn(scope);
    }

    /** 原生给「应用名行 + 标题 + 正文」留的 46dp/20dp 上下白，S5 用不着。 */
    private static void tightenMainColumn(View scope) {
        final View mainColumn =
                scope.findViewById(com.android.internal.R.id.notification_main_column);
        if (mainColumn != null) {
            final ViewGroup.LayoutParams lp = mainColumn.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                final ViewGroup.MarginLayoutParams cur = (ViewGroup.MarginLayoutParams) lp;
                // pd1610 v75: 8dp/8dp 太挤（实测整行只有 222 px = 55.5 dp，标题正文贴边）。
                // 用户原话「太挤了，再留点位置」。参考机 S5 是一条一空 ⇒
                // 上 20dp、下 28dp（下边多出来的就是行与行之间那个空），整行 350 px。
                final int top = dp(scope, 20);
                final int bottom = dp(scope, 28);
                if (cur.topMargin != top || cur.bottomMargin != bottom) {
                    saveLayoutParams(mainColumn);
                    final ViewGroup.MarginLayoutParams mlp =
                            (ViewGroup.MarginLayoutParams) copyLp(cur);
                    mlp.topMargin = top;
                    mlp.bottomMargin = bottom;
                    mainColumn.setLayoutParams(mlp);
                }
            }
        }
    }
}
