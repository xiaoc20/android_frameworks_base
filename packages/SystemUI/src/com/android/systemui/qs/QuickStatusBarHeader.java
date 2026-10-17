/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs;

import static android.app.StatusBarManager.DISABLE2_QUICK_SETTINGS;

import static com.android.systemui.util.InjectionInflationController.VIEW_CONTEXT;

import android.annotation.ColorInt;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Rect;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.AlarmClock;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.service.notification.ZenModeConfig;
import android.text.format.DateUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Pair;
import android.util.TypedValue;
import android.util.StatsLog;
import android.view.ContextThemeWrapper;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.Space;
import android.widget.TextView;

import androidx.annotation.VisibleForTesting;

import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.settingslib.Utils;
import com.android.systemui.BatteryMeterView;
import com.android.systemui.Dependency;
import com.android.systemui.DualToneHandler;
import com.android.systemui.R;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.privacy.OngoingPrivacyChip;
import com.android.systemui.privacy.PrivacyDialogBuilder;
import com.android.systemui.privacy.PrivacyItem;
import com.android.systemui.privacy.PrivacyItemController;
import com.android.systemui.privacy.PrivacyItemControllerKt;
import com.android.systemui.qs.QSDetail.Callback;
import com.android.systemui.settings.BrightnessController;
import com.android.systemui.statusbar.phone.PhoneStatusBarView;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.phone.StatusBarIconController.TintedIconManager;
import com.android.systemui.statusbar.phone.StatusIconContainer;
import com.android.systemui.statusbar.policy.Clock;
import com.android.systemui.statusbar.policy.DateView;
import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.tuner.TunerService;

import lineageos.providers.LineageSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * View that contains the top-most bits of the screen (primarily the status bar with date, time, and
 * battery) and also contains the {@link QuickQSPanel} along with some of the panel's inner
 * contents.
 */
public class QuickStatusBarHeader extends RelativeLayout implements
        View.OnClickListener, NextAlarmController.NextAlarmChangeCallback,
        ZenModeController.Callback, TunerService.Tunable {
    private static final String TAG = "QuickStatusBarHeader";
    private static final boolean DEBUG = false;

    /** Delay for auto fading out the long press tooltip after it's fully visible (in ms). */
    private static final long AUTO_FADE_OUT_DELAY_MS = DateUtils.SECOND_IN_MILLIS * 6;
    private static final int FADE_ANIMATION_DURATION_MS = 300;
    private static final int TOOLTIP_NOT_YET_SHOWN_COUNT = 0;
    public static final int MAX_TOOLTIP_SHOWN_COUNT = 2;

    private static final String QS_SHOW_AUTO_BRIGHTNESS =
            "lineagesecure:" + LineageSettings.Secure.QS_SHOW_AUTO_BRIGHTNESS;
    private static final String QS_SHOW_BRIGHTNESS_SLIDER =
            "lineagesecure:" + LineageSettings.Secure.QS_SHOW_BRIGHTNESS_SLIDER;

    private final Handler mHandler = new Handler();
    private final NextAlarmController mAlarmController;
    private final ZenModeController mZenController;
    private final StatusBarIconController mStatusBarIconController;
    private final ActivityStarter mActivityStarter;

    private QSPanel mQsPanel;

    private boolean mExpanded;
    private boolean mListening;
    private boolean mQsDisabled;

    private QSCarrierGroup mCarrierGroup;
    protected QuickQSPanel mHeaderQsPanel;
    protected QSTileHost mHost;
    private TintedIconManager mIconManager;
    private TouchAnimator mStatusIconsAlphaAnimator;
    private TouchAnimator mHeaderTextContainerAlphaAnimator;
    private TouchAnimator mPrivacyChipAlphaAnimator;
    private DualToneHandler mDualToneHandler;

    private View mSystemIconsView;
    private View mQuickQsStatusIcons;
    private View mHeaderTextContainerView;

    private int mRingerMode = AudioManager.RINGER_MODE_NORMAL;
    private AlarmManager.AlarmClockInfo mNextAlarm;

    private ImageView mNextAlarmIcon;
    /** {@link TextView} containing the actual text indicating when the next alarm will go off. */
    private TextView mNextAlarmTextView;
    private View mNextAlarmContainer;
    private View mStatusSeparator;
    private ImageView mRingerModeIcon;
    private TextView mRingerModeTextView;
    private View mRingerContainer;
    private Clock mClockView;
    private DateView mDateView;
    private OngoingPrivacyChip mPrivacyChip;
    private Space mSpace;
    private BatteryMeterView mBatteryRemainingIcon;
    private boolean mPermissionsHubEnabled;

    private boolean mLandscape;

    // pd1610: S5 控制中心头部＝大时钟 + 日期 + 齿轮/编辑，不是安卓原生那一套
    private boolean mS5Style;

    private View mQuickQsBrightness;
    private BrightnessController mBrightnessController;
    private boolean mIsQuickQsBrightnessEnabled;
    private boolean mIsQsAutoBrightnessEnabled;

    private PrivacyItemController mPrivacyItemController;

    private final BroadcastReceiver mRingerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mRingerMode = intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1);
            updateStatusText();
        }
    };
    private boolean mHasTopCutout = false;
    private boolean mPrivacyChipLogged = false;

    private final DeviceConfig.OnPropertyChangedListener mPropertyListener =
            new DeviceConfig.OnPropertyChangedListener() {
                @Override
                public void onPropertyChanged(String namespace, String name, String value) {
                    if (DeviceConfig.NAMESPACE_PRIVACY.equals(namespace)
                            && SystemUiDeviceConfigFlags.PROPERTY_PERMISSIONS_HUB_ENABLED.equals(
                            name)) {
                        mPermissionsHubEnabled = Boolean.valueOf(value);
                        StatusIconContainer iconContainer = findViewById(R.id.statusIcons);
                        iconContainer.setIgnoredSlots(getIgnoredIconSlots());
                    }
                }
            };

    private PrivacyItemController.Callback mPICCallback = new PrivacyItemController.Callback() {
        @Override
        public void privacyChanged(List<PrivacyItem> privacyItems) {
            mPrivacyChip.setPrivacyList(privacyItems);
            setChipVisibility(!privacyItems.isEmpty());
        }
    };

    @Inject
    public QuickStatusBarHeader(@Named(VIEW_CONTEXT) Context context, AttributeSet attrs,
            NextAlarmController nextAlarmController, ZenModeController zenModeController,
            StatusBarIconController statusBarIconController,
            ActivityStarter activityStarter, PrivacyItemController privacyItemController) {
        super(context, attrs);
        mAlarmController = nextAlarmController;
        mZenController = zenModeController;
        mStatusBarIconController = statusBarIconController;
        mActivityStarter = activityStarter;
        mPrivacyItemController = privacyItemController;
        mDualToneHandler = new DualToneHandler(
                new ContextThemeWrapper(context, R.style.QSHeaderTheme));
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mHeaderQsPanel = findViewById(R.id.quick_qs_panel);
        mSystemIconsView = findViewById(R.id.quick_status_bar_system_icons);
        mQuickQsStatusIcons = findViewById(R.id.quick_qs_status_icons);
        StatusIconContainer iconContainer = findViewById(R.id.statusIcons);
        // Ignore privacy icons because they show in the space above QQS
        iconContainer.addIgnoredSlots(getIgnoredIconSlots());
        iconContainer.setShouldRestrictIcons(false);
        mIconManager = new TintedIconManager(iconContainer);

        mQuickQsBrightness = findViewById(R.id.quick_qs_brightness_bar);
        mBrightnessController = new BrightnessController(getContext(),
                mQuickQsBrightness.findViewById(R.id.brightness_icon),
                mQuickQsBrightness.findViewById(R.id.brightness_slider));

        // Views corresponding to the header info section (e.g. ringer and next alarm).
        mHeaderTextContainerView = findViewById(R.id.header_text_container);
        mStatusSeparator = findViewById(R.id.status_separator);
        mNextAlarmIcon = findViewById(R.id.next_alarm_icon);
        mNextAlarmTextView = findViewById(R.id.next_alarm_text);
        mNextAlarmContainer = findViewById(R.id.alarm_container);
        mNextAlarmContainer.setOnClickListener(this::onClick);
        mRingerModeIcon = findViewById(R.id.ringer_mode_icon);
        mRingerModeTextView = findViewById(R.id.ringer_mode_text);
        mRingerContainer = findViewById(R.id.ringer_container);
        mRingerContainer.setOnClickListener(this::onClick);
        mPrivacyChip = findViewById(R.id.privacy_chip);
        mPrivacyChip.setOnClickListener(this::onClick);
        mCarrierGroup = findViewById(R.id.carrier_group);

        Rect tintArea = new Rect(0, 0, 0, 0);
        int colorForeground = Utils.getColorAttrDefaultColor(getContext(),
                android.R.attr.colorForeground);
        float intensity = getColorIntensity(colorForeground);
        int fillColor = mDualToneHandler.getSingleColor(intensity);

        // Set light text on the header icons because they will always be on a black background
        applyDarkness(R.id.clock, tintArea, 0, DarkIconDispatcher.DEFAULT_ICON_TINT);

        // Set the correct tint for the status icons so they contrast
        mIconManager.setTint(fillColor);
        mNextAlarmIcon.setImageTintList(ColorStateList.valueOf(fillColor));
        mRingerModeIcon.setImageTintList(ColorStateList.valueOf(fillColor));

        mClockView = findViewById(R.id.clock);
        mClockView.setOnClickListener(this);
        mDateView = findViewById(R.id.date);
        mSpace = findViewById(R.id.space);

        // Tint for the battery icons are handled in setupHost()
        mBatteryRemainingIcon = findViewById(R.id.batteryRemainingIcon);
        // QS will always show the estimate, and BatteryMeterView handles the case where
        // it's unavailable or charging
        mBatteryRemainingIcon.setPercentShowMode(BatteryMeterView.MODE_ESTIMATE);
        mRingerModeTextView.setSelected(true);
        mNextAlarmTextView.setSelected(true);

        mPermissionsHubEnabled = PrivacyItemControllerKt.isPermissionsHubEnabled();
        mLandscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        updateResources();
        // Change the ignored slots when DeviceConfig flag changes
        DeviceConfig.addOnPropertyChangedListener(DeviceConfig.NAMESPACE_PRIVACY,
                mContext.getMainExecutor(), mPropertyListener);

        Dependency.get(TunerService.class).addTunable(this,
                StatusBarIconController.ICON_BLACKLIST,
                QS_SHOW_AUTO_BRIGHTNESS, QS_SHOW_BRIGHTNESS_SLIDER);

        if (QsStyle.get(getContext()) == QsStyle.S5) {
            mS5Style = true;
            applyS5Header();
        }
    }

    /**
     * pd1610: 三星 S5 的控制中心头部。
     *
     * 原机那一行是「15:50（超大）+ 2月21日,星期日（两行） | 齿轮 编辑」，
     * 没有安卓 10 原生那排状态图标、也没有闹钟/铃声提示条。
     * 这里把日期从状态图标行搬过来（DateView 自带时间变化监听，不用自己刷）。
     */
    private void applyS5Header() {
        final Resources res = mContext.getResources();

        // S5 的亮度条是 QSPanel 里磁贴下面那一条，头部这条不用
        // （头部一旦自带亮度条，wrap_content 会把 39dp 的头撑到 210dp，
        //  最小/最大展开高度相等 ⇒ 展开比例恒 0，整个 QS 面板都不显示）
        mIsQuickQsBrightnessEnabled = false;

        // 原生那排（日期 + 状态图标 + 电池）和运营商名一律不要
        mQuickQsStatusIcons.setVisibility(View.GONE);
        mHeaderTextContainerView.setVisibility(View.GONE);
        if (mCarrierGroup != null) {
            mCarrierGroup.setVisibility(View.GONE);
        }
        mPrivacyChip.setVisibility(View.GONE);
        // 头部里的「速览磁贴」那一行也不要，磁贴全交给下面的 QSPanel
        mHeaderQsPanel.setVisibility(View.GONE);

        // 大时钟（原包 28sp，左边距 13dp）
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                res.getDimensionPixelSize(R.dimen.pd1610_qs_s5_clock_text_size));
        mClockView.setIncludeFontPadding(false);
        if (mClockView.getLayoutParams() instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams clp =
                    (LinearLayout.LayoutParams) mClockView.getLayoutParams();
            clp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            clp.weight = 0f;
            clp.setMarginStart(res.getDimensionPixelSize(
                    R.dimen.pd1610_qs_s5_clock_margin_start));
            clp.gravity = Gravity.CENTER_VERTICAL;
            mClockView.setLayoutParams(clp);
        }

        // 日期搬到时钟右边
        if (mDateView != null && mDateView.getParent() instanceof ViewGroup
                && mClockView.getParent() instanceof ViewGroup) {
            ViewGroup dateParent = (ViewGroup) mDateView.getParent();
            dateParent.removeView(mDateView);
            ViewGroup clockParent = (ViewGroup) mClockView.getParent();
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            dlp.setMarginStart(res.getDimensionPixelSize(
                    R.dimen.pd1610_qs_s5_date_margin_start));
            dlp.gravity = Gravity.CENTER_VERTICAL;
            mDateView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    res.getDimensionPixelSize(R.dimen.pd1610_qs_s5_date_text_size));
            mDateView.setTextColor(res.getColor(R.color.pd1610_qs_s5_date_text));
            mDateView.setIncludeFontPadding(false);
            // 原机日期是两行（公历 + 农历），这里放开两行
            mDateView.setSingleLine(false);
            mDateView.setMaxLines(2);
            mDateView.setAlpha(1f);
            clockParent.addView(mDateView, dlp);
        }

        // 右边：竖分隔线 + 齿轮（设置）+ 编辑（磁贴）
        if (mPrivacyChip.getParent() instanceof LinearLayout) {
            LinearLayout rightGroup = (LinearLayout) mPrivacyChip.getParent();
            rightGroup.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
            // 右边这组必须 wrap_content：否则它和时钟行平分宽度，日期被挤成「10…」
            LinearLayout.LayoutParams rlp =
                    (LinearLayout.LayoutParams) rightGroup.getLayoutParams();
            rlp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            rlp.weight = 0f;
            rightGroup.setLayoutParams(rlp);
            final int iconSize = res.getDimensionPixelSize(
                    R.dimen.pd1610_qs_s5_header_icon_size);
            final int iconPad = res.getDimensionPixelSize(
                    R.dimen.pd1610_qs_s5_header_icon_padding);

            ImageView gear = makeS5HeaderIcon(
                    com.android.internal.R.drawable.ic_settings_24dp, iconSize, iconPad);
            gear.setContentDescription("设置");
            gear.setOnClickListener(v -> {
                Intent intent = new Intent(Settings.ACTION_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                Dependency.get(ActivityStarter.class)
                        .postStartActivityDismissingKeyguard(intent, 0);
            });
            rightGroup.addView(gear);

            // 原机顺序＝齿轮、竖线、网格
            View divider = new View(mContext);
            divider.setBackgroundColor(res.getColor(R.color.pd1610_qs_s5_divider));
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                    res.getDimensionPixelSize(R.dimen.pd1610_qs_s5_divider_width),
                    res.getDimensionPixelSize(R.dimen.pd1610_qs_s5_divider_height));
            vlp.gravity = Gravity.CENTER_VERTICAL;
            vlp.setMarginStart(iconPad);
            vlp.setMarginEnd(iconPad);
            rightGroup.addView(divider, vlp);

            ImageView edit = makeS5HeaderIcon(R.drawable.pd1610_s5_grid, iconSize, iconPad);
            edit.setContentDescription("编辑磁贴");
            edit.setOnClickListener(v -> {
                View customizer = getRootView().findViewById(R.id.qs_customize);
                if (customizer instanceof com.android.systemui.qs.customize.QSCustomizer) {
                    int[] loc = new int[2];
                    v.getLocationOnScreen(loc);
                    ((com.android.systemui.qs.customize.QSCustomizer) customizer).show(
                            loc[0] + v.getWidth() / 2, loc[1] + v.getHeight() / 2);
                }
            });
            rightGroup.addView(edit);
        }

        updateResources();
    }

    private ImageView makeS5HeaderIcon(int resId, int size, int padding) {
        ImageView iv = new ImageView(mContext);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.gravity = Gravity.CENTER_VERTICAL;
        iv.setLayoutParams(lp);
        iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        iv.setPadding(padding, padding, padding, padding);
        iv.setImageResource(resId);
        // 框架里的齿轮素材是纯黑矢量，这里统一刷成白色（和 S5 原图一致）
        iv.setColorFilter(0xFFFFFFFF);
        TypedValue tv = new TypedValue();
        if (mContext.getTheme().resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, tv, true)) {
            iv.setBackground(mContext.getDrawable(tv.resourceId));
        }
        iv.setClickable(true);
        return iv;
    }

    private List<String> getIgnoredIconSlots() {
        ArrayList<String> ignored = new ArrayList<>();
        ignored.add(mContext.getResources().getString(
                com.android.internal.R.string.status_bar_camera));
        ignored.add(mContext.getResources().getString(
                com.android.internal.R.string.status_bar_microphone));
        if (mPermissionsHubEnabled) {
            ignored.add(mContext.getResources().getString(
                    com.android.internal.R.string.status_bar_location));
        }

        return ignored;
    }

    private void updateStatusText() {
        boolean changed = updateRingerStatus() || updateAlarmStatus();

        if (changed) {
            boolean alarmVisible = mNextAlarmTextView.getVisibility() == View.VISIBLE;
            boolean ringerVisible = mRingerModeTextView.getVisibility() == View.VISIBLE;
            mStatusSeparator.setVisibility(alarmVisible && ringerVisible ? View.VISIBLE
                    : View.GONE);
        }
    }

    private void setChipVisibility(boolean chipVisible) {
        if (chipVisible && mPermissionsHubEnabled) {
            mPrivacyChip.setVisibility(View.VISIBLE);
            // Makes sure that the chip is logged as viewed at most once each time QS is opened
            // mListening makes sure that the callback didn't return after the user closed QS
            if (!mPrivacyChipLogged && mListening) {
                mPrivacyChipLogged = true;
                StatsLog.write(StatsLog.PRIVACY_INDICATORS_INTERACTED,
                        StatsLog.PRIVACY_INDICATORS_INTERACTED__TYPE__CHIP_VIEWED);
            }
        } else {
            mPrivacyChip.setVisibility(View.GONE);
        }
    }

    private boolean updateRingerStatus() {
        boolean isOriginalVisible = mRingerModeTextView.getVisibility() == View.VISIBLE;
        CharSequence originalRingerText = mRingerModeTextView.getText();

        boolean ringerVisible = false;
        if (!ZenModeConfig.isZenOverridingRinger(mZenController.getZen(),
                mZenController.getConsolidatedPolicy())) {
            if (mRingerMode == AudioManager.RINGER_MODE_VIBRATE) {
                mRingerModeIcon.setImageResource(R.drawable.ic_volume_ringer_vibrate);
                mRingerModeTextView.setText(R.string.qs_status_phone_vibrate);
                ringerVisible = true;
            } else if (mRingerMode == AudioManager.RINGER_MODE_SILENT) {
                mRingerModeIcon.setImageResource(R.drawable.ic_volume_ringer_mute);
                mRingerModeTextView.setText(R.string.qs_status_phone_muted);
                ringerVisible = true;
            }
        }
        mRingerModeIcon.setVisibility(ringerVisible ? View.VISIBLE : View.GONE);
        mRingerModeTextView.setVisibility(ringerVisible ? View.VISIBLE : View.GONE);
        mRingerContainer.setVisibility(ringerVisible ? View.VISIBLE : View.GONE);

        return isOriginalVisible != ringerVisible ||
                !Objects.equals(originalRingerText, mRingerModeTextView.getText());
    }

    private boolean updateAlarmStatus() {
        boolean isOriginalVisible = mNextAlarmTextView.getVisibility() == View.VISIBLE;
        CharSequence originalAlarmText = mNextAlarmTextView.getText();

        boolean alarmVisible = false;
        if (mNextAlarm != null) {
            alarmVisible = true;
            mNextAlarmTextView.setText(formatNextAlarm(mNextAlarm));
        }
        mNextAlarmIcon.setVisibility(alarmVisible ? View.VISIBLE : View.GONE);
        mNextAlarmTextView.setVisibility(alarmVisible ? View.VISIBLE : View.GONE);
        mNextAlarmContainer.setVisibility(alarmVisible ? View.VISIBLE : View.GONE);

        return isOriginalVisible != alarmVisible ||
                !Objects.equals(originalAlarmText, mNextAlarmTextView.getText());
    }

    private void applyDarkness(int id, Rect tintArea, float intensity, int color) {
        View v = findViewById(id);
        if (v instanceof DarkReceiver) {
            ((DarkReceiver) v).onDarkChanged(tintArea, intensity, color);
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE;
        updateResources();
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        super.onRtlPropertiesChanged(layoutDirection);
        updateResources();
    }

    /**
     * The height of QQS should always be the status bar height + 128dp. This is normally easy, but
     * when there is a notch involved the status bar can remain a fixed pixel size.
     */
    private void updateMinimumHeight() {
        int sbHeight = mContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_height);
        int qqsHeight = mContext.getResources().getDimensionPixelSize(
                R.dimen.qs_quick_header_panel_height);

        if (mIsQuickQsBrightnessEnabled) {
            qqsHeight += mContext.getResources().getDimensionPixelSize(
                    R.dimen.brightness_mirror_height)
                    + mContext.getResources().getDimensionPixelSize(
                    R.dimen.qs_tile_margin_top);
        }

        setMinimumHeight(sbHeight + qqsHeight);
    }

    private void updateResources() {
        Resources resources = mContext.getResources();
        updateMinimumHeight();

        boolean headerImageEnabled = resources.getBoolean(R.bool.qs_custom_header);

        // Update height for a few views, especially due to landscape mode restricting space.
        // pd1610: S5 头部没有闹钟/铃声那条提示，高度直接归零
        mHeaderTextContainerView.getLayoutParams().height = mS5Style
                ? 0
                : resources.getDimensionPixelSize(R.dimen.qs_header_tooltip_height);
        mHeaderTextContainerView.setLayoutParams(mHeaderTextContainerView.getLayoutParams());

        int topMargin = mS5Style
                ? resources.getDimensionPixelSize(R.dimen.pd1610_qs_s5_header_height)
                : resources.getDimensionPixelSize(
                        com.android.internal.R.dimen.quick_qs_offset_height) + (headerImageEnabled ?
                        resources.getDimensionPixelSize(R.dimen.qs_header_image_offset) : 0);

        mSystemIconsView.getLayoutParams().height = topMargin;
        mSystemIconsView.setLayoutParams(mSystemIconsView.getLayoutParams());

        if (mIsQuickQsBrightnessEnabled) {
            if (mIsQsAutoBrightnessEnabled && resources.getBoolean(
                    com.android.internal.R.bool.config_automatic_brightness_available)) {
                mQuickQsBrightness.findViewById(R.id.brightness_icon).setVisibility(View.VISIBLE);
            } else {
                mQuickQsBrightness.findViewById(R.id.brightness_icon).setVisibility(View.GONE);
            }
            mQuickQsBrightness.setVisibility(View.VISIBLE);
        } else {
            mQuickQsBrightness.setVisibility(View.GONE);
        }

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        if (mQsDisabled) {
            lp.height = topMargin;
        } else if (mS5Style) {
            // S5 头部＝固定 39dp 的大时钟行（原包 notification_panel_header_height）
            lp.height = topMargin;
        } else {
            int qsHeight = resources.getDimensionPixelSize(
                    com.android.internal.R.dimen.quick_qs_total_height);

            if (headerImageEnabled) {
                qsHeight += resources.getDimensionPixelSize(R.dimen.qs_header_image_offset);
            }

            // always add the margin below the statusbar with or without image
            lp.height = Math.max(getMinimumHeight(), qsHeight);
        }

        setLayoutParams(lp);

        updateStatusIconAlphaAnimator();
        updateHeaderTextContainerAlphaAnimator();
        updatePrivacyChipAlphaAnimator();

        boolean shouldUseWallpaperTextColor =
                mLandscape && !headerImageEnabled && !mS5Style;
        mClockView.useWallpaperTextColor(shouldUseWallpaperTextColor);
    }

    private void updateStatusIconAlphaAnimator() {
        mStatusIconsAlphaAnimator = new TouchAnimator.Builder()
                .addFloat(mQuickQsStatusIcons, "alpha", 1, 0, 0)
                .build();
    }

    private void updateHeaderTextContainerAlphaAnimator() {
        mHeaderTextContainerAlphaAnimator = new TouchAnimator.Builder()
                .addFloat(mHeaderTextContainerView, "alpha", 0, 0, 1)
                .build();
    }

    private void updatePrivacyChipAlphaAnimator() {
        mPrivacyChipAlphaAnimator = new TouchAnimator.Builder()
                .addFloat(mPrivacyChip, "alpha", 1, 0, 1)
                .build();
    }

    public void setExpanded(boolean expanded) {
        if (mExpanded == expanded) return;
        mExpanded = expanded;
        mHeaderQsPanel.setExpanded(expanded);
        updateEverything();
    }

    /**
     * Animates the inner contents based on the given expansion details.
     *
     * @param forceExpanded whether we should show the state expanded forcibly
     * @param expansionFraction how much the QS panel is expanded/pulled out (up to 1f)
     * @param panelTranslationY how much the panel has physically moved down vertically (required
     *                          for keyguard animations only)
     */
    public void setExpansion(boolean forceExpanded, float expansionFraction,
                             float panelTranslationY) {
        final float keyguardExpansionFraction = forceExpanded ? 1f : expansionFraction;
        if (mStatusIconsAlphaAnimator != null) {
            mStatusIconsAlphaAnimator.setPosition(keyguardExpansionFraction);
        }

        if (forceExpanded) {
            // If the keyguard is showing, we want to offset the text so that it comes in at the
            // same time as the panel as it slides down.
            mHeaderTextContainerView.setTranslationY(panelTranslationY);
        } else {
            mHeaderTextContainerView.setTranslationY(0f);
        }

        if (mHeaderTextContainerAlphaAnimator != null) {
            mHeaderTextContainerAlphaAnimator.setPosition(keyguardExpansionFraction);
            if (keyguardExpansionFraction > 0) {
                mHeaderTextContainerView.setVisibility(mS5Style ? GONE : VISIBLE);
            } else {
                mHeaderTextContainerView.setVisibility(INVISIBLE);
            }
        }
        if (mPrivacyChipAlphaAnimator != null) {
            mPrivacyChip.setExpanded(expansionFraction > 0.5);
            mPrivacyChipAlphaAnimator.setPosition(keyguardExpansionFraction);
        }

        if (mIsQuickQsBrightnessEnabled) {
            if (keyguardExpansionFraction > 0) {
                mQuickQsBrightness.setVisibility(INVISIBLE);
            } else {
                mQuickQsBrightness.setVisibility(mQsDisabled ? GONE : VISIBLE);
            }
        }
    }

    public void disable(int state1, int state2, boolean animate) {
        final boolean disabled = (state2 & DISABLE2_QUICK_SETTINGS) != 0;
        if (disabled == mQsDisabled) return;
        mQsDisabled = disabled;
        mHeaderQsPanel.setDisabledByPolicy(disabled);
        mHeaderTextContainerView.setVisibility(
                mQsDisabled || mS5Style ? View.GONE : View.VISIBLE);
        mQuickQsStatusIcons.setVisibility(
                mQsDisabled || mS5Style ? View.GONE : View.VISIBLE);
        if (!mS5Style) {
            mQuickQsBrightness.setVisibility(mQsDisabled ? View.GONE : View.VISIBLE);
        }
        updateResources();
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        mStatusBarIconController.addIconGroup(mIconManager);
        requestApplyInsets();
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        DisplayCutout cutout = insets.getDisplayCutout();
        Pair<Integer, Integer> padding = PhoneStatusBarView.cornerCutoutMargins(
                cutout, getDisplay());
        if (padding == null) {
            mSystemIconsView.setPaddingRelative(
                    getResources().getDimensionPixelSize(R.dimen.status_bar_padding_start),
                    getResources().getDimensionPixelSize(R.dimen.status_bar_padding_top),
                    getResources().getDimensionPixelSize(R.dimen.status_bar_padding_end),
                    0);
        } else {
            mSystemIconsView.setPadding(
                    padding.first,
                    getResources().getDimensionPixelSize(R.dimen.status_bar_padding_top),
                    padding.second, 0);

        }
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) mSpace.getLayoutParams();
        if (cutout != null) {
            Rect topCutout = cutout.getBoundingRectTop();
            if (topCutout.isEmpty()) {
                mHasTopCutout = false;
                lp.width = 0;
                mSpace.setVisibility(View.GONE);
            } else {
                mHasTopCutout = true;
                lp.width = topCutout.width();
                mSpace.setVisibility(View.VISIBLE);
            }
        }
        mSpace.setLayoutParams(lp);
        setChipVisibility(mPrivacyChip.getVisibility() == View.VISIBLE);
        // Offset container padding to align with QS brightness bar.
        final int sp = getResources().getDimensionPixelSize(R.dimen.notification_side_paddings);
        RelativeLayout.LayoutParams lpQuickQsBrightness = (RelativeLayout.LayoutParams)
                mQuickQsBrightness.getLayoutParams();
        lpQuickQsBrightness.setMargins(sp - mPaddingLeft, 0, sp - mPaddingRight, 0);
        mQuickQsBrightness.setLayoutParams(lpQuickQsBrightness);
        return super.onApplyWindowInsets(insets);
    }

    @Override
    @VisibleForTesting
    public void onDetachedFromWindow() {
        setListening(false);
        mStatusBarIconController.removeIconGroup(mIconManager);
        super.onDetachedFromWindow();
    }

    public void setListening(boolean listening) {
        if (listening == mListening) {
            return;
        }
        mHeaderQsPanel.setListening(listening);
        mListening = listening;
        mCarrierGroup.setListening(mListening);

        if (listening) {
            mZenController.addCallback(this);
            mAlarmController.addCallback(this);
            mContext.registerReceiver(mRingerReceiver,
                    new IntentFilter(AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION));
            mPrivacyItemController.addCallback(mPICCallback);
            mBrightnessController.registerCallbacks();
        } else {
            mZenController.removeCallback(this);
            mAlarmController.removeCallback(this);
            mPrivacyItemController.removeCallback(mPICCallback);
            mContext.unregisterReceiver(mRingerReceiver);
            mBrightnessController.unregisterCallbacks();
            mPrivacyChipLogged = false;
        }
    }

    @Override
    public void onClick(View v) {
        if (v == mClockView) {
            mActivityStarter.postStartActivityDismissingKeyguard(new Intent(
                    AlarmClock.ACTION_SHOW_ALARMS), 0);
        } else if (v == mNextAlarmContainer && mNextAlarmContainer.isVisibleToUser()) {
            if (mNextAlarm.getShowIntent() != null) {
                mActivityStarter.postStartActivityDismissingKeyguard(
                        mNextAlarm.getShowIntent());
            } else {
                Log.d(TAG, "No PendingIntent for next alarm. Using default intent");
                mActivityStarter.postStartActivityDismissingKeyguard(new Intent(
                        AlarmClock.ACTION_SHOW_ALARMS), 0);
            }
        } else if (v == mPrivacyChip) {
            // Makes sure that the builder is grabbed as soon as the chip is pressed
            PrivacyDialogBuilder builder = mPrivacyChip.getBuilder();
            if (builder.getAppsAndTypes().size() == 0) return;
            Handler mUiHandler = new Handler(Looper.getMainLooper());
            StatsLog.write(StatsLog.PRIVACY_INDICATORS_INTERACTED,
                    StatsLog.PRIVACY_INDICATORS_INTERACTED__TYPE__CHIP_CLICKED);
            mUiHandler.post(() -> {
                mActivityStarter.postStartActivityDismissingKeyguard(
                        new Intent(Intent.ACTION_REVIEW_ONGOING_PERMISSION_USAGE), 0);
                mHost.collapsePanels();
            });
        } else if (v == mRingerContainer && mRingerContainer.isVisibleToUser()) {
            mActivityStarter.postStartActivityDismissingKeyguard(new Intent(
                    Settings.ACTION_SOUND_SETTINGS), 0);
        }
    }

    @Override
    public void onNextAlarmChanged(AlarmManager.AlarmClockInfo nextAlarm) {
        mNextAlarm = nextAlarm;
        updateStatusText();
    }

    @Override
    public void onZenChanged(int zen) {
        updateStatusText();
    }

    @Override
    public void onConfigChanged(ZenModeConfig config) {
        updateStatusText();
    }

    public void updateEverything() {
        post(() -> setClickable(!mExpanded));
    }

    public void setQSPanel(final QSPanel qsPanel) {
        mQsPanel = qsPanel;
        setupHost(qsPanel.getHost());
    }

    public void setupHost(final QSTileHost host) {
        mHost = host;
        //host.setHeaderView(mExpandIndicator);
        mHeaderQsPanel.setQSPanelAndHeader(mQsPanel, this);
        mHeaderQsPanel.setHost(host, null /* No customization in header */);


        Rect tintArea = new Rect(0, 0, 0, 0);
        int colorForeground = Utils.getColorAttrDefaultColor(getContext(),
                android.R.attr.colorForeground);
        float intensity = getColorIntensity(colorForeground);
        int fillColor = mDualToneHandler.getSingleColor(intensity);
        mBatteryRemainingIcon.onDarkChanged(tintArea, intensity, fillColor);
    }

    public void setCallback(Callback qsPanelCallback) {
        mHeaderQsPanel.setCallback(qsPanelCallback);
    }

    private String formatNextAlarm(AlarmManager.AlarmClockInfo info) {
        if (info == null) {
            return "";
        }
        String skeleton = android.text.format.DateFormat
                .is24HourFormat(mContext, ActivityManager.getCurrentUser()) ? "EHm" : "Ehma";
        String pattern = android.text.format.DateFormat
                .getBestDateTimePattern(Locale.getDefault(), skeleton);
        return android.text.format.DateFormat.format(pattern, info.getTriggerTime()).toString();
    }

    public static float getColorIntensity(@ColorInt int color) {
        return color == Color.WHITE ? 0 : 1;
    }

    public void setMargins(int sideMargins) {
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            // Prevents these views from getting set a margin.
            // The Icon views all have the same padding set in XML to be aligned.
            if (v == mSystemIconsView || v == mQuickQsStatusIcons || v == mHeaderQsPanel
                    || v == mHeaderTextContainerView) {
                continue;
            }
            RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) v.getLayoutParams();
            lp.leftMargin = sideMargins;
            lp.rightMargin = sideMargins;
        }
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (QS_SHOW_BRIGHTNESS_SLIDER.equals(key)) {
            if (mS5Style) {
                // S5 头部自带亮度条，这个 key 管不着
                mIsQuickQsBrightnessEnabled = true;
            } else {
                try {
                    mIsQuickQsBrightnessEnabled = Integer.parseInt(newValue) > 1;
                } catch (NumberFormatException e) {
                    // Catches exception as newValue may be null or malformed.
                    mIsQuickQsBrightnessEnabled = false;
                }
            }
            updateResources();
        } else if (QS_SHOW_AUTO_BRIGHTNESS.equals(key)) {
            mIsQsAutoBrightnessEnabled = TunerService.parseIntegerSwitch(newValue, true);
            updateResources();
        } else if (StatusBarIconController.ICON_BLACKLIST.equals(key)) {
            mClockView.setClockVisibleByUser(!StatusBarIconController.getIconBlacklist(newValue)
                    .contains("clock"));
        }
    }
}
