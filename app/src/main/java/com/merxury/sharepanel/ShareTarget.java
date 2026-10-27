package com.merxury.sharepanel;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;

import java.util.Objects;

/**
 * Immutable model representing a single target application/activity for sharing.
 */
public final class ShareTarget {
    public final String key;
    public final ComponentName component;
    public final String appLabel;
    public final String activityLabel;
    public final Intent launchIntent;
    public final ActivityInfo activityInfo;

    public ShareTarget(ComponentName component, String appLabel, String activityLabel, Intent launchIntent, ActivityInfo activityInfo) {
        this.component = Objects.requireNonNull(component, "component cannot be null");
        this.key = component.flattenToString();
        this.appLabel = appLabel != null ? appLabel : "";
        this.activityLabel = activityLabel != null ? activityLabel : "";
        this.launchIntent = launchIntent;
        this.activityInfo = activityInfo;
    }

    public static ShareTarget forMissing(ComponentName component) {
        Objects.requireNonNull(component, "component cannot be null");
        return new ShareTarget(component, component.getPackageName(), component.getClassName(), null, null);
    }

    public static ShareTarget forMissing(ComponentName component, String appLabel, String activityLabel) {
        Objects.requireNonNull(component, "component cannot be null");
        return new ShareTarget(component, appLabel, activityLabel, null, null);
    }

    public boolean isMissing() {
        return activityInfo == null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ShareTarget)) return false;
        ShareTarget that = (ShareTarget) o;
        return Objects.equals(this.key, that.key);
    }

    @Override
    public int hashCode() {
        return key != null ? key.hashCode() : 0;
    }

    @Override
    public String toString() {
        return "ShareTarget{" +
                "key='" + key + '\'' +
                ", appLabel='" + appLabel + '\'' +
                ", activityLabel='" + activityLabel + '\'' +
                ", hasLaunchIntent=" + (launchIntent != null) +
                ", isMissing=" + isMissing() +
                '}';
    }
}
