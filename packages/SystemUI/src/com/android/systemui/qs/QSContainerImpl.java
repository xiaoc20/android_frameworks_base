/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.qs;

import static android.app.StatusBarManager.DISABLE2_QUICK_SETTINGS;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.Pd1610Sui;
import com.android.systemui.R;
import com.android.systemui.qs.QsStyle;
import com.android.systemui.qs.customize.QSCustomizer;

/**
 * Wrapper view with background which contains {@link QSPanel} and {@link BaseStatusBarHeader}
 */
public class QSContainerImpl extends FrameLayout {

    private final Point mSizePoint = new Point();

    private int mHeightOverride = -1;
    private QSPanel mQSPanel;
    private View mQSDetail;
    private QuickStatusBarHeader mHeader;
    private float mQsExpansion;
    private QSCustomizer mQSCustomizer;
    private View mQSFooter;

    private View mBackground;
    private View mBackgroundGradient;
    private View mStatusBarBackground;

    private int mSideMargins;
    private boolean mQsDisabled;

    private ImageView mBackgroundImage;
    private Drawable mStatusBarBackgroundDrawable;

    private boolean mLandscape;
    private boolean mHeaderImageEnabled;

    // pd1610 v122: SUI 设置 —— 控制中心顶图
    private ImageView mSuiQsImage;

    // pd1610: 三星 S5 的「通知」分隔条（QS 面板最下面那一条青色标题）
    private View mS5NotifBar;
    private TextView mS5ClearButton;
    private int mS5NotifBarHeight;

    public QSContainerImpl(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mQSPanel = findViewById(R.id.quick_settings_panel);
        mQSDetail = findViewById(R.id.qs_detail);
        mHeader = findViewById(R.id.header);
        mQSCustomizer = findViewById(R.id.qs_customize);
        mQSFooter = findViewById(R.id.qs_footer);
        mBackground = findViewById(R.id.quick_settings_background);
        mStatusBarBackground = findViewById(R.id.quick_settings_status_bar_background);
        mBackgroundGradient = findViewById(R.id.quick_settings_gradient_view);
        // pd1610: S5 的面板是整宽的，原机没有左右留白
        mSideMargins = QsStyle.get(getContext()) == QsStyle.S5 ? 0
                : getResources().getDimensionPixelSize(R.dimen.notification_side_paddings);
        mBackgroundImage = findViewById(R.id.qs_header_image_view);
        mBackgroundImage.setClipToOutline(true);
        mLandscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;

        mStatusBarBackgroundDrawable = mStatusBarBackground.getBackground();

        // pd1610: TouchWiz 4.4 是深色面板 + 白色文字（标签在 QSTileView 里处理）
        if (QsStyle.get(getContext()) == QsStyle.S5) {
            mBackground.setBackgroundColor(getContext().getColor(R.color.pd1610_qs_panel_bg));
        } else if (QsStyle.get(getContext()) == QsStyle.TOUCHWIZ) {
            mBackground.setBackgroundColor(0xFF000000);
        }

        setupS5NotifBar();

        // pd1610 v122: SUI 设置的外观项（顶图 / 亮度条底色）
        applySui();

        updateResources();

        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setMargins();
    }

    /**
     * pd1610: 三星 S5 控制中心在「亮度条」和通知列表之间有一条青色「通知」标题条。
     * 它挂在容器最底下（FrameLayout 的 BOTTOM 重力），容器高度把它算进去，
     * 通知列表的下移量来自 QSContainer 的测量高度，所以通知会自动排在它下面。
     * 颜色/高度/字号全部走资源，主题 overlay 换名字就能改。
     */
    private void setupS5NotifBar() {
        if (QsStyle.get(getContext()) != QsStyle.S5) return;
        final Resources res = getResources();
        mS5NotifBarHeight = res.getDimensionPixelSize(R.dimen.pd1610_qs_s5_notif_bar_height);
        // pd1610: S5 通知条右侧「清除」（照 参考逻辑1.png）
        final int barTextSize = res.getDimensionPixelSize(
                R.dimen.pd1610_qs_s5_notif_bar_text_size);
        final int barPad = res.getDimensionPixelSize(
                R.dimen.pd1610_qs_s5_notif_bar_padding_start);
        LinearLayout bar = new LinearLayout(getContext());
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(getContext());
        label.setText("通知");
        label.setTextColor(res.getColor(R.color.pd1610_qs_notif_bar_text));
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, barTextSize);
        label.setIncludeFontPadding(false);
        label.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        labelLp.leftMargin = barPad;
        bar.addView(label, labelLp);
        final TextView clear = new TextView(getContext());
        mS5ClearButton = clear;
        clear.setText("清除");
        clear.setTextColor(res.getColor(R.color.pd1610_qs_notif_bar_text));
        clear.setTextSize(TypedValue.COMPLEX_UNIT_PX, barTextSize);
        clear.setIncludeFontPadding(false);
        clear.setGravity(Gravity.CENTER);
        clear.setPadding(barPad, 0, barPad, 0);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    // IStatusBarService.onClearAllNotifications() 就是通知堆栈
                    // 底部那个「全部清除」走的同一个接口
                    com.android.internal.statusbar.IStatusBarService bar =
                            com.android.internal.statusbar.IStatusBarService.Stub.asInterface(
                                    android.os.ServiceManager.getService("statusbar"));
                    bar.onClearAllNotifications(android.os.UserHandle.myUserId());
                } catch (Exception e) {
                    android.util.Log.w("pd1610qs", "clear all failed", e);
                }
            }
        });
        bar.addView(clear, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        bar.setBackgroundColor(res.getColor(R.color.pd1610_qs_notif_bar_bg));
        // quick_settings_background / quick_settings_panel 都是 elevation=4dp，
        // FrameLayout 按 Z 序绘制 ⇒ 必须比它们高，否则这条被背景整块盖住
        bar.setElevation(res.getDisplayMetrics().density * 6f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, mS5NotifBarHeight);
        lp.gravity = Gravity.BOTTOM;
        addView(bar, lp);
        mS5NotifBar = bar;
        bar.setId(R.id.pd1610_s5_notif_bar);
        mS5NotifBar.setVisibility(View.INVISIBLE);
    }

    /**
     * pd1610 v122: SUI 设置的外观项。
     * 顶图垫在 QS 容器最底下（索引 0），只铺到亮度条上沿（见 updateExpansion）；
     * 有图时把原来那两层青底让开。
     */
    private void applySui() {
        final Context ctx = getContext();
        if (QsStyle.get(ctx) != QsStyle.S5) {
            return;
        }
        registerSuiObservers();
        // 亮度条背景色
        final int brightBg = Pd1610Sui.getColor(ctx, Pd1610Sui.KEY_BRIGHT_BG);
        if (brightBg != 0) {
            View bv = mQSPanel.getBrightnessView();
            if (bv != null) {
                bv.setBackgroundColor(brightBg);
            }
        }
        // 顶图
        Drawable image = Pd1610Sui.loadImage(ctx,
                Pd1610Sui.getString(ctx, Pd1610Sui.KEY_QS_IMAGE));
        if (image == null) {
            return;
        }
        mSuiQsImage = new ImageView(ctx);
        mSuiQsImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mSuiQsImage.setImageDrawable(image);
        mSuiQsImage.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(mSuiQsImage, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        mBackground.setBackground(null);
    }

    private boolean mSuiObserverRegistered;

    /** pd1610 v122: 设置里改完图/颜色，控制中心即时跟着变，不用重启 SystemUI。 */
    private void registerSuiObservers() {
        if (mSuiObserverRegistered) {
            return;
        }
        mSuiObserverRegistered = true;
        final android.database.ContentObserver observer =
                new android.database.ContentObserver(
                        new android.os.Handler(android.os.Looper.getMainLooper())) {
                    @Override
                    public void onChange(boolean selfChange) {
                        rebuildSui();
                    }
                };
        try {
            android.content.ContentResolver cr = getContext().getContentResolver();
            cr.registerContentObserver(android.provider.Settings.System.getUriFor(
                    Pd1610Sui.KEY_QS_IMAGE), false, observer);
            cr.registerContentObserver(android.provider.Settings.System.getUriFor(
                    Pd1610Sui.KEY_BRIGHT_BG), false, observer);
        } catch (Throwable t) {
            // 注册失败只影响即时刷新
        }
    }

    private void rebuildSui() {
        if (mSuiQsImage != null) {
            removeView(mSuiQsImage);
            mSuiQsImage = null;
        }
        mBackground.setBackgroundColor(getContext().getColor(R.color.pd1610_qs_panel_bg));
        View bv = mQSPanel == null ? null : mQSPanel.getBrightnessView();
        if (bv != null) {
            bv.setBackground(null);
        }
        applySui();
        updateHeaderVisibility();
        updateExpansion();
        requestLayout();
    }

    private void updateS5NotifBarVisibility() {
        if (mS5NotifBar == null) return;
        // pd1610 v98: 网格态也显示「通知」条（v96 起通知区在网格态是保留的，
        // v87 那条「网格态不显示」随之作废）。
        final boolean show = !mQsDisabled && mQsExpansion > 0.99f
                && !mQSCustomizer.isCustomizing();
        mS5NotifBar.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        if (show && mS5ClearButton != null) {
            // 安卓 10 原生逻辑：只有存在「可清除」的通知时才给清除入口；
            // USB 这类系统/不可清通知不算（hasActiveClearableNotifications 用的
            // 就是底部那个清除按钮的判据）
            View stack = getRootView().findViewById(R.id.notification_stack_scroller);
            boolean clearable = stack
                    instanceof com.android.systemui.statusbar.notification.stack
                            .NotificationStackScrollLayout
                    && ((com.android.systemui.statusbar.notification.stack
                            .NotificationStackScrollLayout) stack)
                            .hasActiveClearableNotifications(
                                    com.android.systemui.statusbar.notification.stack
                                            .NotificationStackScrollLayout.ROWS_ALL);
            mS5ClearButton.setVisibility(clearable ? View.VISIBLE : View.INVISIBLE);
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        setBackgroundGradientVisibility(newConfig);
        mLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE;
        updateResources();
        mSizePoint.set(0, 0); // Will be retrieved on next measure pass.
    }

    @Override
    public boolean performClick() {
        // Want to receive clicks so missing QQS tiles doesn't cause collapse, but
        // don't want to do anything with them.
        return true;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // QSPanel will show as many rows as it can (up to TileLayout.MAX_ROWS) such that the
        // bottom and footer are inside the screen.
        Configuration config = getResources().getConfiguration();
        boolean navBelow = config.smallestScreenWidthDp >= 600
                || config.orientation != Configuration.ORIENTATION_LANDSCAPE;
        MarginLayoutParams layoutParams = (MarginLayoutParams) mQSPanel.getLayoutParams();

        // The footer is pinned to the bottom of QSPanel (same bottoms), therefore we don't need to
        // subtract its height. We do not care if the collapsed notifications fit in the screen.
        int maxQs = getDisplayHeight() - layoutParams.topMargin - layoutParams.bottomMargin
                - getPaddingBottom();
        if (navBelow) {
            maxQs -= getResources().getDimensionPixelSize(R.dimen.navigation_bar_height);
        }
        // pd1610: S5 的「通知」分隔条也算 QS 面板的一部分
        maxQs -= mS5NotifBarHeight;
        // Measure with EXACTLY. That way, PagedTileLayout will only use excess height and will be
        // measured last, after other views and padding is accounted for.
        mQSPanel.measure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxQs, MeasureSpec.EXACTLY));
        int width = mQSPanel.getMeasuredWidth();
        int height = layoutParams.topMargin + layoutParams.bottomMargin
                + mQSPanel.getMeasuredHeight() + getPaddingBottom() + mS5NotifBarHeight;
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        // QSCustomizer will always be the height of the screen, but do this after
        // other measuring to avoid changing the height of the QS.
        mQSCustomizer.measure(widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(getDisplayHeight(), MeasureSpec.EXACTLY));
    }


    @Override
    protected void measureChildWithMargins(View child, int parentWidthMeasureSpec, int widthUsed,
            int parentHeightMeasureSpec, int heightUsed) {
        // Do not measure QSPanel again when doing super.onMeasure.
        // This prevents the pages in PagedTileLayout to be remeasured with a different (incorrect)
        // size to the one used for determining the number of rows and then the number of pages.
        if (child != mQSPanel) {
            super.measureChildWithMargins(child, parentWidthMeasureSpec, widthUsed,
                    parentHeightMeasureSpec, heightUsed);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        updateExpansion();
    }

    public void disable(int state1, int state2, boolean animate) {
        final boolean disabled = (state2 & DISABLE2_QUICK_SETTINGS) != 0;
        if (disabled == mQsDisabled) return;
        mQsDisabled = disabled;
        updateS5NotifBarVisibility();
        setBackgroundGradientVisibility(getResources().getConfiguration());
        mBackground.setVisibility(mQsDisabled ? View.GONE : View.VISIBLE);
        updateHeaderVisibility();
    }

    private void updateResources() {
        mHeaderImageEnabled = mContext.getResources().getBoolean(R.bool.qs_custom_header);
        // pd1610: S5 头部是一整行大时钟（72dp），比原生的 48dp 顶栏高
        final boolean s5 = QsStyle.get(mContext) == QsStyle.S5;
        int topMargin = s5 ? mContext.getResources().getDimensionPixelSize(
                    R.dimen.pd1610_qs_s5_header_height)
                : mContext.getResources().getDimensionPixelSize(
                        com.android.internal.R.dimen.quick_qs_offset_height)
                        + (mHeaderImageEnabled ? mContext.getResources().getDimensionPixelSize(
                                R.dimen.qs_header_image_offset) : 0);

        LayoutParams layoutParams = (LayoutParams) mQSPanel.getLayoutParams();
        layoutParams.topMargin  = topMargin;
        // S5 没有 LOS 底部页脚（齿轮/编辑已经搬到头部），下边距也一起去掉
        if (s5) {
            layoutParams.bottomMargin = 0;
            mQSFooter.setVisibility(View.GONE);
        }
        mQSPanel.setLayoutParams(layoutParams);

        int statusBarSideMargin = mHeaderImageEnabled ? mContext.getResources().getDimensionPixelSize(
                R.dimen.qs_header_image_side_margin) : 0;

        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) mStatusBarBackground.getLayoutParams();
        lp.height = topMargin;
        lp.setMargins(statusBarSideMargin, 0, statusBarSideMargin, 0);
        mStatusBarBackground.setLayoutParams(lp);

        mQSPanel.setLayoutParams(layoutParams);

        updateHeaderVisibility();
    }

    /**
     * Overrides the height of this view (post-layout), so that the content is clipped to that
     * height and the background is set to that height.
     *
     * @param heightOverride the overridden height
     */
    public void setHeightOverride(int heightOverride) {
        mHeightOverride = heightOverride;
        updateExpansion();
    }

    public void updateExpansion() {
        int height = calculateContainerHeight();
        setBottom(getTop() + height);
        mQSDetail.setBottom(getTop() + height);
        // Pin QS Footer to the bottom of the panel.
        mQSFooter.setTranslationY(height - mQSFooter.getHeight());
        mBackground.setTop(mQSPanel.getTop());
        mBackground.setBottom(height);
        // pd1610 v122: SUI 顶图铺到「亮度条上沿」为止
        if (mSuiQsImage != null) {
            int bottom = mQSPanel.getTop();
            View bv = mQSPanel.getBrightnessView();
            if (bv != null && bv.getVisibility() == View.VISIBLE) {
                bottom += bv.getTop();
            }
            mSuiQsImage.setLeft(0);
            mSuiQsImage.setRight(getWidth());
            mSuiQsImage.setTop(0);
            mSuiQsImage.setBottom(Math.min(bottom, height));
        }
    }

    protected int calculateContainerHeight() {
        int heightOverride = mHeightOverride != -1 ? mHeightOverride : getMeasuredHeight();
        return mQSCustomizer.isCustomizing() ? mQSCustomizer.getHeight()
                : Math.round(mQsExpansion * (heightOverride - mHeader.getHeight()))
                + mHeader.getHeight();
    }

    private void setBackgroundGradientVisibility(Configuration newConfig) {
        if ((QsStyle.get(getContext()) == QsStyle.TOUCHWIZ || QsStyle.get(getContext()) == QsStyle.S5)) {
            // TouchWiz 没有渐变层，整块是纯深色
            mBackgroundGradient.setVisibility(View.INVISIBLE);
            mStatusBarBackground.setVisibility(View.VISIBLE);
            return;
        }
        if (newConfig.orientation == ORIENTATION_LANDSCAPE) {
            mBackgroundGradient.setVisibility(View.INVISIBLE);
            mStatusBarBackground.setVisibility(View.INVISIBLE);
        } else {
            mBackgroundGradient.setVisibility(mQsDisabled ? View.INVISIBLE : View.VISIBLE);
            mStatusBarBackground.setVisibility(View.VISIBLE);
        }
    }

    public void setExpansion(float expansion) {
        mQsExpansion = expansion;
        updateS5NotifBarVisibility();
        updateExpansion();
    }

    private void setMargins() {
        setMargins(mQSDetail);
        setMargins(mBackground);
        setMargins(mQSFooter);
        mQSPanel.setMargins(mSideMargins);
        mHeader.setMargins(mSideMargins);
    }

    private void setMargins(View view) {
        FrameLayout.LayoutParams lp = (LayoutParams) view.getLayoutParams();
        lp.rightMargin = mSideMargins;
        lp.leftMargin = mSideMargins;
    }

    private int getDisplayHeight() {
        if (mSizePoint.y == 0) {
            getDisplay().getRealSize(mSizePoint);
        }
        return mSizePoint.y;
    }

    private void updateHeaderVisibility() {
        boolean hideGradient = (QsStyle.get(getContext()) == QsStyle.TOUCHWIZ || QsStyle.get(getContext()) == QsStyle.S5)
                || mLandscape || mHeaderImageEnabled || mQsDisabled;
        // pd1610 v83: 横屏藏「状态栏底」是 AOSP 给 544dp 窄列用的；S5 横屏整宽，
        // 藏掉后顶部整条透明（用户：「顶部怎么是透明的」）⇒ S5 下横屏也保留。
        boolean hideStatusbar = mLandscape && !mHeaderImageEnabled
                && QsStyle.get(getContext()) != QsStyle.S5;

        mBackgroundImage.setVisibility(mHeaderImageEnabled ? View.VISIBLE : View.GONE);
        mBackgroundGradient.setVisibility(hideGradient ? View.INVISIBLE : View.VISIBLE);
        if (QsStyle.get(getContext()) == QsStyle.S5) {
            // pd1610 v122: 放了顶图就别再盖青底，否则顶部 39dp 那块还是纯色
            mStatusBarBackground.setBackgroundColor(mSuiQsImage != null
                    ? android.graphics.Color.TRANSPARENT
                    : getContext().getColor(R.color.pd1610_qs_panel_bg));
        } else if (QsStyle.get(getContext()) == QsStyle.TOUCHWIZ) {
            mStatusBarBackground.setBackgroundColor(0xFF000000);
        } else {
            mStatusBarBackground.setBackground(hideGradient ? null : mStatusBarBackgroundDrawable);
        }
        mStatusBarBackground.setVisibility(hideStatusbar ? View.INVISIBLE : View.VISIBLE);
    }
}
