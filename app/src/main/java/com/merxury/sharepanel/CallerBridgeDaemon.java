package com.merxury.sharepanel;

import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.AttributionSource;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static com.merxury.sharepanel.CallerBridgeClient.AUTHORITY;
import static com.merxury.sharepanel.CallerBridgeClient.DESCRIPTOR;
import static com.merxury.sharepanel.CallerBridgeClient.KEY_BINDER;
import static com.merxury.sharepanel.CallerBridgeClient.METHOD_ATTACH;
import static com.merxury.sharepanel.CallerBridgeClient.SOCKET;
import static com.merxury.sharepanel.CallerBridgeClient.TRANSACTION_START;

/** Per-boot Root broker using Android 16's original-caller activity launch API. */
@TargetApi(36)
public final class CallerBridgeDaemon {
    private static final String PACKAGE_NAME = "com.merxury.sharepanel";
    private final int appUid;
    private final Object activityManager;
    private final Object taskManager;
    private final Method getProvider;
    private final Method removeProvider;
    private final Field providerBinder;
    private final Method providerCall;
    private final Method startAsCaller;
    private final Method isStartSuccessful;
    private final AttributionSource attribution;

    private CallerBridgeDaemon() throws Exception {
        if (Process.myUid() != 0) {
            throw new SecurityException("Caller bridge must be activated with Root");
        }
        Object packageManager = invoke(Class.forName("android.app.AppGlobals")
                .getMethod("getPackageManager"), null);
        ApplicationInfo app = (ApplicationInfo) invoke(Class.forName("android.content.pm.IPackageManager")
                .getMethod("getApplicationInfo", String.class, long.class, int.class),
                packageManager, PACKAGE_NAME, 0L, 0);
        if (app == null) throw new IllegalStateException("Share panel is not installed for user 0");
        appUid = app.uid;
        activityManager = invoke(ActivityManager.class.getMethod("getService"), null);
        taskManager = invoke(Class.forName("android.app.ActivityTaskManager").getMethod("getService"), null);
        Class<?> amApi = Class.forName("android.app.IActivityManager");
        getProvider = amApi.getMethod("getContentProviderExternal",
                String.class, int.class, IBinder.class, String.class);
        removeProvider = amApi.getMethod("removeContentProviderExternalAsUser",
                String.class, IBinder.class, int.class);
        providerBinder = getProvider.getReturnType().getField("provider");
        providerCall = Class.forName("android.content.IContentProvider").getMethod("call",
                AttributionSource.class, String.class, String.class, String.class, Bundle.class);
        startAsCaller = Class.forName("android.app.IActivityTaskManager").getMethod(
                "startActivityAsCaller", Class.forName("android.app.IApplicationThread"),
                String.class, Intent.class, String.class, IBinder.class, String.class,
                int.class, int.class, Class.forName("android.app.ProfilerInfo"), Bundle.class,
                boolean.class, int.class);
        isStartSuccessful = ActivityManager.class.getMethod("isStartResultSuccessful", int.class);
        attribution = new AttributionSource.Builder(Process.myUid()).build();
    }

    public static void main(String[] args) {
        try {
            new CallerBridgeDaemon().serve();
        } catch (Exception error) {
            error.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private void serve() throws Exception {
        LocalServerSocket server = new LocalServerSocket(SOCKET);
        try {
            RootBinder broker = new RootBinder();
            attach(broker);
            System.out.println("READY: CallerBridgeDaemon attached for uid " + appUid);
            System.out.flush();
            while (true) {
                try (LocalSocket client = server.accept()) {
                    if (client.getPeerCredentials().getUid() != appUid) {
                        throw new SecurityException("Unauthorized bridge socket peer");
                    }
                    attach(broker);
                    client.getOutputStream().write(1);
                } catch (Exception error) {
                    System.err.println("Caller bridge handshake failed: " + error);
                }
            }
        } finally {
            server.close();
        }
    }

    private void attach(IBinder broker) throws Exception {
        IBinder token = new Binder();
        Object holder = invoke(getProvider, activityManager, AUTHORITY, 0, token, "share-panel-bridge");
        if (holder == null) throw new IllegalStateException("Share panel provider is unavailable");
        try {
            Object provider = providerBinder.get(holder);
            if (provider == null) throw new IllegalStateException("Share panel provider has no Binder");
            Bundle extras = new Bundle();
            extras.putBinder(KEY_BINDER, broker);
            Bundle result = (Bundle) invoke(providerCall, provider,
                    attribution, AUTHORITY, METHOD_ATTACH, null, extras);
            if (result == null || !result.getBoolean("attached")) {
                throw new IllegalStateException("Share panel did not attach the caller bridge");
            }
        } finally {
            invoke(removeProvider, activityManager, AUTHORITY, token, 0);
        }
    }

    private static Object invoke(Method method, Object receiver, Object... args) throws Exception {
        try {
            return method.invoke(receiver, args);
        } catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IllegalStateException("Platform call failed", cause);
        }
    }

    private final class RootBinder extends Binder {
        RootBinder() {
            attachInterface(null, DESCRIPTOR);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code != TRANSACTION_START) return super.onTransact(code, data, reply, flags);
            if (Binder.getCallingUid() != appUid) {
                throw new SecurityException("Unauthorized caller bridge UID");
            }
            data.enforceInterface(DESCRIPTOR);
            IBinder activityToken = data.readStrongBinder();
            Intent intent = Intent.CREATOR.createFromParcel(data);
            String resolvedType = data.readString();
            int userId = data.readInt();
            data.enforceNoDataAvail();
            if (activityToken == null || intent.getComponent() == null || userId != 0) {
                throw new IllegalArgumentException("An activity token and explicit user-0 target are required");
            }
            try {
                int result;
                long identity = Binder.clearCallingIdentity();
                try {
                    Bundle options = ActivityOptions.makeBasic()
                            .setPendingIntentBackgroundActivityStartMode(
                                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS)
                            .toBundle();
                    result = (Integer) invoke(startAsCaller, taskManager, null, null,
                            intent, resolvedType, activityToken, null, -1, 0, null, options, false, userId);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
                // START_ABORTED is a positive result, but no activity was launched.
                if (result == 102 || !(Boolean) invoke(isStartSuccessful, null, result)) {
                    throw new IllegalStateException("startActivityAsCaller returned " + result);
                }
                reply.writeNoException();
                reply.writeInt(result);
            } catch (Exception error) {
                reply.writeException(new IllegalStateException(error.getMessage(), error));
            }
            return true;
        }
    }
}
