/*
 * Fugueti Feed: bridge between Fugueti and the Google app's feed (Google Discover).
 * Copyright (C) 2026 Fugueti Launcher contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package app.fugueti.feed;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * Handed to the launcher in place of the Google app's overlay binder. Every call is re-sent from
 * this process, so the Google app keeps talking to its accepted client; calls from anyone other
 * than an allowed Fugueti build are rejected.
 */
final class ForwardingBinder extends Binder {
    private final IBinder mTarget;
    private final CallerCheck mCallers;

    ForwardingBinder(IBinder target, CallerCheck callers) {
        mTarget = target;
        mCallers = callers;
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        if (!mCallers.isAllowedUid(getCallingUid())) {
            throw new SecurityException("Caller is not an allowed Fugueti build");
        }
        return mTarget.transact(code, data, reply, flags);
    }
}
