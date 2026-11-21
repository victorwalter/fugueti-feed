/*
 * Fugueti Feed: bridge between Fugueti and the Google app's feed (Google Discover).
 * Copyright (C) 2026 Fugueti Launcher contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package app.fugueti.feed;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import amirz.aidlbridge.IBridge;
import amirz.aidlbridge.IBridgeCallback;

/**
 * The Google app only hands its feed overlay to launchers that are debuggable (or system apps).
 * Release builds of Fugueti are neither, so Fugueti binds this small debuggable app instead
 * (the "bridge" protocol of {@code LauncherClientBridge}):
 *
 * <ol>
 *   <li>Fugueti binds {@code WINDOW_OVERLAY} here with {@code app://<launcher>:<uid>} as data.</li>
 *   <li>We answer with an {@link IBridge}; Fugueti calls {@link IBridge#bindService}.</li>
 *   <li>We bind the Google app's overlay service as ourselves and hand Fugueti a binder that
 *       forwards every call to it, so the Google app always sees this app as its client.</li>
 * </ol>
 *
 * Only Fugueti builds signed with the same key as this app may use it.
 */
public class FeedBridgeService extends Service {
    private static final String TAG = "FuguetiFeed";
    static final String GOOGLE_PACKAGE = "com.google.android.googlequicksearchbox";
    static final String OVERLAY_ACTION = "com.android.launcher3.WINDOW_OVERLAY";

    /** One session per distinct bind intent (launcher package/uid/version). */
    private final Map<Intent.FilterComparison, Session> mSessions = new HashMap<>();
    private CallerCheck mCallers;

    @Override
    public void onCreate() {
        super.onCreate();
        mCallers = new CallerCheck(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        Uri data = intent.getData();
        String launcher = data == null ? null : packageOf(data);
        if (launcher == null || !mCallers.isAllowedPackage(launcher)) {
            Log.w(TAG, "Refusing bind from " + launcher);
            return null;
        }
        Intent.FilterComparison key = new Intent.FilterComparison(intent);
        synchronized (mSessions) {
            Session session = mSessions.get(key);
            if (session == null) {
                session = new Session(data, launcher);
                mSessions.put(key, session);
            }
            return session;
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // Every client of this intent is gone: drop its Google connections. Keep the session
        // itself, because Android hands the same cached binder to the next bind of this intent.
        Session session;
        synchronized (mSessions) {
            session = mSessions.get(new Intent.FilterComparison(intent));
        }
        if (session != null) session.disconnect();
        return false;
    }

    @Override
    public void onDestroy() {
        synchronized (mSessions) {
            for (Session session : mSessions.values()) session.disconnect();
            mSessions.clear();
        }
        super.onDestroy();
    }

    /** {@code app://pkg:uid?...} → {@code pkg}. */
    private static String packageOf(Uri data) {
        String authority = data.getEncodedAuthority();
        if (authority == null || authority.isEmpty()) return null;
        int colon = authority.indexOf(':');
        return colon < 0 ? authority : authority.substring(0, colon);
    }

    private final class Session extends IBridge.Stub {
        private final String mLauncher;
        private final Intent mGoogleIntent;
        /**
         * One Google connection per launcher callback. The launcher keeps two connections open at
         * the same time (an app-wide one and the overlay one, both with this same intent), so a new
         * bindService() must never tear down the other one: only a repeated call with the same
         * callback replaces its own previous connection.
         */
        private final Map<IBinder, Link> mLinks = new HashMap<>();

        Session(Uri launcherData, String launcher) {
            mLauncher = launcher;
            // Same request the launcher would send, but on behalf of this app: the Google app
            // checks the client named in the data against the app that actually binds it.
            String self = getPackageName() + ":" + Process.myUid();
            mGoogleIntent = new Intent(OVERLAY_ACTION)
                    .setPackage(GOOGLE_PACKAGE)
                    .setData(launcherData.buildUpon().encodedAuthority(self).build());
        }

        @Override
        public void bindService(IBridgeCallback callback, int flags) {
            if (!mCallers.isAllowedUid(Binder.getCallingUid())) {
                Log.w(TAG, "Refusing bindService from uid " + Binder.getCallingUid());
                return;
            }
            IBinder key = callback.asBinder();
            Link previous;
            synchronized (mLinks) {
                previous = mLinks.remove(key);
            }
            if (previous != null) previous.close(false);

            Link link = new Link(callback);
            boolean bound;
            try {
                bound = FeedBridgeService.this.bindService(mGoogleIntent, link, flags);
            } catch (SecurityException e) {
                Log.w(TAG, "Google app refused the feed connection", e);
                bound = false;
            }
            if (!bound) {
                Log.w(TAG, "Could not bind the Google app's feed for " + mLauncher);
                return;
            }
            try {
                // If the launcher process dies, drop its Google connection too.
                key.linkToDeath(link, 0);
            } catch (RemoteException e) {
                link.close(false);
                return;
            }
            synchronized (mLinks) {
                mLinks.put(key, link);
            }
            Log.i(TAG, "Feed connected for " + mLauncher + " (" + mLinks.size() + " link(s))");
        }

        void disconnect() {
            List<Link> links;
            synchronized (mLinks) {
                links = new ArrayList<>(mLinks.values());
                mLinks.clear();
            }
            for (Link link : links) link.close(true);
        }

        /** A Google overlay connection made for one launcher callback. */
        private final class Link implements ServiceConnection, IBinder.DeathRecipient {
            private final IBridgeCallback mCallback;
            private boolean mConnected;
            private boolean mClosed;

            Link(IBridgeCallback callback) {
                mCallback = callback;
            }

            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                if (mClosed) return;
                mConnected = true;
                try {
                    mCallback.onServiceConnected(name, new ForwardingBinder(service, mCallers));
                } catch (RemoteException e) {
                    Log.w(TAG, "Launcher went away", e);
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                // The Google app died or restarted; bindService() reconnects by itself, so just
                // tell the launcher, which will get onServiceConnected() again.
                if (!mConnected) return;
                mConnected = false;
                try {
                    mCallback.onServiceDisconnected(name);
                } catch (RemoteException e) {
                    Log.w(TAG, "Launcher went away", e);
                }
            }

            @Override
            public void binderDied() {
                synchronized (mLinks) {
                    mLinks.remove(mCallback.asBinder());
                }
                close(false);
            }

            /** Unbinds from the Google app; tells the launcher only when asked to. */
            void close(boolean notifyLauncher) {
                if (mClosed) return;
                mClosed = true;
                try {
                    mCallback.asBinder().unlinkToDeath(this, 0);
                } catch (RuntimeException ignored) {
                    // not linked
                }
                try {
                    unbindService(this);
                } catch (IllegalArgumentException ignored) {
                    // already unbound
                }
                if (notifyLauncher && mConnected) {
                    mConnected = false;
                    try {
                        mCallback.onServiceDisconnected(null);
                    } catch (RemoteException ignored) {
                        // launcher is gone
                    }
                }
            }
        }
    }
}
