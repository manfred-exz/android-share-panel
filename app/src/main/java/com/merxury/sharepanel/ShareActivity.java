package com.merxury.sharepanel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.view.DragEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * System sharesheet replacement activity.
 * Receives Intent.ACTION_CHOOSER, displays candidate targets, and forwards
 * selected payload with preserved flags and URI permissions.
 */
public class ShareActivity extends Activity implements TargetAdapter.OnTargetActionListener {

    private TargetRepository targetRepository;
    private RuleStore ruleStore;
    private IconLoader iconLoader;
    private TargetAdapter adapter;

    private TextView tvShareTitle;
    private TextView tvShareDescription;
    private ImageButton btnSettings;
    private LinearLayout layoutLoading;
    private LinearLayout layoutEmpty;
    private LinearLayout layoutError;
    private TextView tvError;
    private ListView lvTargets;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private ShareRequest currentRequest;
    private List<ShareTarget> rawTargets;
    private volatile boolean isDispatching = false;

    private List<ShareTarget> originalTargets;
    private boolean isDragging = false;
    private int currentDragPos = -1;
    private int lastDragX = 0;
    private int lastDragY = 0;
    private int autoScrollVelocity = 0;
    private boolean isAutoScrolling = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_share);

        // Apply edge-to-edge window insets cleanly across API 29-36
        View rootView = findViewById(R.id.root_layout);
        if (rootView != null) {
            rootView.setOnApplyWindowInsetsListener((v, insets) -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                } else {
                    v.setPadding(
                            insets.getSystemWindowInsetLeft(),
                            insets.getSystemWindowInsetTop(),
                            insets.getSystemWindowInsetRight(),
                            insets.getSystemWindowInsetBottom()
                    );
                }
                return insets;
            });
        }

        targetRepository = new TargetRepository(this);
        ruleStore = new RuleStore(this);
        iconLoader = new IconLoader(targetRepository);

        initViews();
        if (!(this instanceof SettingsActivity)) processIntent(getIntent());
    }

    private void initViews() {
        tvShareTitle = findViewById(R.id.tv_share_title);
        tvShareDescription = findViewById(R.id.tv_share_description);
        btnSettings = findViewById(R.id.btn_settings);
        layoutLoading = findViewById(R.id.layout_loading);
        layoutEmpty = findViewById(R.id.layout_empty);
        layoutError = findViewById(R.id.layout_error);
        tvError = findViewById(R.id.tv_error);
        lvTargets = findViewById(R.id.lv_targets);

        adapter = new TargetAdapter(this, ruleStore, iconLoader, this);
        lvTargets.setAdapter(adapter);
        lvTargets.setOnDragListener(this::handleDragEvent);

        btnSettings.setOnClickListener(v -> {
            if (isDispatching || isDragging) return;
            // Open settings without finishing this activity so share request & URI grants remain active
            Intent settingsIntent = new Intent(this, SettingsActivity.class);
            if (currentRequest != null) {
                settingsIntent.putExtra("rule_scope", ruleStore.getScope().key);
            }
            startActivity(settingsIntent);
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        processIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh arranged list when returning from SettingsActivity
        if (rawTargets != null && !rawTargets.isEmpty()) {
            rearrangeAndDisplay();
        }
    }

    protected void processIntent(Intent intent) {
        currentRequest = null;
        rawTargets = null;
        if (intent == null) {
            showError(getString(R.string.error_invalid_request, "null Intent"));
            return;
        }

        try {
            currentRequest = ShareRequest.fromIntent(intent);
        } catch (IllegalArgumentException ex) {
            showError(getString(R.string.error_invalid_request, ex.getMessage()));
            return;
        }

        if (this instanceof SettingsActivity) {
            ruleStore.setScope(RuleScope.fromKey(intent.getStringExtra("rule_scope")));
        } else if (currentRequest.ruleScope != null) {
            ruleStore.setScope(currentRequest.ruleScope);
        }

        // Set title and preview description
        tvShareTitle.setText(currentRequest.title != null && !currentRequest.title.isEmpty()
                ? currentRequest.title
                : getString(R.string.share_title_default));

        if (currentRequest.description != null && !currentRequest.description.isEmpty()) {
            tvShareDescription.setText(currentRequest.description);
            tvShareDescription.setVisibility(View.VISIBLE);
        } else {
            tvShareDescription.setVisibility(View.GONE);
        }
        if (this instanceof SettingsActivity) {
            tvShareTitle.setText(R.string.settings_title);
            tvShareDescription.setText(R.string.scope_configuration_hint);
            tvShareDescription.setVisibility(View.VISIBLE);
        }

        final ShareRequest loadingRequest = currentRequest;
        final java.util.Set<String> savedKeys = new java.util.HashSet<>();
        if (this instanceof SettingsActivity) {
            savedKeys.addAll(ruleStore.hiddenKeys());
        }
        showLoading();
        executor.execute(() -> {
            try {
                List<ShareTarget> result = targetRepository.load(loadingRequest);
                if (!savedKeys.isEmpty()) {
                    java.util.Set<String> found = new java.util.HashSet<>();
                    for (ShareTarget target : result) found.add(target.key);
                    for (ShareTarget target : targetRepository.loadSaved(savedKeys)) {
                        if (found.add(target.key)) result.add(target);
                    }
                }
                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed() || currentRequest != loadingRequest) return;
                    rawTargets = result;
                    rearrangeAndDisplay();
                });
            } catch (RuntimeException error) {
                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed() || currentRequest != loadingRequest) return;
                    showError(getString(R.string.error_load_targets, error.getMessage()));
                });
            }
        });
    }

    private void rearrangeAndDisplay() {
        if (rawTargets == null) return;

        List<ShareTarget> arranged;
        if (this instanceof SettingsActivity) {
            arranged = new ArrayList<>(rawTargets);
            ruleStore.sortSavedTargets(arranged);
        } else {
            arranged = ruleStore.arrange(rawTargets);
        }

        layoutLoading.setVisibility(View.GONE);
        layoutError.setVisibility(View.GONE);

        if (arranged.isEmpty()) {
            layoutEmpty.setVisibility(View.VISIBLE);
            lvTargets.setVisibility(View.GONE);
        } else {
            layoutEmpty.setVisibility(View.GONE);
            lvTargets.setVisibility(View.VISIBLE);
            adapter.setTargets(arranged);
        }
    }

    private void showLoading() {
        layoutLoading.setVisibility(View.VISIBLE);
        layoutEmpty.setVisibility(View.GONE);
        layoutError.setVisibility(View.GONE);
        lvTargets.setVisibility(View.GONE);
    }

    private void showError(String message) {
        layoutLoading.setVisibility(View.GONE);
        layoutEmpty.setVisibility(View.GONE);
        layoutError.setVisibility(View.VISIBLE);
        lvTargets.setVisibility(View.GONE);
        tvError.setText(message);
    }

    @Override
    public void onTargetClick(ShareTarget target) {
        if (this instanceof SettingsActivity) {
            Toast.makeText(this, R.string.scope_preview_no_launch, Toast.LENGTH_SHORT).show();
            return;
        }
        if (isDispatching || isDragging) return;
        if (target == null || target.launchIntent == null) {
            showErrorDialog(getString(R.string.launch_failed, getString(R.string.target_missing_app)));
            return;
        }

        final Intent launchIntent = target.launchIntent;
        isDispatching = true;

        final boolean bridgeRequired;
        try {
            bridgeRequired = isBridgeRequired(launchIntent);
        } catch (RuntimeException error) {
            isDispatching = false;
            showErrorDialog(getString(R.string.launch_failed, error.getMessage()));
            return;
        }
        if (!bridgeRequired) {
            try {
                startActivity(launchIntent);
                if (currentRequest != null) {
                    currentRequest.notifySelected(this, target.component);
                }
                finish();
            } catch (Exception ex) {
                isDispatching = false;
                showErrorDialog(getString(R.string.launch_failed, ex.getMessage()));
            }
            return;
        }

        // Privileged bridge dispatch required because outgoing content URI grant is missing from chooser
        IBinder activityToken = null;
        if (getWindow() != null && getWindow().getAttributes() != null) {
            activityToken = getWindow().getAttributes().token;
        }
        if (activityToken == null) {
            isDispatching = false;
            showErrorDialog(getString(R.string.launch_failed, getString(R.string.error_missing_activity_token)));
            return;
        }

        final IBinder finalToken = activityToken;
        final Intent bridgeIntent = prepareBridgeIntent(launchIntent);
        final String resolvedType = resolveIntentType(bridgeIntent);
        final int userId = Process.myUid() / 100000;

        executor.execute(() -> {
            try {
                CallerBridgeClient.start(finalToken, bridgeIntent, resolvedType, userId);
                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (currentRequest != null) {
                        currentRequest.notifySelected(this, target.component);
                    }
                    finish();
                });
            } catch (Exception ex) {
                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    isDispatching = false;
                    showErrorDialog(getString(R.string.launch_failed, ex.getMessage()));
                });
            }
        });
    }

    private boolean isBridgeRequired(Intent intent) {
        int modes = intent.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (Intent.ACTION_SEND.equals(intent.getAction()) || Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            modes |= Intent.FLAG_GRANT_READ_URI_PERMISSION;
        }
        return modes != 0 && hasMissingUriPermission(intent, modes);
    }

    private Intent prepareBridgeIntent(Intent original) {
        if (original == null) return null;

        String action = original.getAction();
        boolean isSend = Intent.ACTION_SEND.equals(action);
        boolean isSendMultiple = Intent.ACTION_SEND_MULTIPLE.equals(action);

        boolean needsClipData = (isSend || isSendMultiple)
                && original.getClipData() == null
                && original.hasExtra(Intent.EXTRA_STREAM);

        boolean needsReadFlag = (isSend || isSendMultiple || original.getClipData() != null)
                && (original.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0;

        if (!needsClipData && !needsReadFlag) {
            return original;
        }

        Intent clone = new Intent(original);

        if (needsClipData) {
            String type = clone.getType();
            String[] mimeTypes = type != null ? new String[]{type} : new String[]{"*/*"};

            if (isSend) {
                Uri stream = null;
                try {
                    stream = clone.getParcelableExtra(Intent.EXTRA_STREAM);
                } catch (Exception ignored) {
                }
                CharSequence text = null;
                try {
                    text = clone.getCharSequenceExtra(Intent.EXTRA_TEXT);
                } catch (Exception ignored) {
                }
                String htmlText = null;
                try {
                    htmlText = clone.getStringExtra(Intent.EXTRA_HTML_TEXT);
                } catch (Exception ignored) {
                }

                if (stream != null || text != null || htmlText != null) {
                    ClipData clipData = new ClipData(null, mimeTypes, new ClipData.Item(text, htmlText, null, stream));
                    clone.setClipData(clipData);
                }
            } else {
                ArrayList<Uri> streams = null;
                try {
                    streams = clone.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                } catch (Exception ignored) {
                }
                if (streams != null && !streams.isEmpty()) {
                    ClipData clipData = null;
                    for (Uri u : streams) {
                        ClipData.Item item = new ClipData.Item(null, null, null, u);
                        if (clipData == null) {
                            clipData = new ClipData(null, mimeTypes, item);
                        } else {
                            clipData.addItem(item);
                        }
                    }
                    if (clipData != null) {
                        clone.setClipData(clipData);
                    }
                }
            }
        }

        if ((clone.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) {
            clone.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }

        return clone;
    }

    private String resolveIntentType(Intent intent) {
        if (intent == null) return null;
        String type = intent.getType();
        if (type != null && !type.isEmpty()) {
            return type;
        }
        Uri data = intent.getData();
        if (data != null && isContentUri(data)) {
            try {
                return getContentResolver().getType(data);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private boolean hasMissingUriPermission(Intent intent, int modes) {
        if (isUriPermissionMissing(intent.getData(), modes)) return true;
        ClipData clips = intent.getClipData();
        if (clips != null) {
            for (int index = 0; index < clips.getItemCount(); index++) {
                ClipData.Item item = clips.getItemAt(index);
                if (isUriPermissionMissing(item.getUri(), modes)) return true;
                if (item.getIntent() != null && hasMissingUriPermission(item.getIntent(), modes)) return true;
            }
        }
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            return isUriPermissionMissing(intent.getParcelableExtra(Intent.EXTRA_STREAM), modes);
        }
        if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (streams != null) {
                for (Uri uri : streams) {
                    if (isUriPermissionMissing(uri, modes)) return true;
                }
            }
        }
        return false;
    }

    private boolean isUriPermissionMissing(Uri uri, int modes) {
        if (!isContentUri(uri)) return false;
        try {
            if (Build.VERSION.SDK_INT >= 35) {
                return checkContentUriPermissionFull(uri, Process.myPid(), Process.myUid(), modes)
                        != PackageManager.PERMISSION_GRANTED;
            }
            return checkUriPermission(uri, Process.myPid(), Process.myUid(), modes)
                    != PackageManager.PERMISSION_GRANTED;
        } catch (SecurityException denied) {
            return true;
        }
    }

    private static boolean isContentUri(Uri uri) {
        return uri != null && ContentResolver.SCHEME_CONTENT.equalsIgnoreCase(uri.getScheme());
    }


    @Override
    public void onMoveTarget(ShareTarget target, boolean toTop) {
        if (isDispatching || isDragging) return;
        List<ShareTarget> targets = adapter.getTargets();
        if (!targets.remove(target)) return;
        targets.add(toTop ? 0 : targets.size(), target);
        ruleStore.saveOrderedTargets(targets);
        rearrangeAndDisplay();
    }

    @Override
    public void onHideTarget(ShareTarget target) {
        if (isDispatching || isDragging) return;
        boolean hidden = !(this instanceof SettingsActivity) || !ruleStore.isHidden(target.key);
        ruleStore.setHidden(target.key, hidden);

        String targetName = target.appLabel != null && !target.appLabel.isEmpty()
                ? target.appLabel
                : target.component.getPackageName();
        String message = getString(hidden ? R.string.toast_hidden : R.string.toast_restored, targetName);
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();

        rearrangeAndDisplay();
    }

    @Override
    public boolean isDispatchingShare() {
        return isDispatching || isDragging;
    }

    @Override
    public boolean onStartDrag(ShareTarget target, int position, View itemView) {
        if (isDispatching || isDragging) return false;
        originalTargets = adapter.getTargets();
        isDragging = true;
        currentDragPos = position;
        adapter.setDraggingKey(target.key);
        adapter.notifyDataSetChanged();

        ClipData clipData = ClipData.newPlainText("share_target", target.key);
        View.DragShadowBuilder shadowBuilder = new View.DragShadowBuilder(itemView);
        boolean started = itemView.startDragAndDrop(clipData, shadowBuilder, target, 0);
        if (!started) finishDrag(false);
        return started;
    }

    private boolean handleDragEvent(View v, DragEvent event) {
        if (isDispatching) {
            return false;
        }
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return isDragging;

            case DragEvent.ACTION_DRAG_ENTERED:
                return true;

            case DragEvent.ACTION_DRAG_LOCATION:
                lastDragX = (int) event.getX();
                lastDragY = (int) event.getY();
                checkAutoScroll(lastDragY);
                checkDragPosition(lastDragX, lastDragY);
                return true;

            case DragEvent.ACTION_DRAG_EXITED:
                stopAutoScroll();
                return true;

            case DragEvent.ACTION_DROP:
                checkDragPosition((int) event.getX(), (int) event.getY());
                finishDrag(true);
                return true;

            case DragEvent.ACTION_DRAG_ENDED:
                finishDrag(false);
                return true;

            default:
                return false;
        }
    }

    private void finishDrag(boolean commit) {
        stopAutoScroll();
        if (originalTargets == null) return;
        if (commit) {
            List<ShareTarget> ordered = adapter.getTargets();
            if (!ordered.equals(originalTargets)) ruleStore.saveOrderedTargets(ordered);
        } else {
            adapter.setTargets(originalTargets);
        }
        adapter.setDraggingKey(null);
        adapter.notifyDataSetChanged();
        originalTargets = null;
        isDragging = false;
    }

    private void checkDragPosition(int x, int y) {
        if (!isDragging || adapter.getCount() <= 1) return;

        int targetPos = lvTargets.pointToPosition(x, y);
        if (targetPos == AdapterView.INVALID_POSITION) {
            int childCount = lvTargets.getChildCount();
            if (childCount > 0) {
                View firstChild = lvTargets.getChildAt(0);
                View lastChild = lvTargets.getChildAt(childCount - 1);
                if (y < firstChild.getTop()) {
                    targetPos = lvTargets.getFirstVisiblePosition();
                } else if (y > lastChild.getBottom()) {
                    targetPos = lvTargets.getLastVisiblePosition();
                } else {
                    for (int i = 0; i < childCount; i++) {
                        View child = lvTargets.getChildAt(i);
                        if (y >= child.getTop() && y <= child.getBottom()) {
                            targetPos = lvTargets.getFirstVisiblePosition() + i;
                            break;
                        }
                    }
                }
            }
        }

        if (targetPos == AdapterView.INVALID_POSITION) {
            return;
        }

        int totalCount = adapter.getCount();

        if (targetPos != currentDragPos && targetPos >= 0 && targetPos < totalCount) {
            adapter.moveItem(currentDragPos, targetPos);
            currentDragPos = targetPos;
        }
    }

    private void checkAutoScroll(int y) {
        int height = lvTargets.getHeight();
        if (height <= 0) return;

        int threshold = (int) (48 * getResources().getDisplayMetrics().density);
        int topBound = lvTargets.getPaddingTop() + threshold;
        int bottomBound = height - lvTargets.getPaddingBottom() - threshold;

        if (y < topBound) {
            int dy = topBound - y;
            int maxStep = (int) (18 * getResources().getDisplayMetrics().density);
            int minStep = (int) (4 * getResources().getDisplayMetrics().density);
            int step = -Math.min(maxStep, Math.max(minStep, dy / 2));
            startAutoScroll(step);
        } else if (y > bottomBound) {
            int dy = y - bottomBound;
            int maxStep = (int) (18 * getResources().getDisplayMetrics().density);
            int minStep = (int) (4 * getResources().getDisplayMetrics().density);
            int step = Math.min(maxStep, Math.max(minStep, dy / 2));
            startAutoScroll(step);
        } else {
            stopAutoScroll();
        }
    }

    private void startAutoScroll(int velocity) {
        autoScrollVelocity = velocity;
        if (!isAutoScrolling) {
            isAutoScrolling = true;
            mainHandler.post(autoScrollRunnable);
        }
    }

    private void stopAutoScroll() {
        autoScrollVelocity = 0;
        isAutoScrolling = false;
        mainHandler.removeCallbacks(autoScrollRunnable);
    }

    private final Runnable autoScrollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isDragging || autoScrollVelocity == 0) {
                isAutoScrolling = false;
                return;
            }
            lvTargets.scrollListBy(autoScrollVelocity);
            checkDragPosition(lastDragX, lastDragY);
            mainHandler.postDelayed(this, 16);
        }
    };


    private void showErrorDialog(String message) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopAutoScroll();
        if (iconLoader != null) {
            iconLoader.destroy();
        }
        executor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
    }
}
