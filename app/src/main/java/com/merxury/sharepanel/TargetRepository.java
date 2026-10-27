package com.merxury.sharepanel;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Discovers and prepares effective share target activities via PackageManager.
 */
public final class TargetRepository {
    private final Context context;
    private final PackageManager pm;

    public TargetRepository(Context context) {
        this.context = Objects.requireNonNull(context, "context cannot be null").getApplicationContext();
        this.pm = this.context.getPackageManager();
    }

    public List<ShareTarget> load(ShareRequest request) {
        if (request == null) {
            return Collections.emptyList();
        }

        List<ShareTarget> result = new ArrayList<>();
        Set<ComponentName> seenComponents = new HashSet<>();
        Set<ComponentName> excluded = request.excludedComponents();
        String selfPackage = context.getPackageName();

        // 1. EXTRA_INITIAL_INTENTS
        for (Intent initial : request.initialIntents()) {
            if (initial == null) continue;
            ComponentName cn = initial.getComponent();
            if (cn != null) {
                if (selfPackage.equals(cn.getPackageName())) continue;
                if (excluded.contains(cn)) continue;
                try {
                    ActivityInfo ai = pm.getActivityInfo(cn, PackageManager.MATCH_DEFAULT_ONLY);
                    if (isValidLaunchable(ai)) {
                        String appLabel = getAppLabel(ai);
                        String activityLabel = getActivityLabel(ai, appLabel);
                        Intent launchIntent = prepareLaunchIntent(initial, cn);
                        result.add(new ShareTarget(cn, appLabel, activityLabel, launchIntent, ai));
                        seenComponents.add(cn);
                    }
                } catch (PackageManager.NameNotFoundException missing) {
                    // An optional target may have been uninstalled since the request was created.
                }
            } else {
                List<ResolveInfo> matches = pm.queryIntentActivities(initial, PackageManager.MATCH_DEFAULT_ONLY);
                for (ResolveInfo ri : matches) {
                    ActivityInfo ai = ri.activityInfo;
                    if (!isValidLaunchable(ai)) continue;
                    if (selfPackage.equals(ai.packageName)) continue;
                    ComponentName matchCn = new ComponentName(ai.packageName, ai.name);
                    if (excluded.contains(matchCn)) continue;

                    String appLabel = getAppLabel(ai);
                    String activityLabel = getActivityLabel(ai, appLabel);
                    Intent launchIntent = prepareLaunchIntent(initial, matchCn);
                    result.add(new ShareTarget(matchCn, appLabel, activityLabel, launchIntent, ai));
                    seenComponents.add(matchCn);
                }
            }
        }

        // 2. EXTRA_INTENT + EXTRA_ALTERNATE_INTENTS
        for (Intent payload : request.payloadIntents()) {
            if (payload == null) continue;
            List<ResolveInfo> matches = pm.queryIntentActivities(payload, PackageManager.MATCH_DEFAULT_ONLY);
            for (ResolveInfo ri : matches) {
                ActivityInfo ai = ri.activityInfo;
                if (!isValidLaunchable(ai)) continue;
                if (selfPackage.equals(ai.packageName)) continue;
                ComponentName cn = new ComponentName(ai.packageName, ai.name);
                if (excluded.contains(cn)) continue;
                if (!seenComponents.add(cn)) continue;

                String appLabel = getAppLabel(ai);
                String activityLabel = getActivityLabel(ai, appLabel);
                Intent launchIntent = prepareLaunchIntent(payload, cn);
                result.add(new ShareTarget(cn, appLabel, activityLabel, launchIntent, ai));
            }
        }

        return result;
    }

    public List<ShareTarget> loadSaved(Set<String> componentKeys) {
        if (componentKeys == null || componentKeys.isEmpty()) {
            return Collections.emptyList();
        }

        List<ShareTarget> result = new ArrayList<>();
        for (String key : componentKeys) {
            if (key == null || key.trim().isEmpty()) continue;
            ComponentName cn = ComponentName.unflattenFromString(key);
            if (cn == null) continue;

            ActivityInfo ai = null;
            try {
                ai = pm.getActivityInfo(cn, PackageManager.MATCH_DISABLED_COMPONENTS);
            } catch (PackageManager.NameNotFoundException missing) {
                // Keep the saved rule visible so it can still be removed.
            }

            if (ai != null) {
                String appLabel = getAppLabel(ai);
                String activityLabel = getActivityLabel(ai, appLabel);
                result.add(new ShareTarget(cn, appLabel, activityLabel, null, ai));
            } else {
                result.add(ShareTarget.forMissing(cn));
            }
        }
        return result;
    }

    public Drawable loadIcon(ShareTarget target) {
        if (target == null) {
            return pm.getDefaultActivityIcon();
        }

        if (target.activityInfo != null) {
            try {
                Drawable icon = target.activityInfo.loadIcon(pm);
                if (icon != null) return icon;
            } catch (RuntimeException unavailable) {}
            try {
                if (target.activityInfo.applicationInfo != null) {
                    Drawable icon = target.activityInfo.applicationInfo.loadIcon(pm);
                    if (icon != null) return icon;
                }
            } catch (RuntimeException unavailable) {}
        }

        if (target.component != null) {
            try {
                Drawable icon = pm.getActivityIcon(target.component);
                if (icon != null) return icon;
            } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {}
        }

        return pm.getDefaultActivityIcon();
    }

    private static boolean isValidLaunchable(ActivityInfo ai) {
        if (ai == null) return false;
        if (!ai.exported) return false;
        if (!ai.isEnabled()) return false;
        if (ai.applicationInfo != null && !ai.applicationInfo.enabled) return false;
        return true;
    }

    private Intent prepareLaunchIntent(Intent baseIntent, ComponentName component) {
        Intent launch = new Intent(baseIntent);
        launch.setComponent(component);
        launch.setPackage(null);
        return launch;
    }

    private String getAppLabel(ActivityInfo ai) {
        try {
            if (ai.applicationInfo != null) {
                CharSequence label = ai.applicationInfo.loadLabel(pm);
                if (label != null && label.length() > 0) {
                    return label.toString();
                }
            }
        } catch (RuntimeException unavailable) {}
        return ai.packageName;
    }

    private String getActivityLabel(ActivityInfo ai, String appLabel) {
        try {
            CharSequence label = ai.loadLabel(pm);
            if (label != null && label.length() > 0) {
                return label.toString();
            }
        } catch (RuntimeException unavailable) {}
        return appLabel;
    }
}
