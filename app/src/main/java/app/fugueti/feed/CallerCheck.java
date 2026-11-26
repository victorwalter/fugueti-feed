/*
 * Fugueti Feed: bridge between Fugueti and the Google app's feed (Google Discover).
 * Copyright (C) 2026 Fugueti Launcher contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package app.fugueti.feed;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.os.Process;
import android.util.SparseBooleanArray;

import java.security.MessageDigest;
import java.util.Locale;

/**
 * Decides who may use the bridge: Fugueti packages ({@code app.fugueti.launcher*}) signed with the
 * same key as this app, or the Google Play version ({@link #PLAY_PACKAGE}) signed by Google Play.
 * Results are cached per uid.
 */
final class CallerCheck {
    private static final String LAUNCHER_PREFIX = "app.fugueti.launcher";
    static final String PLAY_PACKAGE = "app.fugueti.launcher.play";

    /**
     * SHA-256 of the certificates Google Play signs Fugueti's Play version with (Play App Signing,
     * Play Console → App signing). Play signs with key rotation ("quantum-ready"), so the signer
     * Android reports depends on the device: all three are accepted.
     */
    private static final String[] PLAY_CERT_SHA256 = {
            // deployment_cert.der (RSA 4096)
            "55147fd98baddf10772a8149598427f4fd9dd6029125a7b5a8251f20f87f8d09",
            // hybrid_classical_cert.der (RSA 4096)
            "aabed666046d53f842b7bbdb074bc0d9e25ebb8bde24d0df87667197ac8aeb65",
            // hybrid_pqc_cert.der (ML-DSA-65)
            "806b14b894fe4bdb0c078bb974c66efb6b1bf51e0584f7ce02d7f8f3e9f732fd",
    };

    private final Context mContext;
    private final SparseBooleanArray mUidCache = new SparseBooleanArray();

    CallerCheck(Context context) {
        mContext = context.getApplicationContext();
    }

    boolean isAllowedPackage(String packageName) {
        if (packageName == null || !packageName.startsWith(LAUNCHER_PREFIX)) return false;
        PackageManager pm = mContext.getPackageManager();
        if (pm.checkSignatures(mContext.getPackageName(), packageName)
                == PackageManager.SIGNATURE_MATCH) {
            return true;
        }
        return PLAY_PACKAGE.equals(packageName) && isSignedByPlay(pm, packageName);
    }

    /** True when any certificate of the package (current signers or rotation history) is Play's. */
    private static boolean isSignedByPlay(PackageManager pm, String packageName) {
        Signature[] signatures;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageInfo info = pm.getPackageInfo(packageName,
                        PackageManager.GET_SIGNING_CERTIFICATES);
                SigningInfo signing = info.signingInfo;
                if (signing == null) return false;
                signatures = signing.hasMultipleSigners()
                        ? signing.getApkContentsSigners()
                        : signing.getSigningCertificateHistory();
            } else {
                @SuppressWarnings("deprecation")
                PackageInfo info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES);
                signatures = info.signatures;
            }
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
        if (signatures == null) return false;
        for (Signature signature : signatures) {
            String digest = sha256(signature.toByteArray());
            for (String allowed : PLAY_CERT_SHA256) {
                if (allowed.equals(digest)) return true;
            }
        }
        return false;
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format(Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
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
