package com.merxury.sharepanel;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;

/**
 * Non-exported content provider used exclusively as a secure bootstrap channel
 * for receiving the privileged broker Binder from the Root daemon.
 */
public final class CallerBridgeProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        return handleCall(method, extras);
    }

    @Override
    public Bundle call(String authority, String method, String arg, Bundle extras) {
        return handleCall(method, extras);
    }

    private Bundle handleCall(String method, Bundle extras) {
        if (!CallerBridgeClient.METHOD_ATTACH.equals(method)) {
            throw new IllegalArgumentException("Unknown method: " + method);
        }

        int callingUid = Binder.getCallingUid();
        // Only accept attach requests from ROOT_UID (0) or SYSTEM_UID (1000)
        if (callingUid != 0 && callingUid != 1000) {
            throw new SecurityException("Unauthorized UID: " + callingUid);
        }

        if (extras == null) {
            throw new IllegalArgumentException("Extras bundle cannot be null");
        }

        IBinder binder = extras.getBinder(CallerBridgeClient.KEY_BINDER);
        if (binder == null || !binder.isBinderAlive()) {
            throw new IllegalArgumentException("Valid live binder required in extras");
        }

        CallerBridgeClient.attach(binder);

        Bundle response = new Bundle();
        response.putBoolean("attached", true);
        return response;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        throw new UnsupportedOperationException("Only bridge attachment is supported");
    }

    @Override
    public String getType(Uri uri) {
        throw new UnsupportedOperationException("Only bridge attachment is supported");
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Only bridge attachment is supported");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Only bridge attachment is supported");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Only bridge attachment is supported");
    }
}
