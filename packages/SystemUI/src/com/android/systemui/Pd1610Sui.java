/*
 * Copyright (C) 2026 The LineageOS Project
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

package com.android.systemui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.text.TextUtils;

/**
 * pd1610: SUI 设置（只有 UI 切换＝三星 Galaxy S5 时才露出）里的可自定义项。
 * 设置值统一走 Settings.System，键名和 LineageParts 那边一一对应。
 * 自定义图片由 LineageParts 拷到 /data/system/pd1610sui/&lt;key&gt;.png，
 * 这里只按路径读（两个 App 的 uid 不同，所以文件是 0644、目录 0755）。
 */
public final class Pd1610Sui {
    /** 控制中心顶图（亮度条以上） */
    public static final String KEY_QS_IMAGE = "pd1610_sui_qs_image";
    /** 通知区背景色，0＝不覆盖 */
    public static final String KEY_NOTIF_BG = "pd1610_sui_notif_bg";
    /** 亮度条背景色，0＝不覆盖 */
    public static final String KEY_BRIGHT_BG = "pd1610_sui_bright_bg";
    /** 设置界面底图 */
    public static final String KEY_SETTINGS_IMAGE = "pd1610_sui_settings_image";

    private Pd1610Sui() {
    }

    public static String getString(Context context, String key) {
        try {
            return Settings.System.getString(context.getContentResolver(), key);
        } catch (Throwable t) {
            return null;
        }
    }

    public static int getColor(Context context, String key) {
        try {
            return Settings.System.getInt(context.getContentResolver(), key, 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 按路径读图并按需降采样，失败/没设都返回 null。 */
    public static Drawable loadImage(Context context, String path) {
        Bitmap bmp = loadBitmap(path);
        if (bmp == null) {
            return null;
        }
        return new BitmapDrawable(context.getResources(), bmp);
    }

    public static Bitmap loadBitmap(String path) {
        if (TextUtils.isEmpty(path)) {
            return null;
        }
        try {
            final int max = 2048;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= max
                    && bounds.outHeight / (sample * 2) >= max) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            return BitmapFactory.decodeFile(path, opts);
        } catch (Throwable t) {
            return null;
        }
    }
}
