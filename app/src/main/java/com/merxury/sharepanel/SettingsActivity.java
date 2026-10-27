package com.merxury.sharepanel;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Configuration view using the same target list and actions as the share panel. */
public final class SettingsActivity extends ShareActivity {
    private static final ComponentName SYSTEM_CHOOSER = new ComponentName(
            "com.android.intentresolver", "com.android.intentresolver.ChooserActivity");
    private static final ComponentName CUSTOM_CHOOSER = new ComponentName(
            "com.merxury.sharepanel", "com.merxury.sharepanel.ShareActivity");
    private final ExecutorService bridgeExecutor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView statusDot;
    private Button chooserSwitch;
    private boolean switchingChooser;
    private boolean bridgeRunning;
    private boolean activating;
    private String selectedScope;
    private int statusGeneration;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        selectedScope = state == null ? getIntent().getStringExtra("rule_scope")
                : state.getString("rule_scope");
        if (selectedScope == null) selectedScope = RuleScope.DEFAULT_SHARE.key;

        findViewById(R.id.btn_settings).setVisibility(View.GONE);
        statusDot = findViewById(R.id.bridge_status_dot);
        statusDot.setVisibility(View.VISIBLE);
        statusDot.setOnClickListener(v -> showBridgeStatus());

        chooserSwitch = findViewById(R.id.btn_switch_chooser);
        chooserSwitch.setVisibility(View.VISIBLE);
        chooserSwitch.setOnClickListener(v -> switchChooser());

        Spinner spinner = findViewById(R.id.spinner_rule_scope);
        spinner.setVisibility(View.VISIBLE);
        List<RuleScope> scopes = new RuleStore(this).availableScopes();
        ArrayAdapter<RuleScope> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, scopes);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        int index = 0;
        for (int i = 0; i < scopes.size(); i++) {
            if (scopes.get(i).key.equals(selectedScope)) { index = i; break; }
        }
        spinner.setSelection(index);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedScope = scopes.get(position).key;
                processIntent(sampleChooser(scopes.get(position)));
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private Intent sampleChooser(RuleScope scope) {
        int separator = scope.key.indexOf(':');
        String operation = scope.key.substring(0, separator);
        String type = scope.key.substring(separator + 1);
        String action;
        switch (operation) {
            case "share": action = Intent.ACTION_SEND; break;
            case "open": action = Intent.ACTION_VIEW; break;
            case "edit": action = Intent.ACTION_EDIT; break;
            case "process_text": action = Intent.ACTION_PROCESS_TEXT; break;
            default: action = "com.merxury.sharepanel.UNKNOWN";
        }
        Intent sample = new Intent(action);
        if ("web".equals(type)) {
            sample.setData(Uri.parse("https://example.com/"));
        } else {
            String mime = "default".equals(type) ? "*/*" : "unknown".equals(type) ? null : type;
            if (Intent.ACTION_VIEW.equals(action) || Intent.ACTION_EDIT.equals(action)) {
                sample.setDataAndType(Uri.parse("content://com.merxury.sharepanel.preview/sample"), mime);
            } else if (mime != null) {
                sample.setType(mime);
            }
        }
        Intent chooser = new Intent(Intent.ACTION_CHOOSER);
        chooser.putExtra(Intent.EXTRA_INTENT, sample);
        chooser.putExtra("rule_scope", scope.key);
        return chooser;
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("rule_scope", selectedScope);
        super.onSaveInstanceState(state);
    }

    @Override protected void onResume() {
        super.onResume();
        checkBridge();
        updateChooserSwitch();
    }

    private void checkBridge() {
        if (activating) return;
        int generation = ++statusGeneration;
        bridgeExecutor.execute(() -> {
            try { CallerBridgeClient.connect(); } catch (Exception ignored) { }
            boolean running = CallerBridgeClient.isConnected();
            handler.post(() -> {
                if (!isFinishing() && !isDestroyed() && generation == statusGeneration && !activating) {
                    updateStatus(running);
                }
            });
        });
    }

    private void updateStatus(boolean running) {
        bridgeRunning = running;
        statusDot.setTextColor(getColor(running ? R.color.bridge_running : R.color.text_secondary));
        statusDot.setContentDescription(getString(running ? R.string.root_status_running : R.string.root_status_inactive));
    }

    private boolean systemChooserEnabled() {
        int state = getPackageManager().getComponentEnabledSetting(SYSTEM_CHOOSER);
        return state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                || state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }

    private void updateChooserSwitch() {
        if (chooserSwitch == null || switchingChooser) return;
        boolean nativeEnabled = systemChooserEnabled();
        chooserSwitch.setEnabled(true);
        chooserSwitch.setText(nativeEnabled ? R.string.switch_to_custom : R.string.switch_to_native);
    }

    private void switchChooser() {
        if (switchingChooser) return;
        boolean useNative = !systemChooserEnabled();
        switchingChooser = true;
        chooserSwitch.setEnabled(false);
        chooserSwitch.setText(R.string.switching_chooser);
        bridgeExecutor.execute(() -> {
            String error = null;
            try {
                String operation = useNative ? "default-state" : "disable";
                int user = android.os.Process.myUid() / 100000;
                String command = "cmd package " + operation + " --user " + user + " "
                        + SYSTEM_CHOOSER.flattenToString();
                Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
                if (!process.waitFor(25, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IllegalStateException(getString(R.string.root_error_timeout));
                }
                String output = new String(process.getInputStream().readAllBytes()).trim();
                if (process.exitValue() != 0) throw new IllegalStateException(output);
                if (systemChooserEnabled() != useNative) {
                    throw new IllegalStateException("系统组件状态未改变");
                }
                Intent query = new Intent(Intent.ACTION_CHOOSER).addCategory(Intent.CATEGORY_DEFAULT);
                ResolveInfo resolved = getPackageManager().resolveActivity(query, PackageManager.MATCH_DEFAULT_ONLY);
                ComponentName actual = resolved == null || resolved.activityInfo == null ? null
                        : new ComponentName(resolved.activityInfo.packageName, resolved.activityInfo.name);
                if (!(useNative ? SYSTEM_CHOOSER : CUSTOM_CHOOSER).equals(actual)) {
                    throw new IllegalStateException("系统实际解析到其他分享入口：" + actual);
                }
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            String result = error;
            handler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                switchingChooser = false;
                updateChooserSwitch();
                if (result != null) new AlertDialog.Builder(this)
                        .setMessage(getString(R.string.switch_failed, result))
                        .setPositiveButton(android.R.string.ok, null).show();
            });
        });
    }

    private void showBridgeStatus() {
        if (activating) return;
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.bridge_status_title)
                .setMessage(bridgeRunning ? R.string.root_status_running : R.string.root_status_inactive)
                .setNegativeButton(R.string.action_close, null);
        if (!bridgeRunning) dialog.setPositiveButton(R.string.root_action_activate, (d, which) -> activateBridge());
        dialog.show();
    }

    private void activateBridge() {
        if (activating) return;
        activating = true;
        ++statusGeneration;
        Toast.makeText(this, R.string.root_status_activating, Toast.LENGTH_SHORT).show();
        bridgeExecutor.execute(() -> {
            String error = null;
            try { RootActivation.activate(this); }
            catch (Exception e) { error = e.getMessage() == null ? e.toString() : e.getMessage(); }
            String message = error;
            boolean running = CallerBridgeClient.isConnected();
            handler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                activating = false;
                updateStatus(running);
                if (message != null) {
                    new AlertDialog.Builder(this).setTitle(R.string.bridge_status_title)
                            .setMessage(message).setPositiveButton(android.R.string.ok, null).show();
                } else {
                    Toast.makeText(this, R.string.root_activated_success, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    @Override protected void onDestroy() {
        ++statusGeneration;
        bridgeExecutor.shutdown();
        super.onDestroy();
    }
}
