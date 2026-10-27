package com.merxury.sharepanel;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Handles explicit on-device activation of the privileged per-boot CallerBridgeDaemon.
 * Requests Root only when the user explicitly triggers activation, and verifies
 * daemon readiness using native socket bootstrapping without hidden APIs.
 */
public final class RootActivation {

    private static final long SU_TIMEOUT_SECONDS = 25;
    private static final long DAEMON_READY_TIMEOUT_MS = 4000;
    private static final long PROBE_INTERVAL_MS = 250;

    private RootActivation() {
    }

    /**
     * Activates the caller bridge daemon.
     * Reuses an already running/healthy daemon if possible.
     * Otherwise requests su permission to launch the app_process daemon from the installed base APK.
     * Must be called OFF the main thread.
     */
    public static void activate(Context context) throws Exception {
        if (context == null) {
            throw new IllegalArgumentException("context cannot be null");
        }

        // 1. Reuse existing daemon if healthy and responsive
        if (CallerBridgeClient.isConnected()) {
            return;
        }
        try {
            CallerBridgeClient.connect(context);
            if (CallerBridgeClient.isConnected()) {
                return;
            }
        } catch (IOException unavailable) {
            // Daemon is not running or socket is inactive; proceed to start via su
        }

        // 2. Locate installed base APK path
        ApplicationInfo appInfo = context.getApplicationInfo();
        if (appInfo == null || appInfo.sourceDir == null || appInfo.sourceDir.isEmpty()) {
            throw new IllegalStateException(context.getString(R.string.root_apk_missing));
        }
        String safeApkPath = appInfo.sourceDir.replace("'", "'\\''");

        // 3. Construct trusted startup command with detached stdio
        String command = "CLASSPATH='" + safeApkPath + "' " +
                "nohup app_process /system/bin " +
                "--nice-name=com.merxury.sharepanel.callerbridge " +
                "com.merxury.sharepanel.CallerBridgeDaemon " +
                "> /data/local/tmp/share-panel-callerbridge.log 2>&1 < /dev/null &";

        // 4. Execute su command with drained stdout/stderr to prevent pipe deadlock
        Process process;
        try {
            process = startSuProcess(command);
        } catch (IOException e) {
            throw new IOException(context.getString(R.string.root_su_failed), e);
        }

        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread drainer = new Thread(() -> drainStream(process.getInputStream(), output), "RootActivationOutput");
        drainer.start();

        boolean finished;
        try {
            finished = process.waitFor(SU_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException(context.getString(R.string.root_interrupted), e);
        }

        if (!finished) {
            process.destroyForcibly();
            throw new TimeoutException(context.getString(R.string.root_error_timeout));
        }

        try {
            drainer.join(1000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(context.getString(R.string.root_interrupted), interrupted);
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            String err = output.toString().trim();
            String lower = err.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("denied") || exitCode == 13) {
                throw new SecurityException(context.getString(R.string.root_command_denied, exitCode, err.isEmpty() ? "" : ": " + err));
            }
            throw new IOException(context.getString(R.string.root_command_failed, exitCode, err.isEmpty() ? "" : ": " + err));
        }

        // 5. Verify the daemon is ready by connecting to its abstract socket
        long deadline = SystemClock.uptimeMillis() + DAEMON_READY_TIMEOUT_MS;
        boolean connected = false;
        IOException handshakeError = null;

        while (SystemClock.uptimeMillis() < deadline) {
            try {
                CallerBridgeClient.connect(context);
                if (CallerBridgeClient.isConnected()) {
                    connected = true;
                    break;
                }
            } catch (IOException t) {
                handshakeError = t;
            }

            try {
                Thread.sleep(PROBE_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (!connected) {
            String detail = handshakeError != null ? handshakeError.getMessage() : context.getString(R.string.root_no_ack);
            throw new IOException(context.getString(R.string.root_handshake_failed, detail));
        }
    }

    private static Process startSuProcess(String command) throws IOException {
        return new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
    }

    private static void drainStream(InputStream in, ByteArrayOutputStream out) {
        try {
            byte[] buf = new byte[1024];
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
            }
        } catch (IOException ignored) {
        }
    }
}
