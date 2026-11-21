/*
 * Fugueti Feed: bridge between Fugueti and the Google app's feed (Google Discover).
 * Copyright (C) 2026 Fugueti Launcher contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package app.fugueti.feed;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Process;
import android.util.SparseBooleanArray;

/**
 * Decides who may use the bridge: Fugueti packages ({@code app.fugueti.launcher*}) signed with the
 * same key as this app. Results are cached per uid.
 */
final class CallerCheck {
    private static final String LAUNCHER_PREFIX = "app.fugueti.launcher";

    private final Context mContext;
    private final SparseBooleanArray mUidCache = new SparseBooleanArray();

    CallerCheck(Context context) {
        mContext = context.getApplicationContext();
    }

    boolean isAllowedPackage(String packageName) {
        if (packageName == null || !packageName.startsWith(LAUNCHER_PREFIX)) return false;
        PackageManager pm = mContext.getPackageManager();
        return pm.checkSignatures(mContext.getPackageName(), packageName)
                == PackageManager.SIGNATURE_MATCH;
    }

    boolean isAllowedUid(int uid) {
        if (uid == Process.myUid()) return true;
        synchronized (mUidCache) {
            int index = mUidCache.indexOfKey(uid);
            if (index >= 0) return mUidCache.valueAt(index);
        }
        boolean allowed = false;
        String[] packages = mContext.getPackageManager().getPackagesForUid(uid);
        if (packages != null) {
            for (String pkg : packages) {
                if (isAllowedPackage(pkg)) {
                    allowed = true;
                    break;
                }
            }
        }
        synchronized (mUidCache) {
            mUidCache.put(uid, allowed);
        }
        return allowed;
    }
}
