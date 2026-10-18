/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.stack;

import static com.android.internal.util.Preconditions.checkNotNull;

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.statusbar.notification.row.ActivatableNotificationView;

/**
 * Similar in size and appearance to the NotificationShelf, appears at the beginning of some
 * notification sections. Currently only used for gentle notifications.
 */
public class SectionHeaderView extends ActivatableNotificationView {
    private ViewGroup mContents;
    private TextView mLabelView;
    private ImageView mClearAllButton;
    @Nullable private View.OnClickListener mOnClearClickListener = null;

    private final RectF mTmpRect = new RectF();

    public SectionHeaderView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mContents = checkNotNull(findViewById(R.id.content));
        bindContents();
    }

    private void bindContents() {
        mLabelView = checkNotNull(findViewById(R.id.header_label));
        mClearAllButton = checkNotNull(findViewById(R.id.btn_clear_all));
        // pd1610 v126b: 首帧也要按当前 UI 风格着色（原来只有 uiMode 变化时才刷）
        updateLabelColor();
        if (mOnClearClickListener != null) {
            mClearAllButton.setOnClickListener(mOnClearClickListener);
        }
    }

    @Override
    protected View getContentView() {
        return mContents;
    }

    /**
     * Destroys and reinflates the visible contents of the section header. For use on configuration
     * changes or any other time that layout values might need to be re-evaluated.
     *
     * Does not reinflate the base content view itself ({@link #getContentView()} or any of the
     * decorator views, such as the background view or shadow view.
     */
    void reinflateContents() {
        mContents.removeAllViews();
        LayoutInflater.from(getContext()).inflate(
                R.layout.status_bar_notification_section_header_contents,
                mContents);
        bindContents();
    }

    @Override
    public boolean isTransparent() {
        return true;
    }

    /** Must be called whenever the UI mode changes (i.e. when we enter night mode). */
    void onUiModeChanged() {
        updateBackgroundColors();
        updateLabelColor();
        mClearAllButton.setImageResource(
                R.drawable.status_bar_notification_section_header_clear_btn);
    }

    /**
     * pd1610 v129: 锁屏⇄解锁切换后重套分节头样式（深色卡 + 灰蓝标题）。
     */
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // pd1610 v130: 分节头同样是锁屏时建好的，展开 S5 控制中心要补刷标题色
        updateLabelColor();
    }

    public void refreshS5Style() {
        refreshS5BackgroundTint();
        updateLabelColor();
    }

    /** pd1610 v126b: S5 深色通知区的分节标题用灰蓝，原生用 GM2_grey_900。 */
    private void updateLabelColor() {
        final Context c = getContext();
        mLabelView.setTextColor(com.android.systemui.qs.QsStyle.get(c)
                == com.android.systemui.qs.QsStyle.S5
                ? c.getColor(R.color.pd1610_qs_section_label)
                : c.getColor(R.color.notification_section_header_label_color));
    }

    void setAreThereDismissableGentleNotifs(boolean areThereDismissableGentleNotifs) {
        mClearAllButton.setVisibility(areThereDismissableGentleNotifs ? View.VISIBLE : View.GONE);
    }

    @Override
    protected boolean disallowSingleClick(MotionEvent event) {
        // Disallow single click on lockscreen if user is tapping on clear all button
        mTmpRect.set(
                mClearAllButton.getLeft(),
                mClearAllButton.getTop(),
                mClearAllButton.getLeft() + mClearAllButton.getWidth(),
                mClearAllButton.getTop() + mClearAllButton.getHeight());
        return mTmpRect.contains(event.getX(), event.getY());
    }

    /**
     * Fired whenever the user clicks on the body of the header (e.g. no sub-buttons or anything).
     */
    void setOnHeaderClickListener(View.OnClickListener listener) {
        mContents.setOnClickListener(listener);
    }

    /** Fired when the user clicks on the "X" button on the far right of the header. */
    void setOnClearAllClickListener(View.OnClickListener listener) {
        mOnClearClickListener = listener;
        mClearAllButton.setOnClickListener(listener);
    }
}
