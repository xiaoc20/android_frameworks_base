/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.util.AttributeSet;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.LatencyTracker;
import com.android.systemui.DejankUtils;
import com.android.systemui.Dependency;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.doze.DozeLog;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.FlingAnimationUtils;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.statusbar.policy.KeyguardMonitor;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;

public abstract class PanelView extends FrameLayout {
    public static final boolean DEBUG = PanelBar.DEBUG;
    public static final String TAG = PanelView.class.getSimpleName();
    private static final int INITIAL_OPENING_PEEK_DURATION = 200;
    private static final int PEEK_ANIMATION_DURATION = 360;
    private static final int NO_FIXED_DURATION = -1;
    protected long mDownTime;
    protected boolean mTouchSlopExceededBeforeDown;
    private float mMinExpandHeight;
    private LockscreenGestureLogger mLockscreenGestureLogger = new LockscreenGestureLogger();
    private boolean mPanelUpdateWhenAnimatorEnds;
    private boolean mVibrateOnOpening;
    protected boolean mLaunchingNotification;
    private int mFixedDuration = NO_FIXED_DURATION;
    protected ArrayList<PanelExpansionListener> mExpansionListeners = new ArrayList<>();

    private final void logf(String fmt, Object... args) {
        Log.v(TAG, (mViewName != null ? (mViewName + ": ") : "") + String.format(fmt, args));
    }

    protected StatusBar mStatusBar;
    protected HeadsUpManagerPhone mHeadsUpManager;

    private float mPeekHeight;
    private float mHintDistance;
    private float mInitialOffsetOnTouch;
    private boolean mCollapsedAndHeadsUpOnDown;
    private float mExpandedFraction = 0;
    protected float mExpandedHeight = 0;
    private boolean mPanelClosedOnDown;
    private boolean mHasLayoutedSinceDown;
    private float mUpdateFlingVelocity;
    private boolean mUpdateFlingOnLayout;
    private boolean mPeekTouching;
    private boolean mJustPeeked;
    private boolean mClosing;
    protected boolean mTracking;
    private boolean mTouchSlopExceeded;
    private int mTrackingPointer;
    protected int mTouchSlop;

    /**
     * pd1610 v93: S5 只允许从底栏（「没有服务」那条）上滑收面板。
     * 返回 true = 这次 DOWN 不在手柄上，面板不许被拖（通知列表照常滚动）。
     */
    protected boolean isS5TouchOutsideCloseHandle(float x, float y) {
        return false;
    }

    private boolean mS5UpDragBlocked;

    /**
     * pd1610 v104: 「这次上滑算不算被拦住」改成用到时再算。
     * 原来只在 DOWN 那一刻算一次并缓存（还要求展开 >= 0.99），
     * 面板在开合动画尾巴上按下时 fraction 不够 ⇒ 不拦 ⇒ 上滑照样收面板。
     * 这里用 DOWN 的坐标 + 当前展开比例现算。
     */
    protected boolean isS5UpDragBlockedNow() {
        return mS5UpDragBlocked
                || (getExpandedFraction() >= 0.5f
                        && isS5TouchOutsideCloseHandle(mInitialTouchX, mInitialTouchY));
    }
    protected boolean mHintAnimationRunning;
    private boolean mOverExpandedBeforeFling;
    private boolean mTouchAboveFalsingThreshold;
    private int mUnlockFalsingThreshold;
    private boolean mTouchStartedInEmptyArea;
    private boolean mMotionAborted;
    private boolean mUpwardsWhenTresholdReached;
    private boolean mAnimatingOnDown;

    private ValueAnimator mHeightAnimator;
    private ObjectAnimator mPeekAnimator;
    private final VelocityTracker mVelocityTracker = VelocityTracker.obtain();
    private FlingAnimationUtils mFlingAnimationUtils;
    private FlingAnimationUtils mFlingAnimationUtilsClosing;
    private FlingAnimationUtils mFlingAnimationUtilsDismissing;
    private final FalsingManager mFalsingManager;
    private final VibratorHelper mVibratorHelper;

    /**
     * Whether an instant expand request is currently pending and we are just waiting for layout.
     */
    private boolean mInstantExpanding;
    private boolean mAnimateAfterExpanding;

    PanelBar mBar;

    private String mViewName;
    private float mInitialTouchY;
    private float mInitialTouchX;
    private boolean mTouchDisabled;

    /**
     * Whether or not the PanelView can be expanded or collapsed with a drag.
     */
    private boolean mNotificationsDragEnabled;

    private Interpolator mBounceInterpolator;
    protected KeyguardBottomAreaView mKeyguardBottomArea;

    /**
     * Speed-up factor to be used when {@link #mFlingCollapseRunnable} runs the next time.
     */
    private float mNextCollapseSpeedUpFactor = 1.0f;

    protected boolean mExpanding;
    private boolean mGestureWaitForTouchSlop;
    private boolean mIgnoreXTouchSlop;
    private boolean mExpandLatencyTracking;
    protected final KeyguardMonitor mKeyguardMonitor = Dependency.get(KeyguardMonitor.class);
    protected final SysuiStatusBarStateController mStatusBarStateController =
            (SysuiStatusBarStateController) Dependency.get(StatusBarStateController.class);

    protected void onExpandingFinished() {
        mBar.onExpandingFinished();
    }

    protected void onExpandingStarted() {
    }

    private void notifyExpandingStarted() {
        if (!mExpanding) {
            mExpanding = true;
            onExpandingStarted();
        }
    }

    protected final void notifyExpandingFinished() {
        endClosing();
        if (mExpanding) {
            mExpanding = false;
            onExpandingFinished();
        }
    }

    private void runPeekAnimation(long duration, float peekHeight, boolean collapseWhenFinished) {
        mPeekHeight = peekHeight;
        if (DEBUG) logf("peek to height=%.1f", mPeekHeight);
        if (mHeightAnimator != null) {
            return;
        }
        if (mPeekAnimator != null) {
            mPeekAnimator.cancel();
        }
        mPeekAnimator = ObjectAnimator.ofFloat(this, "expandedHeight", mPeekHeight)
                .setDuration(duration);
        mPeekAnimator.setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN);
        mPeekAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                mPeekAnimator = null;
                if (!mCancelled && collapseWhenFinished) {
                    postOnAnimation(mPostCollapseRunnable);
                }

            }
        });
        notifyExpandingStarted();
        mPeekAnimator.start();
        mJustPeeked = true;
    }

    public PanelView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mFlingAnimationUtils = new FlingAnimationUtils(context, 0.6f /* maxLengthSeconds */,
                0.6f /* speedUpFactor */);
        mFlingAnimationUtilsClosing = new FlingAnimationUtils(context, 0.5f /* maxLengthSeconds */,
                0.6f /* speedUpFactor */);
        mFlingAnimationUtilsDismissing = new FlingAnimationUtils(context,
                0.5f /* maxLengthSeconds */, 0.2f /* speedUpFactor */, 0.6f /* x2 */,
                0.84f /* y2 */);
        mBounceInterpolator = new BounceInterpolator();
        mFalsingManager = Dependency.get(FalsingManager.class);  // TODO: inject into a controller.
        mNotificationsDragEnabled =
                getResources().getBoolean(R.bool.config_enableNotificationShadeDrag);
        mVibratorHelper = Dependency.get(VibratorHelper.class);
        mVibrateOnOpening = mContext.getResources().getBoolean(
                R.bool.config_vibrateOnIconAnimation);
    }

    protected void loadDimens() {
        final Resources res = getContext().getResources();
        final ViewConfiguration configuration = ViewConfiguration.get(getContext());
        mTouchSlop = configuration.getScaledTouchSlop();
        mHintDistance = res.getDimension(R.dimen.hint_move_distance);
        mUnlockFalsingThreshold = res.getDimensionPixelSize(R.dimen.unlock_falsing_threshold);
    }

    private void addMovement(MotionEvent event) {
        // Add movement to velocity tracker using raw screen X and Y coordinates instead
        // of window coordinates because the window frame may be moving at the same time.
        float deltaX = event.getRawX() - event.getX();
        float deltaY = event.getRawY() - event.getY();
        event.offsetLocation(deltaX, deltaY);
        mVelocityTracker.addMovement(event);
        event.offsetLocation(-deltaX, -deltaY);
    }

    public void setTouchAndAnimationDisabled(boolean disabled) {
        mTouchDisabled = disabled;
        if (mTouchDisabled) {
            cancelHeightAnimator();
            if (mTracking) {
                onTrackingStopped(true /* expanded */);
            }
            notifyExpandingFinished();
        }
    }

    public void startExpandLatencyTracking() {
        if (LatencyTracker.isEnabled(mContext)) {
            LatencyTracker.getInstance(mContext).onActionStart(
                    LatencyTracker.ACTION_EXPAND_PANEL);
            mExpandLatencyTracking = true;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mInstantExpanding
                || (mTouchDisabled && event.getActionMasked() != MotionEvent.ACTION_CANCEL)
                || (mMotionAborted && event.getActionMasked() != MotionEvent.ACTION_DOWN)) {
            return false;
        }

        // If dragging should not expand the notifications shade, then return false.
        if (!mNotificationsDragEnabled) {
            if (mTracking) {
                // Turn off tracking if it's on or the shade can get stuck in the down position.
                onTrackingStopped(true /* expand */);
            }
            return false;
        }

        // On expanding, single mouse click expands the panel instead of dragging.
        if (isFullyCollapsed() && event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                expand(true);
            }
            return true;
        }

        /*
         * We capture touch events here and update the expand height here in case according to
         * the users fingers. This also handles multi-touch.
         *
         * If the user just clicks shortly, we show a quick peek of the shade.
         *
         * Flinging is also enabled in order to open or close the shade.
         */

        int pointerIndex = event.findPointerIndex(mTrackingPointer);
        if (pointerIndex < 0) {
            pointerIndex = 0;
            mTrackingPointer = event.getPointerId(pointerIndex);
        }
        final float x = event.getX(pointerIndex);
        final float y = event.getY(pointerIndex);

        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mGestureWaitForTouchSlop = shouldGestureWaitForTouchSlop();
            mIgnoreXTouchSlop = isFullyCollapsed() || shouldGestureIgnoreXTouchSlop(x, y);
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startExpandMotion(x, y, false /* startTracking */, mExpandedHeight);
                mJustPeeked = false;
                mMinExpandHeight = 0.0f;
                mPanelClosedOnDown = isFullyCollapsed();
                mHasLayoutedSinceDown = false;
                mUpdateFlingOnLayout = false;
                mMotionAborted = false;
                mPeekTouching = mPanelClosedOnDown;
                mDownTime = SystemClock.uptimeMillis();
                mTouchAboveFalsingThreshold = false;
                mCollapsedAndHeadsUpOnDown = isFullyCollapsed()
                        && mHeadsUpManager.hasPinnedHeadsUp();
                addMovement(event);
                if (!mGestureWaitForTouchSlop || (mHeightAnimator != null && !mHintAnimationRunning)
                        || mPeekAnimator != null) {
                    mTouchSlopExceeded = (mHeightAnimator != null && !mHintAnimationRunning)
                            || mPeekAnimator != null || mTouchSlopExceededBeforeDown;
                    cancelHeightAnimator();
                    cancelPeek();
                    onTrackingStarted();
                }
                if (isFullyCollapsed() && !mHeadsUpManager.hasPinnedHeadsUp()
                        && !mStatusBar.isBouncerShowing()
                        // pd1610: S5 的 peek 高度≈整块面板，点一下状态栏会「全下来」。
                        // 控制中心只认从上往下滑，所以 S5 不发起 peek。
                        && com.android.systemui.qs.QsStyle.get(getContext())
                                != com.android.systemui.qs.QsStyle.S5) {
                    startOpening(event);
                }
                break;

            case MotionEvent.ACTION_POINTER_UP:
                final int upPointer = event.getPointerId(event.getActionIndex());
                if (mTrackingPointer == upPointer) {
                    // gesture is ongoing, find a new pointer to track
                    final int newIndex = event.getPointerId(0) != upPointer ? 0 : 1;
                    final float newY = event.getY(newIndex);
                    final float newX = event.getX(newIndex);
                    mTrackingPointer = event.getPointerId(newIndex);
                    startExpandMotion(newX, newY, true /* startTracking */, mExpandedHeight);
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (mStatusBarStateController.getState() == StatusBarState.KEYGUARD) {
                    mMotionAborted = true;
                    endMotionEvent(event, x, y, true /* forceCancel */);
                    return false;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                addMovement(event);
                float h = y - mInitialTouchY;
                // pd1610 v93: 不在底栏上的上滑不参与面板拖拽（S5 通知列表只管滚动）。
                // pd1610 v107: 阈值从 -mTouchSlop 放宽到 0 —— 否则头 32 px 的上移
                // 仍会走下面的 setExpandedHeightInternal，面板跟着手上抬一小截。
                if (isS5UpDragBlockedNow() && h < 0) {
                    if (h < -mTouchSlop) {
                        android.util.Log.i("pd1610T", "TMOVE blocked h=" + (int) h);
                    }
                    return true;
                }

                // If the panel was collapsed when touching, we only need to check for the
                // y-component of the gesture, as we have no conflicting horizontal gesture.
                if (Math.abs(h) > mTouchSlop
                        && (Math.abs(h) > Math.abs(x - mInitialTouchX)
                        || mIgnoreXTouchSlop)) {
                    mTouchSlopExceeded = true;
                    if (mGestureWaitForTouchSlop && !mTracking && !mCollapsedAndHeadsUpOnDown) {
                        if (!mJustPeeked && mInitialOffsetOnTouch != 0f) {
                            startExpandMotion(x, y, false /* startTracking */, mExpandedHeight);
                            h = 0;
                        }
                        cancelHeightAnimator();
                        onTrackingStarted();
                    }
                }
                float newHeight = Math.max(0, h + mInitialOffsetOnTouch);
                if (newHeight > mPeekHeight) {
                    if (mPeekAnimator != null) {
                        mPeekAnimator.cancel();
                    }
                    mJustPeeked = false;
                } else if (mPeekAnimator == null && mJustPeeked) {
                    // The initial peek has finished, but we haven't dragged as far yet, lets
                    // speed it up by starting at the peek height.
                    mInitialOffsetOnTouch = mExpandedHeight;
                    mInitialTouchY = y;
                    mMinExpandHeight = mExpandedHeight;
                    mJustPeeked = false;
                }
                newHeight = Math.max(newHeight, mMinExpandHeight);
                if (-h >= getFalsingThreshold()) {
                    mTouchAboveFalsingThreshold = true;
                    mUpwardsWhenTresholdReached = isDirectionUpwards(x, y);
                }
                if (!mJustPeeked && (!mGestureWaitForTouchSlop || mTracking) &&
                        !isTrackingBlocked()) {
                    setExpandedHeightInternal(newHeight);
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                addMovement(event);
                endMotionEvent(event, x, y, false /* forceCancel */);
                break;
        }
        return !mGestureWaitForTouchSlop || mTracking;
    }

    private void startOpening(MotionEvent event) {
        runPeekAnimation(INITIAL_OPENING_PEEK_DURATION, getOpeningHeight(),
                false /* collapseWhenFinished */);
        notifyBarPanelExpansionChanged();
        maybeVibrateOnOpening();

        //TODO: keyguard opens QS a different way; log that too?

        // Log the position of the swipe that opened the panel
        float width = mStatusBar.getDisplayWidth();
        float height = mStatusBar.getDisplayHeight();
        int rot = mStatusBar.getRotation();

        mLockscreenGestureLogger.writeAtFractionalPosition(MetricsEvent.ACTION_PANEL_VIEW_EXPAND,
                (int) (event.getX() / width * 100),
                (int) (event.getY() / height * 100),
                rot);
    }

    protected void maybeVibrateOnOpening() {
        if (mVibrateOnOpening) {
            mVibratorHelper.vibrate(VibrationEffect.EFFECT_TICK);
        }
    }

    protected abstract float getOpeningHeight();

    /**
     * @return whether the swiping direction is upwards and above a 45 degree angle compared to the
     * horizontal direction
     */
    private boolean isDirectionUpwards(float x, float y) {
        float xDiff = x - mInitialTouchX;
        float yDiff = y - mInitialTouchY;
        if (yDiff >= 0) {
            return false;
        }
        return Math.abs(yDiff) >= Math.abs(xDiff);
    }

    protected void startExpandingFromPeek() {
        mStatusBar.handlePeekToExpandTransistion();
    }

    protected void startExpandMotion(float newX, float newY, boolean startTracking,
            float expandedHeight) {
        mInitialOffsetOnTouch = expandedHeight;
        mInitialTouchY = newY;
        mInitialTouchX = newX;
        if (startTracking) {
            mTouchSlopExceeded = true;
            setExpandedHeight(mInitialOffsetOnTouch);
            onTrackingStarted();
        }
    }

    private void endMotionEvent(MotionEvent event, float x, float y, boolean forceCancel) {
        mTrackingPointer = -1;
        // pd1610 v104: 上滑早被拦住了、从头到尾没 startTracking，但下面第一层 if 里的
        //     Math.abs(y - mInitialTouchY) > mTouchSlop
        // 会让它照样进 fling 分支 ⇒ 松手把面板收掉（用户报的「通知下面上滑还能收」）。
        // 这次手势既然不许拖面板，UP 就什么都不做。
        if (useS5PanelPhysics() && isS5UpDragBlockedNow()
                && y - mInitialTouchY < -mTouchSlop) {
            android.util.Log.i("pd1610T", "END skip-fling blocked dy=" + (int) (y - mInitialTouchY)
                    + " tracking=" + mTracking + " frac=" + getExpandedFraction());
            if (mTracking && getExpandedFraction() < 0.99f) {
                // 手指按下去时开/合动画还没跑完 ⇒ tracking 已经开始、面板被拖到半路。
                // 这次上滑不许收，按 S5 规则（松手位置过没过半屏）回到全开。
                fling(0f, true /* expand */, false /* aboveFalsingThreshold */);
            }
            if (mTracking) {
                onTrackingStopped(true /* expand */);
            }
            mVelocityTracker.clear();
            mPeekTouching = false;
            return;
        }
        if ((mTracking && mTouchSlopExceeded)
                || Math.abs(x - mInitialTouchX) > mTouchSlop
                || Math.abs(y - mInitialTouchY) > mTouchSlop
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL
                || forceCancel) {
            mVelocityTracker.computeCurrentVelocity(1000);
            float vel = mVelocityTracker.getYVelocity();
            float vectorVel = (float) Math.hypot(
                    mVelocityTracker.getXVelocity(), mVelocityTracker.getYVelocity());

            // pd1610 v78: 原机 PanelView$3 ACTION_UP 的速度规则。
            // ⚠ jadx 把最后那行反编译成「两个分支都是 0.0f」，别照抄 —— 真身是
            //     if (Math.abs(mFinalTouchY - mInitialTouchY) < 20dp
            //             || v < 100dp/s) { v = 0; }
            // 也就是「两个条件都不成立才保留速度」。smali 0x3c8..0x3da：
            //     cmpg-float |dy|, 20dp ; if-ltz  → v = 0     （拖够 20dp 就清零）
            //     cmpg-float v,   100dp/s ; if-gez → v = 0    （拖得短但不够快也清零）
            // ⇒ 短促快挥按方向开/关；正常拖动纯看松手位置。
            if (useS5PanelPhysics()) {
                final float density = getResources().getDisplayMetrics().density;
                final float minDistPx = 20f * density;    // fling_gesture_min_dist
                final float minVelPx = 100f * density;    // fling_expand_min_velocity
                final float maxOutPx = 3000f * density;   // fling_gesture_max_output_velocity
                // smali 0x3c8..0x3db（if-ltz = 「小于零则跳」）：
                //     |dy| < 20dp        → v = 0     （几乎没动）
                //     |v|  < 100dp/s     → v = 0     （拖得慢）
                //     否则                → 保留 v（夹到 ±3000dp/s）
                if (Math.abs(y - mInitialTouchY) < minDistPx || vectorVel < minVelPx) {
                    vel = 0f;
                } else {
                    vel = Math.max(-maxOutPx, Math.min(maxOutPx, vel));
                }
            }

            android.util.Log.i("pd1610T", "END fling tracking=" + mTracking
                    + " vel=" + (int) vel + " dy=" + (int) (y - mInitialTouchY));
            boolean expand = flingExpands(vel, vectorVel, x, y)
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL
                    || forceCancel;
            DozeLog.traceFling(expand, mTouchAboveFalsingThreshold,
                    mStatusBar.isFalsingThresholdNeeded(),
                    mStatusBar.isWakeUpComingFromTouch());
                    // Log collapse gesture if on lock screen.
                    if (!expand && mStatusBarStateController.getState() == StatusBarState.KEYGUARD) {
                        float displayDensity = mStatusBar.getDisplayDensity();
                        int heightDp = (int) Math.abs((y - mInitialTouchY) / displayDensity);
                        int velocityDp = (int) Math.abs(vel / displayDensity);
                        mLockscreenGestureLogger.write(
                                MetricsEvent.ACTION_LS_UNLOCK,
                                heightDp, velocityDp);
                    }
            fling(vel, expand, isFalseTouch(x, y));
            onTrackingStopped(expand);
            mUpdateFlingOnLayout = expand && mPanelClosedOnDown && !mHasLayoutedSinceDown;
            if (mUpdateFlingOnLayout) {
                mUpdateFlingVelocity = vel;
            }
        } else if (mPanelClosedOnDown && !mHeadsUpManager.hasPinnedHeadsUp() && !mTracking
                && !mStatusBar.isBouncerShowing() && !mKeyguardMonitor.isKeyguardFadingAway()) {
            long timePassed = SystemClock.uptimeMillis() - mDownTime;
            if (com.android.systemui.qs.QsStyle.get(getContext())
                    == com.android.systemui.qs.QsStyle.S5) {
                // pd1610: S5 单击状态栏 = 什么都不做（不 peek、不展开）。
            } else if (timePassed < ViewConfiguration.getLongPressTimeout()) {
                // Lets show the user that he can actually expand the panel
                runPeekAnimation(PEEK_ANIMATION_DURATION, getPeekHeight(), true /* collapseWhenFinished */);
            } else {
                // We need to collapse the panel since we peeked to the small height.
                postOnAnimation(mPostCollapseRunnable);
            }
        } else if (!mStatusBar.isBouncerShowing()) {
            boolean expands = onEmptySpaceClick(mInitialTouchX);
            onTrackingStopped(expands);
        }

        mVelocityTracker.clear();
        mPeekTouching = false;
    }

    protected float getCurrentExpandVelocity() {
        mVelocityTracker.computeCurrentVelocity(1000);
        return mVelocityTracker.getYVelocity();
    }

    private int getFalsingThreshold() {
        float factor = mStatusBar.isWakeUpComingFromTouch() ? 1.5f : 1.0f;
        return (int) (mUnlockFalsingThreshold * factor);
    }

    protected abstract boolean shouldGestureWaitForTouchSlop();

    protected abstract boolean shouldGestureIgnoreXTouchSlop(float x, float y);

    protected void onTrackingStopped(boolean expand) {
        mTracking = false;
        mBar.onTrackingStopped(expand);
        notifyBarPanelExpansionChanged();
    }

    protected void onTrackingStarted() {
        endClosing();
        mTracking = true;
        mBar.onTrackingStarted();
        notifyExpandingStarted();
        notifyBarPanelExpansionChanged();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (mInstantExpanding || !mNotificationsDragEnabled || mTouchDisabled
                || (mMotionAborted && event.getActionMasked() != MotionEvent.ACTION_DOWN)) {
            return false;
        }

        /*
         * If the user drags anywhere inside the panel we intercept it if the movement is
         * upwards. This allows closing the shade from anywhere inside the panel.
         *
         * We only do this if the current content is scrolled to the bottom,
         * i.e isScrolledToBottom() is true and therefore there is no conflicting scrolling gesture
         * possible.
         */
        int pointerIndex = event.findPointerIndex(mTrackingPointer);
        if (pointerIndex < 0) {
            pointerIndex = 0;
            mTrackingPointer = event.getPointerId(pointerIndex);
        }
        final float x = event.getX(pointerIndex);
        final float y = event.getY(pointerIndex);
        boolean scrolledToBottom = isScrolledToBottom();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mStatusBar.userActivity();
                mAnimatingOnDown = mHeightAnimator != null;
                mMinExpandHeight = 0.0f;
                mDownTime = SystemClock.uptimeMillis();
                if (mAnimatingOnDown && mClosing && !mHintAnimationRunning
                        || mPeekAnimator != null) {
                    cancelHeightAnimator();
                    cancelPeek();
                    mTouchSlopExceeded = true;
                    return true;
                }
                mInitialTouchY = y;
                mInitialTouchX = x;
                mTouchStartedInEmptyArea = !isInContentBounds(x, y);
                // pd1610 v93: 全展开后、手指又不在底栏上 ⇒ 这次手势不许拖面板。
                mS5UpDragBlocked = getExpandedFraction() >= 0.5f
                        && isS5TouchOutsideCloseHandle(x, y);
                android.util.Log.i("pd1610T", "DOWN x=" + (int) x + " y=" + (int) y
                        + " frac=" + getExpandedFraction()
                        + " blocked=" + mS5UpDragBlocked
                        + " tracking=" + mTracking
                        + " inEmpty=" + mTouchStartedInEmptyArea);
                mTouchSlopExceeded = mTouchSlopExceededBeforeDown;
                mJustPeeked = false;
                mMotionAborted = false;
                mPanelClosedOnDown = isFullyCollapsed();
                mCollapsedAndHeadsUpOnDown = false;
                mHasLayoutedSinceDown = false;
                mUpdateFlingOnLayout = false;
                mTouchAboveFalsingThreshold = false;
                addMovement(event);
                break;
            case MotionEvent.ACTION_POINTER_UP:
                final int upPointer = event.getPointerId(event.getActionIndex());
                if (mTrackingPointer == upPointer) {
                    // gesture is ongoing, find a new pointer to track
                    final int newIndex = event.getPointerId(0) != upPointer ? 0 : 1;
                    mTrackingPointer = event.getPointerId(newIndex);
                    mInitialTouchX = event.getX(newIndex);
                    mInitialTouchY = event.getY(newIndex);
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (mStatusBarStateController.getState() == StatusBarState.KEYGUARD) {
                    mMotionAborted = true;
                    mVelocityTracker.clear();
                }
                break;
            case MotionEvent.ACTION_MOVE:
                final float h = y - mInitialTouchY;
                addMovement(event);
                android.util.Log.i("pd1610T", "IMOVE h=" + (int) h
                        + " blocked=" + isS5UpDragBlockedNow()
                        + " s2b=" + scrolledToBottom);
                if ((scrolledToBottom || mTouchStartedInEmptyArea || mAnimatingOnDown)
                        && !isS5UpDragBlockedNow()) {
                    float hAbs = Math.abs(h);
                    if ((h < -mTouchSlop || (mAnimatingOnDown && hAbs > mTouchSlop))
                            && hAbs > Math.abs(x - mInitialTouchX)) {
                        cancelHeightAnimator();
                        startExpandMotion(x, y, true /* startTracking */, mExpandedHeight);
                        return true;
                    }
                }
                break;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                mVelocityTracker.clear();
                break;
        }
        return false;
    }

    /**
     * @return Whether a pair of coordinates are inside the visible view content bounds.
     */
    protected abstract boolean isInContentBounds(float x, float y);

    protected void cancelHeightAnimator() {
        if (mHeightAnimator != null) {
            if (mHeightAnimator.isRunning()) {
                mPanelUpdateWhenAnimatorEnds = false;
            }
            mHeightAnimator.cancel();
        }
        endClosing();
    }

    private void endClosing() {
        if (mClosing) {
            mClosing = false;
            onClosingFinished();
        }
    }

    protected boolean isScrolledToBottom() {
        return true;
    }

    protected float getContentHeight() {
        return mExpandedHeight;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        loadDimens();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        loadDimens();
    }

    /**
     * @param vel the current vertical velocity of the motion
     * @param vectorVel the length of the vectorial velocity
     * @return whether a fling should expands the panel; contracts otherwise
     */
    protected boolean flingExpands(float vel, float vectorVel, float x, float y) {
        if (mFalsingManager.isUnlockingDisabled()) {
            return true;
        }

        if (isFalseTouch(x, y)) {
            return true;
        }
        if (Math.abs(vectorVel) < mFlingAnimationUtils.getMinVelocityPxPerSecond()) {
            return shouldExpandWhenNotFlinging();
        } else {
            return vel > 0;
        }
    }

    protected boolean shouldExpandWhenNotFlinging() {
        return getExpandedFraction() > 0.5f;
    }

    /**
     * @param x the final x-coordinate when the finger was lifted
     * @param y the final y-coordinate when the finger was lifted
     * @return whether this motion should be regarded as a false touch
     */
    private boolean isFalseTouch(float x, float y) {
        if (!mStatusBar.isFalsingThresholdNeeded()) {
            return false;
        }
        if (mFalsingManager.isClassiferEnabled()) {
            return mFalsingManager.isFalseTouch();
        }
        if (!mTouchAboveFalsingThreshold) {
            return true;
        }
        if (mUpwardsWhenTresholdReached) {
            return false;
        }
        return !isDirectionUpwards(x, y);
    }

    protected void fling(float vel, boolean expand) {
        fling(vel, expand, 1.0f /* collapseSpeedUpFactor */, false);
    }

    protected void fling(float vel, boolean expand, boolean expandBecauseOfFalsing) {
        fling(vel, expand, 1.0f /* collapseSpeedUpFactor */, expandBecauseOfFalsing);
    }

    protected void fling(float vel, boolean expand, float collapseSpeedUpFactor,
            boolean expandBecauseOfFalsing) {
        cancelPeek();
        float target = expand ? getMaxPanelHeight() : 0;
        if (!expand) {
            mClosing = true;
        }
        flingToHeight(vel, expand, target, collapseSpeedUpFactor, expandBecauseOfFalsing);
    }

    protected void flingToHeight(float vel, boolean expand, float target,
            float collapseSpeedUpFactor, boolean expandBecauseOfFalsing) {
        // Hack to make the expand transition look nice when clear all button is visible - we make
        // the animation only to the last notification, and then jump to the maximum panel height so
        // clear all just fades in and the decelerating motion is towards the last notification.
        final boolean clearAllExpandHack = expand && fullyExpandedClearAllVisible()
                && mExpandedHeight < getMaxPanelHeight() - getClearAllHeight()
                && !isClearAllVisible();
        if (clearAllExpandHack) {
            target = getMaxPanelHeight() - getClearAllHeight();
        }
        if (target == mExpandedHeight || getOverExpansionAmount() > 0f && expand) {
            notifyExpandingFinished();
            return;
        }
        mOverExpandedBeforeFling = getOverExpansionAmount() > 0f;
        ValueAnimator animator = createHeightAnimator(target);
        if (useS5PanelPhysics()) {
            // pd1610 v77: 三星 S5 不用 FlingAnimationUtils 的缓出曲线，见
            // applyS5PanelAnimation() 的注释（原机 PanelView.animationTick）。
            applyS5PanelAnimation(animator, mExpandedHeight, target, vel);
        } else if (expand) {
            if (expandBecauseOfFalsing && vel < 0) {
                vel = 0;
            }
            mFlingAnimationUtils.apply(animator, mExpandedHeight, target, vel, getHeight());
            if (vel == 0) {
                animator.setDuration(350);
            }
        } else {
            if (shouldUseDismissingAnimation()) {
                if (vel == 0) {
                    animator.setInterpolator(Interpolators.PANEL_CLOSE_ACCELERATED);
                    long duration = (long) (200 + mExpandedHeight / getHeight() * 100);
                    animator.setDuration(duration);
                } else {
                    mFlingAnimationUtilsDismissing.apply(animator, mExpandedHeight, target, vel,
                            getHeight());
                }
            } else {
                mFlingAnimationUtilsClosing
                        .apply(animator, mExpandedHeight, target, vel, getHeight());
            }

            // Make it shorter if we run a canned animation
            if (vel == 0) {
                animator.setDuration((long) (animator.getDuration() / collapseSpeedUpFactor));
            }
            if (mFixedDuration != NO_FIXED_DURATION) {
                animator.setDuration(mFixedDuration);
            }
        }
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (clearAllExpandHack && !mCancelled) {
                    setExpandedHeightInternal(getMaxPanelHeight());
                }
                setAnimator(null);
                if (!mCancelled) {
                    notifyExpandingFinished();
                }
                notifyBarPanelExpansionChanged();
            }
        });
        setAnimator(animator);
        animator.start();
    }

    /**
     * pd1610 v77: S5 控制中心用自家动画曲线。只有 NotificationPanelView 在
     * 「S5 样式 + 解锁后的控制中心」两个条件都满足时返回 true。
     */
    protected boolean useS5PanelPhysics() {
        return false;
    }

    /**
     * pd1610 v77: 复刻三星 S5 PanelView.animationTick() 的「恒加速度」模型。
     *
     * 原机（G9008V 官方 SystemUI 反编译，PanelView.java:193 附近）：
     *     mAccel = mClosing ? -mCollapseAccelPx : mExpandAccelPx;   // ±2000dp/s²
     *     mVel  += mAccel * dt;
     *     h     += mVel * dt;
     * 而且手势速度在 ACTION_UP 里两条分支都赋 0f（PanelView$3.onTouch），
     * 等于每次都从静止起步 ⇒ 不是原生那种「先快后慢」的缓出，而是「越走越快」。
     *
     * 从静止、恒加速走完 d px：T = sqrt(2d/a)，h(t) = h0 ± d*(t/T)²
     * ⇒ 插值器就是 t²，时长由 S5 的加速度反推。
     */
    protected void applyS5PanelAnimation(ValueAnimator animator, float from, float to,
            float velocityPxPerSecond) {
        // 原机 PanelView.animationTick() 的积分：
        //     mAccel = mClosing ? -collapse_accel : +expand_accel;   // ±2000dp/s²
        //     mVel  += mAccel * dt;
        //     展开: mVel = min(mVel, +fling_gesture_max_output_velocity 3000dp/s);
        //     收起: mVel = min(mVel, -fling_collapse_min_velocity 100dp/s);
        //     mExpandedHeight += mVel * dt;                          // 到顶/到底即停
        // 解析形式就是 h(t) = h0 + v0·t + ½·a·t²。v0 的来源：
        //   · 点开/点关 = ±self_expand_velocity / self_collapse_velocity（2000dp/s）；
        //   · 手势拖动 ⇒ 0（原机 PanelView$3 ACTION_UP 里拖够 20dp 就把速度清零），
        //     只有 20dp 内的短促快挥才带着速度进来（见 endMotionEvent 的 S5 分支）。
        final float density = getResources().getDisplayMetrics().density;
        final boolean expanding = to > from;
        final float dist = Math.abs(to - from);
        final float accel = 2000f * density;              // 2000dp/s²，永远朝目标方向
        final float maxOut = 3000f * density;             // fling_gesture_max_output_velocity
        final float minCollapse = 100f * density;         // fling_collapse_min_velocity
        final float v0 = expanding
                ? Math.max(0f, Math.min(velocityPxPerSecond, maxOut))
                : Math.min(0f, Math.min(velocityPxPerSecond, -minCollapse));
        if (dist <= 0f) {
            animator.setDuration(1);
            return;
        }
        final float speed = Math.abs(v0);
        // dist = speed·T + ½·accel·T²  ⇒  T = (-speed + √(speed² + 2·accel·dist)) / accel
        final float seconds = (float) ((-speed
                + Math.sqrt(speed * speed + 2f * accel * dist)) / accel);
        final float total = Math.max(0.001f, seconds);
        animator.setInterpolator(f -> {
            // pd1610 v81: 端点必须精确。
            // f=1 时 travelled/dist 因浮点尾差可能给 0.99999994 ⇒ 动画值
            // 起点×(1-0.99999994) ≈ 1e-4 ≠ 0 ⇒ 撞上 setExpandedHeightInternal()
            // 里的「几乎到 0 就 end()」判据 ⇒ end() 又回灌本监听器 ⇒ 爆栈。
            if (f >= 1f) {
                return 1f;
            }
            if (f <= 0f) {
                return 0f;
            }
            final float t = f * total;
            final float travelled = speed * t + 0.5f * accel * t * t;
            return Math.min(1f, Math.max(0f, travelled / dist));
        });
        animator.setDuration(Math.max(1L, (long) (total * 1000f)));
    }

    /** pd1610 v78: S5 的自展开/自收起速度（self_expand_velocity = self_collapse_velocity = 2000dp/s）。 */
    private float getS5SelfVelocity() {
        return useS5PanelPhysics()
                ? 2000f * getResources().getDisplayMetrics().density : 0f;
    }

    protected abstract boolean shouldUseDismissingAnimation();

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mViewName = getResources().getResourceName(getId());
    }

    public String getName() {
        return mViewName;
    }

    public void setExpandedHeight(float height) {
        if (DEBUG) logf("setExpandedHeight(%.1f)", height);
        setExpandedHeightInternal(height + getOverExpansionPixels());
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        mStatusBar.onPanelLaidOut();
        requestPanelHeightUpdate();
        mHasLayoutedSinceDown = true;
        if (mUpdateFlingOnLayout) {
            abortAnimations();
            fling(mUpdateFlingVelocity, true /* expands */);
            mUpdateFlingOnLayout = false;
        }
    }

    protected void requestPanelHeightUpdate() {
        float currentMaxPanelHeight = getMaxPanelHeight();

        if (isFullyCollapsed()) {
            return;
        }

        if (currentMaxPanelHeight == mExpandedHeight) {
            return;
        }

        if (mPeekAnimator != null || mPeekTouching) {
            return;
        }

        if (mTracking && !isTrackingBlocked()) {
            return;
        }

        if (mHeightAnimator != null) {
            mPanelUpdateWhenAnimatorEnds = true;
            return;
        }

        setExpandedHeight(currentMaxPanelHeight);
    }

    /** pd1610 v81: mHeightAnimator.end() 的重入闸（见 setExpandedHeightInternal）。 */
    private boolean mS5EndingHeightAnimator;

    public void setExpandedHeightInternal(float h) {
        if (mExpandLatencyTracking && h != 0f) {
            DejankUtils.postAfterTraversal(() -> LatencyTracker.getInstance(mContext).onActionEnd(
                    LatencyTracker.ACTION_EXPAND_PANEL));
            mExpandLatencyTracking = false;
        }
        float fhWithoutOverExpansion = getMaxPanelHeight() - getOverExpansionAmount();
        if (mHeightAnimator == null) {
            float overExpansionPixels = Math.max(0, h - fhWithoutOverExpansion);
            if (getOverExpansionPixels() != overExpansionPixels && mTracking) {
                setOverExpansion(overExpansionPixels, true /* isPixels */);
            }
            mExpandedHeight = Math.min(h, fhWithoutOverExpansion) + getOverExpansionAmount();
        } else {
            mExpandedHeight = h;
            if (mOverExpandedBeforeFling) {
                setOverExpansion(Math.max(0, h - fhWithoutOverExpansion), false /* isPixels */);
            }
        }

        // If we are closing the panel and we are almost there due to a slow decelerating
        // interpolator, abort the animation.
        // pd1610 v81: 这段会重入 —— end() → animateValue(1f) → 本动画的监听器 →
        // 再进本方法。只要那一帧的动画值还落在 (0,1)，就会无限套下去
        // （StackOverflow / ANR，实测「上滑收控制中心」必现）。插值器端点已修，
        // 这里再加一道硬闸：end() 期间再进来只把高度归零，不再 end()。
        if (mExpandedHeight < 1f && mExpandedHeight != 0f && mClosing) {
            mExpandedHeight = 0f;
            if (mHeightAnimator != null && !mS5EndingHeightAnimator) {
                mS5EndingHeightAnimator = true;
                try {
                    mHeightAnimator.end();
                } finally {
                    mS5EndingHeightAnimator = false;
                }
            }
        }
        mExpandedFraction = Math.min(1f,
                fhWithoutOverExpansion == 0 ? 0 : mExpandedHeight / fhWithoutOverExpansion);
        onHeightUpdated(mExpandedHeight);
        notifyBarPanelExpansionChanged();
    }

    /**
     * @return true if the panel tracking should be temporarily blocked; this is used when a
     *         conflicting gesture (opening QS) is happening
     */
    protected abstract boolean isTrackingBlocked();

    protected abstract void setOverExpansion(float overExpansion, boolean isPixels);

    protected abstract void onHeightUpdated(float expandedHeight);

    protected abstract float getOverExpansionAmount();

    protected abstract float getOverExpansionPixels();

    /**
     * This returns the maximum height of the panel. Children should override this if their
     * desired height is not the full height.
     *
     * @return the default implementation simply returns the maximum height.
     */
    protected abstract int getMaxPanelHeight();

    public void setExpandedFraction(float frac) {
        setExpandedHeight(getMaxPanelHeight() * frac);
    }

    public float getExpandedHeight() {
        return mExpandedHeight;
    }

    public float getExpandedFraction() {
        return mExpandedFraction;
    }

    public boolean isFullyExpanded() {
        return mExpandedHeight >= getMaxPanelHeight();
    }

    public boolean isFullyCollapsed() {
        return mExpandedFraction <= 0.0f;
    }

    public boolean isCollapsing() {
        return mClosing || mLaunchingNotification;
    }

    public boolean isTracking() {
        return mTracking;
    }

    public void setBar(PanelBar panelBar) {
        mBar = panelBar;
    }

    public void collapse(boolean delayed, float speedUpFactor) {
        if (DEBUG) logf("collapse: " + this);
        if (canPanelBeCollapsed()) {
            cancelHeightAnimator();
            notifyExpandingStarted();

            // Set after notifyExpandingStarted, as notifyExpandingStarted resets the closing state.
            mClosing = true;
            if (delayed) {
                mNextCollapseSpeedUpFactor = speedUpFactor;
                postDelayed(mFlingCollapseRunnable, 120);
            } else {
                fling(-getS5SelfVelocity(), false /* expand */, speedUpFactor,
                        false /* expandBecauseOfFalsing */);
            }
        }
    }

    public boolean canPanelBeCollapsed() {
        return !isFullyCollapsed() && !mTracking && !mClosing;
    }

    private final Runnable mFlingCollapseRunnable = new Runnable() {
        @Override
        public void run() {
            fling(-getS5SelfVelocity(), false /* expand */, mNextCollapseSpeedUpFactor,
                    false /* expandBecauseOfFalsing */);
        }
    };

    public void cancelPeek() {
        boolean cancelled = false;
        if (mPeekAnimator != null) {
            cancelled = true;
            mPeekAnimator.cancel();
        }

        if (cancelled) {
            // When peeking, we already tell mBar that we expanded ourselves. Make sure that we also
            // notify mBar that we might have closed ourselves.
            notifyBarPanelExpansionChanged();
        }
    }

    public void expand(final boolean animate) {
        if (!isFullyCollapsed() && !isCollapsing()) {
            return;
        }

        mInstantExpanding = true;
        mAnimateAfterExpanding = animate;
        mUpdateFlingOnLayout = false;
        abortAnimations();
        cancelPeek();
        if (mTracking) {
            onTrackingStopped(true /* expands */); // The panel is expanded after this call.
        }
        if (mExpanding) {
            notifyExpandingFinished();
        }
        notifyBarPanelExpansionChanged();

        // Wait for window manager to pickup the change, so we know the maximum height of the panel
        // then.
        getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        if (!mInstantExpanding) {
                            getViewTreeObserver().removeOnGlobalLayoutListener(this);
                            return;
                        }
                        if (mStatusBar.getStatusBarWindow().getHeight()
                                != mStatusBar.getStatusBarHeight()) {
                            getViewTreeObserver().removeOnGlobalLayoutListener(this);
                            if (mAnimateAfterExpanding) {
                                notifyExpandingStarted();
                                fling(getS5SelfVelocity(), true /* expand */);
                            } else {
                                setExpandedFraction(1f);
                            }
                            mInstantExpanding = false;
                        }
                    }
                });

        // Make sure a layout really happens.
        requestLayout();
    }

    public void instantCollapse() {
        abortAnimations();
        setExpandedFraction(0f);
        if (mExpanding) {
            notifyExpandingFinished();
        }
        if (mInstantExpanding) {
            mInstantExpanding = false;
            notifyBarPanelExpansionChanged();
        }
    }

    private void abortAnimations() {
        cancelPeek();
        cancelHeightAnimator();
        removeCallbacks(mPostCollapseRunnable);
        removeCallbacks(mFlingCollapseRunnable);
    }

    protected void onClosingFinished() {
        mBar.onClosingFinished();
    }


    protected void startUnlockHintAnimation() {

        // We don't need to hint the user if an animation is already running or the user is changing
        // the expansion.
        if (mHeightAnimator != null || mTracking) {
            return;
        }
        cancelPeek();
        notifyExpandingStarted();
        startUnlockHintAnimationPhase1(() -> {
            notifyExpandingFinished();
            onUnlockHintFinished();
            mHintAnimationRunning = false;
        });
        onUnlockHintStarted();
        mHintAnimationRunning = true;
    }

    protected void onUnlockHintFinished() {
        mStatusBar.onHintFinished();
    }

    protected void onUnlockHintStarted() {
        mStatusBar.onUnlockHintStarted();
    }

    public boolean isUnlockHintRunning() {
        return mHintAnimationRunning;
    }

    /**
     * Phase 1: Move everything upwards.
     */
    private void startUnlockHintAnimationPhase1(final Runnable onAnimationFinished) {
        float target = Math.max(0, getMaxPanelHeight() - mHintDistance);
        ValueAnimator animator = createHeightAnimator(target);
        animator.setDuration(250);
        animator.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (mCancelled) {
                    setAnimator(null);
                    onAnimationFinished.run();
                } else {
                    startUnlockHintAnimationPhase2(onAnimationFinished);
                }
            }
        });
        animator.start();
        setAnimator(animator);

        View[] viewsToAnimate = {
                mKeyguardBottomArea.getIndicationArea(),
                mStatusBar.getAmbientIndicationContainer()};
        for (View v : viewsToAnimate) {
            if (v == null) {
                continue;
            }
            v.animate()
                    .translationY(-mHintDistance)
                    .setDuration(250)
                    .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                    .withEndAction(() -> v.animate()
                            .translationY(0)
                            .setDuration(450)
                            .setInterpolator(mBounceInterpolator)
                            .start())
                    .start();
        }
    }

    private void setAnimator(ValueAnimator animator) {
        mHeightAnimator = animator;
        if (animator == null && mPanelUpdateWhenAnimatorEnds) {
            mPanelUpdateWhenAnimatorEnds = false;
            requestPanelHeightUpdate();
        }
    }

    /**
     * Phase 2: Bounce down.
     */
    private void startUnlockHintAnimationPhase2(final Runnable onAnimationFinished) {
        ValueAnimator animator = createHeightAnimator(getMaxPanelHeight());
        animator.setDuration(450);
        animator.setInterpolator(mBounceInterpolator);
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                setAnimator(null);
                onAnimationFinished.run();
                notifyBarPanelExpansionChanged();
            }
        });
        animator.start();
        setAnimator(animator);
    }

    private ValueAnimator createHeightAnimator(float targetHeight) {
        ValueAnimator animator = ValueAnimator.ofFloat(mExpandedHeight, targetHeight);
        animator.addUpdateListener(
                animation -> setExpandedHeightInternal((float) animation.getAnimatedValue()));
        return animator;
    }

    protected void notifyBarPanelExpansionChanged() {
        if (mBar != null) {
            mBar.panelExpansionChanged(mExpandedFraction, mExpandedFraction > 0f
                    || mPeekAnimator != null || mInstantExpanding
                    || isPanelVisibleBecauseOfHeadsUp() || mTracking || mHeightAnimator != null);
        }
        for (int i = 0; i < mExpansionListeners.size(); i++) {
            mExpansionListeners.get(i).onPanelExpansionChanged(mExpandedFraction, mTracking);
        }
    }

    public void addExpansionListener(PanelExpansionListener panelExpansionListener) {
        mExpansionListeners.add(panelExpansionListener);
    }

    protected abstract boolean isPanelVisibleBecauseOfHeadsUp();

    /**
     * Gets called when the user performs a click anywhere in the empty area of the panel.
     *
     * @return whether the panel will be expanded after the action performed by this method
     */
    protected boolean onEmptySpaceClick(float x) {
        if (mHintAnimationRunning) {
            return true;
        }
        return onMiddleClicked();
    }

    protected final Runnable mPostCollapseRunnable = new Runnable() {
        @Override
        public void run() {
            collapse(false /* delayed */, 1.0f /* speedUpFactor */);
        }
    };

    protected abstract boolean onMiddleClicked();

    protected abstract boolean isDozing();

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println(String.format("[PanelView(%s): expandedHeight=%f maxPanelHeight=%d closing=%s"
                + " tracking=%s justPeeked=%s peekAnim=%s%s timeAnim=%s%s touchDisabled=%s"
                + "]",
                this.getClass().getSimpleName(),
                getExpandedHeight(),
                getMaxPanelHeight(),
                mClosing?"T":"f",
                mTracking?"T":"f",
                mJustPeeked?"T":"f",
                mPeekAnimator, ((mPeekAnimator!=null && mPeekAnimator.isStarted())?" (started)":""),
                mHeightAnimator, ((mHeightAnimator !=null && mHeightAnimator.isStarted())?" (started)":""),
                mTouchDisabled?"T":"f"
        ));
    }

    public abstract void resetViews(boolean animate);

    protected abstract float getPeekHeight();
    /**
     * @return whether "Clear all" button will be visible when the panel is fully expanded
     */
    protected abstract boolean fullyExpandedClearAllVisible();

    protected abstract boolean isClearAllVisible();

    /**
     * @return the height of the clear all button, in pixels
     */
    protected abstract int getClearAllHeight();

    public void setHeadsUpManager(HeadsUpManagerPhone headsUpManager) {
        mHeadsUpManager = headsUpManager;
    }

    public void setLaunchingNotification(boolean launchingNotification) {
        mLaunchingNotification = launchingNotification;
    }

    public void collapseWithDuration(int animationDuration) {
        mFixedDuration = animationDuration;
        collapse(false /* delayed */, 1.0f /* speedUpFactor */);
        mFixedDuration = NO_FIXED_DURATION;
    }
}
