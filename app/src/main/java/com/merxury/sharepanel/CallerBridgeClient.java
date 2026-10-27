package com.merxury.sharepanel;

import android.content.Context;
import android.content.Intent;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import java.io.IOException;
import java.io.InputStream;

/**
 * Client for communicating with the privileged per-boot CallerBridgeDaemon.
 * Executes target dispatch via startActivityAsCaller without requiring
 * app-level root or hidden API exemptions.
 */
public final class CallerBridgeClient {
    public static final String AUTHORITY = "com.merxury.sharepanel.bridge";
    public static final String SOCKET = "com.merxury.sharepanel.bridge.v1";
    public static final String DESCRIPTOR = "com.merxury.sharepanel.ICallerBridge";
    public static final int TRANSACTION_START = IBinder.FIRST_CALL_TRANSACTION;
    public static final String METHOD_ATTACH = "attach";
    public static final String KEY_BINDER = "binder";

    private static final int HANDSHAKE_TIMEOUT_MS = 3000;

    private static volatile IBinder sBroker;

    private CallerBridgeClient() {
    }

    /**
     * Caches the live broker Binder received from the privileged daemon.
     */
    public static synchronized void attach(IBinder broker) {
        if (broker == null) {
            throw new IllegalArgumentException("Broker binder cannot be null");
        }
        sBroker = broker;
        try {
            broker.linkToDeath(() -> {
                synchronized (CallerBridgeClient.class) {
                    if (sBroker == broker) {
                        sBroker = null;
                    }
                }
            }, 0);
        } catch (RemoteException ignored) {
        }
    }
    /**
     * Checks if a cached live broker is currently available and responsive.
     * Fast in-memory check suitable for any thread.
     */
    public static boolean isConnected() {
        IBinder broker = sBroker;
        return broker != null && broker.isBinderAlive();
    }

    /**
     * Connects to the local abstract socket of the privileged daemon to trigger
     * broker attachment. If already connected with an alive broker, returns immediately.
     * Must be called OFF the main thread.
     */
    public static void connect(Context context) throws IOException {
        IBinder broker = sBroker;
        if (broker != null && broker.isBinderAlive()) {
            return;
        }
        connectAndBootstrap(context);
        broker = sBroker;
        if (broker == null || !broker.isBinderAlive()) {
            throw new IOException(context.getString(R.string.bridge_not_ready));
        }
    }

    /**
     * Dispatches the launch intent via startActivityAsCaller using the privileged broker.
     * Must be called OFF the main thread.
     */
    public static void start(Context context, IBinder activityToken, Intent intent, String resolvedType, int userId) throws Exception {
        if (activityToken == null) {
            throw new IllegalArgumentException("activityToken cannot be null");
        }
        if (intent == null) {
            throw new IllegalArgumentException("intent cannot be null");
        }

        connect(context);
        IBinder broker = sBroker;

        if (broker == null || !broker.isBinderAlive()) {
            throw new IOException(context.getString(R.string.bridge_not_ready));
        }

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeStrongBinder(activityToken);
            intent.writeToParcel(data, 0);
            data.writeString(resolvedType);
            data.writeInt(userId);

            boolean success = broker.transact(TRANSACTION_START, data, reply, 0);
            if (!success) {
                throw new RemoteException(context.getString(R.string.bridge_transaction_failed));
            }
            reply.readException();
            int startResult = reply.readInt();
            if (startResult < 0) {
                throw new StartActivityException(context.getString(R.string.bridge_start_failed, startResult), startResult);
            }
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static void connectAndBootstrap(Context context) throws IOException {
        LocalSocket socket = new LocalSocket();
        try {
            socket.connect(new LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.ABSTRACT));
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            InputStream in = socket.getInputStream();
            int ack = in.read();
            if (ack != 1) {
                throw new IOException(context.getString(R.string.bridge_handshake_failed, ack));
            }
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    public static class StartActivityException extends RuntimeException {
        public final int resultCode;

        public StartActivityException(String message, int resultCode) {
            super(message);
            this.resultCode = resultCode;
        }
    }
}
