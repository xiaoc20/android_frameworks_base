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

package com.android.systemui.qs.tileimpl;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.service.quicksettings.Tile;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.settingslib.Utils;
import com.android.systemui.FontSizeUtils;
import com.android.systemui.R;
import com.android.systemui.qs.QsStyle;
import com.android.systemui.plugins.qs.QSIconView;
import com.android.systemui.plugins.qs.QSTile;

import java.util.Objects;

/** View that represents a standard quick settings tile. **/
public class QSTileView extends QSTileBaseView {
    private static final int MAX_LABEL_LINES = 2;
    private static final boolean DUAL_TARGET_ALLOWED = true;
    private View mDivider;
    protected TextView mLabel;
    protected TextView mSecondLine;
    private ImageView mPadLock;
    private int mState;
    private ViewGroup mLabelContainer;
    private View mExpandIndicator;
    private View mExpandSpace;
    private ColorStateList mColorLabelDefault;
    private ColorStateList mColorLabelUnavailable;

    public QSTileView(Context context, QSIconView icon) {
        this(context, icon, false);
    }

    public QSTileView(Context context, QSIconView icon, boolean collapsedView) {
        super(context, icon, collapsedView);

        setClipChildren(false);
        setClipToPadding(false);

        setClickable(true);
        setId(View.generateViewId());
        createLabel();
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        mColorLabelDefault = Utils.getColorAttr(getContext(), android.R.attr.textColorPrimary);
        // The text color for unavailable tiles is textColorSecondary, same as secondaryLabel for
        // contrast purposes
        mColorLabelUnavailable = Utils.getColorAttr(getContext(),
                android.R.attr.textColorSecondary);
        // pd1610: TouchWiz 是深色面板，标签强制白色
        final int qsStyle = QsStyle.get(getContext());
        if (qsStyle == QsStyle.S5) {
            mColorLabelDefault = ColorStateList.valueOf(
                    getContext().getColor(R.color.pd1610_qs_label));
            mColorLabelUnavailable = ColorStateList.valueOf(
                    getContext().getColor(R.color.pd1610_qs_label_dim));
        } else if (qsStyle == QsStyle.TOUCHWIZ) {
            mColorLabelDefault = ColorStateList.valueOf(0xFFFFFFFF);
            mColorLabelUnavailable = ColorStateList.valueOf(0xFF9E9E9E);
        }
    }

    TextView getLabel() {
        return mLabel;
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        FontSizeUtils.updateFontSize(mLabel, R.dimen.qs_tile_text_size);
        FontSizeUtils.updateFontSize(mSecondLine, R.dimen.qs_tile_text_size);
    }

    @Override
    public int getDetailY() {
        return getTop() + mLabelContainer.getTop() + mLabelContainer.getHeight() / 2;
    }

    protected void createLabel() {
        mLabelContainer = (ViewGroup) LayoutInflater.from(getContext())
                .inflate(R.layout.qs_tile_label, this, false);
        mLabelContainer.setClipChildren(false);
        mLabelContainer.setClipToPadding(false);
        mLabel = mLabelContainer.findViewById(R.id.tile_label);
        mPadLock = mLabelContainer.findViewById(R.id.restricted_padlock);
        mDivider = mLabelContainer.findViewById(R.id.underline);
        mExpandIndicator = mLabelContainer.findViewById(R.id.expand_indicator);
        mExpandSpace = mLabelContainer.findViewById(R.id.expand_space);
        mSecondLine = mLabelContainer.findViewById(R.id.app_label);
        if (QsStyle.get(getContext()) == QsStyle.S5) {
            // S5 的标签允许折成两行（原机「移动/数据」「声音/模式」就是上下两行），
            // 而且必须限制在格子宽度内，否则五列窄格子会溢出屏幕
            mLabel.setSingleLine(false);
            mLabel.setMaxLines(2);
            mLabel.setEllipsize(TextUtils.TruncateAt.END);
            mLabel.setHorizontallyScrolling(false);
            android.widget.LinearLayout.LayoutParams llp =
                    (android.widget.LinearLayout.LayoutParams) mLabel.getLayoutParams();
            llp.width = 0;
            llp.weight = 1f;
            mLabel.setLayoutParams(llp);
            mLabelContainer.setClipChildren(true);
        }
        addView(mLabelContainer);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        // Remeasure view if the primary label requires more then 2 lines or the secondary label
        // text will be cut off.
        if (mLabel.getLineCount() > MAX_LABEL_LINES || !TextUtils.isEmpty(mSecondLine.getText())
                        && mSecondLine.getLineHeight() > mSecondLine.getHeight()) {
            mLabel.setSingleLine();
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    @Override
    protected void handleStateChanged(QSTile.State state) {
        super.handleStateChanged(state);
        if (!Objects.equals(mLabel.getText(), state.label) || mState != state.state) {
            mLabel.setTextColor(state.state == Tile.STATE_UNAVAILABLE ? mColorLabelUnavailable
                    : mColorLabelDefault);
            mState = state.state;
            mLabel.setText(state.label);
        }
        if (!Objects.equals(mSecondLine.getText(), state.secondaryLabel)) {
            mSecondLine.setText(state.secondaryLabel);
            mSecondLine.setVisibility(TextUtils.isEmpty(state.secondaryLabel) ? View.GONE
                    : View.VISIBLE);
        }
        boolean dualTarget = DUAL_TARGET_ALLOWED && state.dualTarget;
        // pd1610: TouchWiz/S5 是五列窄格子，22dp 占位 + 18dp 箭头会把标签挤出去
        // （「移动数据」被裁成「动数」），这两个风格一律不显示展开件
        final int qsStyle = QsStyle.get(getContext());
        if (qsStyle == QsStyle.TOUCHWIZ || qsStyle == QsStyle.S5) {
            dualTarget = false;
            mExpandIndicator.setVisibility(View.GONE);
            mExpandSpace.setVisibility(View.GONE);
        } else {
            mExpandIndicator.setVisibility(dualTarget ? View.VISIBLE : View.GONE);
            mExpandSpace.setVisibility(dualTarget ? View.VISIBLE : View.GONE);
        }
        mLabelContainer.setContentDescription(dualTarget ? state.dualLabelContentDescription
                : null);
        if (dualTarget != mLabelContainer.isClickable()) {
            mLabelContainer.setClickable(dualTarget);
            mLabelContainer.setLongClickable(dualTarget);
            mLabelContainer.setBackground(dualTarget ? newTileBackground() : null);
        }
        mLabel.setEnabled(!state.disabledByPolicy);
        mPadLock.setVisibility(state.disabledByPolicy ? View.VISIBLE : View.GONE);
    }

    @Override
    public void init(OnClickListener click, OnClickListener secondaryClick,
            OnLongClickListener longClick) {
        super.init(click, secondaryClick, longClick);
        mLabelContainer.setOnClickListener(secondaryClick);
        mLabelContainer.setOnLongClickListener(longClick);
        mLabelContainer.setClickable(false);
        mLabelContainer.setLongClickable(false);
    }
}
