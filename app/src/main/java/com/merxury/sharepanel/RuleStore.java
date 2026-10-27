package com.merxury.sharepanel;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Manages persistent ordering and hidden share targets.
 */
public final class RuleStore {
    private static final String PREF_NAME = "share_panel_rules";
    private static final String KEY_PINNED = "pinned_components";
    private static final String KEY_HIDDEN = "hidden_components";
    private static final String KEY_ORDERED = "ordered_component_keys";
    private static final String KEY_SCOPES_CATALOG = "rule_scopes";

    private final SharedPreferences prefs;
    private final Context context;
    private final Object lock = new Object();
    private RuleScope currentScope = RuleScope.DEFAULT_SHARE;

    public RuleStore(Context context) {
        this.context = Objects.requireNonNull(context, "context cannot be null").getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        ensureScopeInitialized(RuleScope.DEFAULT_SHARE);
        this.currentScope = RuleScope.DEFAULT_SHARE;
    }

    public void setScope(RuleScope scope) {
        RuleScope targetScope = scope != null ? scope : RuleScope.DEFAULT_SHARE;
        ensureScopeInitialized(targetScope);
        synchronized (lock) {
            this.currentScope = targetScope;
        }
    }

    public RuleScope getScope() {
        synchronized (lock) {
            return currentScope != null ? currentScope : RuleScope.DEFAULT_SHARE;
        }
    }

    public List<RuleScope> availableScopes() {
        synchronized (lock) {
            List<RuleScope> result = new ArrayList<>(RuleScope.PRESETS);
            Set<String> known = new HashSet<>();
            for (RuleScope preset : RuleScope.PRESETS) known.add(preset.key);
            Set<String> catalog = prefs.getStringSet(KEY_SCOPES_CATALOG, Collections.emptySet());
            List<String> additional = new ArrayList<>(catalog);
            Collections.sort(additional);
            for (String key : additional) {
                RuleScope scope = RuleScope.fromKey(key);
                if (known.add(scope.key)) result.add(scope);
            }
            return result;
        }
    }

    private boolean isDefaultScope(RuleScope scope) {
        return scope == null
                || RuleScope.DEFAULT_SHARE.equals(scope)
                || (scope.key != null && scope.key.equals(RuleScope.DEFAULT_SHARE.key));
    }

    private String getPinnedKey(RuleScope scope) {
        return isDefaultScope(scope) ? KEY_PINNED : KEY_PINNED + ":" + scope.key;
    }

    private String getHiddenKey(RuleScope scope) {
        return isDefaultScope(scope) ? KEY_HIDDEN : KEY_HIDDEN + ":" + scope.key;
    }

    private String getOrderedKey(RuleScope scope) {
        return isDefaultScope(scope) ? KEY_ORDERED : KEY_ORDERED + ":" + scope.key;
    }

    private void ensureScopeInitialized(RuleScope scope) {
        if (scope == null) return;
        synchronized (lock) {
            Set<String> catalog = prefs.getStringSet(KEY_SCOPES_CATALOG, null);
            Set<String> newCatalog = catalog == null ? new HashSet<>() : new HashSet<>(catalog);
            newCatalog.add(RuleScope.DEFAULT_SHARE.key);
            boolean catalogChanged = newCatalog.add(scope.key);
            if (catalogChanged || catalog == null) {
                prefs.edit().putStringSet(KEY_SCOPES_CATALOG, newCatalog).apply();
            }
            migratePinnedOrder(scope);

            if (isDefaultScope(scope)) {
                return;
            }

            String hiddenKey = getHiddenKey(scope);
            String orderedKey = getOrderedKey(scope);

            boolean alreadyInitialized = prefs.contains(hiddenKey) || prefs.contains(orderedKey);

            if (!alreadyInitialized) {
                SharedPreferences.Editor editor = prefs.edit();
                if (scope.key != null && scope.key.startsWith("share:")) {
                    Set<String> defaultHidden = prefs.getStringSet(KEY_HIDDEN, Collections.emptySet());
                    String defaultOrdered = prefs.getString(KEY_ORDERED, null);

                    editor.putStringSet(hiddenKey, new HashSet<>(defaultHidden));
                    if (defaultOrdered != null && !defaultOrdered.isEmpty()) {
                        editor.putString(orderedKey, defaultOrdered);
                    }
                } else {
                    editor.putStringSet(hiddenKey, Collections.emptySet());
                }
                editor.apply();
            }
        }
    }

    private void migratePinnedOrder(RuleScope scope) {
        String pinnedKey = getPinnedKey(scope);
        if (!prefs.contains(pinnedKey)) return;
        Set<String> pins = prefs.getStringSet(pinnedKey, Collections.emptySet());
        String orderKey = getOrderedKey(scope);
        List<String> oldOrder = new ArrayList<>();
        String json = prefs.getString(orderKey, null);
        try {
            if (json != null && !json.isEmpty()) {
                JSONArray array = new JSONArray(json);
                for (int i = 0; i < array.length(); i++) {
                    String key = array.optString(i, null);
                    if (key != null && !key.isEmpty()) oldOrder.add(key);
                }
            }
        } catch (JSONException corruptOrder) {
            return;
        }
        Map<String, Integer> ranks = new HashMap<>();
        for (int i = 0; i < oldOrder.size(); i++) ranks.putIfAbsent(oldOrder.get(i), i);
        Map<String, ShareTarget> targets = new HashMap<>();
        for (ShareTarget target : new TargetRepository(context).loadSaved(pins)) {
            targets.put(target.key, target);
        }
        Collator collator = Collator.getInstance(Locale.getDefault());
        List<String> orderedPins = new ArrayList<>(pins);
        orderedPins.sort((a, b) -> {
            Integer ar = ranks.get(a), br = ranks.get(b);
            if (ar != null && br != null) return Integer.compare(ar, br);
            if (ar != null) return -1;
            if (br != null) return 1;
            ShareTarget at = targets.get(a), bt = targets.get(b);
            if (at != null && bt != null) {
                int cmp = collator.compare(at.appLabel, bt.appLabel);
                if (cmp != 0) return cmp;
                cmp = collator.compare(at.activityLabel, bt.activityLabel);
                if (cmp != 0) return cmp;
            }
            return a.compareTo(b);
        });
        for (String key : oldOrder) if (!pins.contains(key)) orderedPins.add(key);
        JSONArray result = new JSONArray();
        for (String key : orderedPins) result.put(key);
        prefs.edit().putString(orderKey, result.toString()).remove(pinnedKey).apply();
    }


    public Set<String> hiddenKeys() {
        synchronized (lock) {
            Set<String> set = prefs.getStringSet(getHiddenKey(currentScope), null);
            if (set == null || set.isEmpty()) {
                return new HashSet<>();
            }
            return new HashSet<>(set);
        }
    }


    public boolean isHidden(String key) {
        if (key == null || key.isEmpty()) return false;
        synchronized (lock) {
            Set<String> hidden = prefs.getStringSet(getHiddenKey(currentScope), Collections.emptySet());
            return hidden.contains(key);
        }
    }


    public void setHidden(String key, boolean hidden) {
        if (key == null || key.isEmpty()) return;
        synchronized (lock) {
            Set<String> current = hiddenKeys();
            if (hidden) {
                current.add(key);
            } else {
                current.remove(key);
            }
            prefs.edit().putStringSet(getHiddenKey(currentScope), current).apply();
        }
    }

    public List<String> getOrderedKeys() {
        synchronized (lock) {
            String json = prefs.getString(getOrderedKey(currentScope), null);
            if (json == null || json.isEmpty()) {
                return Collections.emptyList();
            }
            try {
                JSONArray array = new JSONArray(json);
                List<String> list = new ArrayList<>(array.length());
                for (int i = 0; i < array.length(); i++) {
                    String key = array.optString(i, null);
                    if (key != null && !key.isEmpty()) {
                        list.add(key);
                    }
                }
                return list;
            } catch (JSONException e) {
                return Collections.emptyList();
            }
        }
    }

    public void saveOrderedTargets(List<ShareTarget> visibleTargets) {
        if (visibleTargets == null || visibleTargets.isEmpty()) {
            return;
        }
        List<String> visibleKeys = new ArrayList<>(visibleTargets.size());
        for (ShareTarget t : visibleTargets) {
            if (t != null && t.key != null && !t.key.isEmpty()) {
                visibleKeys.add(t.key);
            }
        }
        saveOrderedKeys(visibleKeys);
    }

    public void saveOrderedKeys(List<String> visibleKeys) {
        if (visibleKeys == null || visibleKeys.isEmpty()) {
            return;
        }
        synchronized (lock) {
            List<String> currentStored = getOrderedKeys();
            List<String> merged = mergeOrder(currentStored, visibleKeys);
            JSONArray array = new JSONArray();
            for (String key : merged) {
                array.put(key);
            }
            prefs.edit().putString(getOrderedKey(currentScope), array.toString()).apply();
        }
    }

    static List<String> mergeOrder(List<String> currentStored, List<String> visibleKeys) {
        List<String> ordered = new ArrayList<>(visibleKeys.size());
        Set<String> visible = new HashSet<>();
        for (String key : visibleKeys) {
            if (visible.add(key)) ordered.add(key);
        }
        List<String> merged = new ArrayList<>(currentStored.size() + ordered.size());
        int nextVisible = 0;
        for (String key : currentStored) {
            merged.add(visible.contains(key) ? ordered.get(nextVisible++) : key);
        }
        while (nextVisible < ordered.size()) {
            merged.add(ordered.get(nextVisible++));
        }
        return merged;
    }

    public List<ShareTarget> arrange(List<ShareTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> hidden = hiddenKeys();

        List<ShareTarget> visible = new ArrayList<>();
        for (ShareTarget target : targets) {
            if (target == null) continue;
            if (hidden.contains(target.key)) {
                continue;
            }
            visible.add(target);
        }

        sortTargetsInternal(visible);
        return visible;
    }

    public void sortSavedTargets(List<ShareTarget> targets) {
        if (targets == null || targets.size() <= 1) {
            return;
        }
        sortTargetsInternal(targets);
    }

    private void sortTargetsInternal(List<ShareTarget> targets) {
        List<String> orderedKeys = getOrderedKeys();
        Map<String, Integer> rankMap;
        if (!orderedKeys.isEmpty()) {
            rankMap = new HashMap<>(orderedKeys.size());
            for (int i = 0; i < orderedKeys.size(); i++) {
                rankMap.putIfAbsent(orderedKeys.get(i), i);
            }
        } else {
            rankMap = Collections.emptyMap();
        }

        Collator collator = Collator.getInstance(Locale.getDefault());

        targets.sort((t1, t2) -> {
            Integer r1 = rankMap.get(t1.key);
            Integer r2 = rankMap.get(t2.key);

            if (r1 != null && r2 != null) {
                int cmp = Integer.compare(r1, r2);
                if (cmp != 0) {
                    return cmp;
                }
            } else if (r1 != null) {
                return -1;
            } else if (r2 != null) {
                return 1;
            }

            int appDiff = collator.compare(t1.appLabel, t2.appLabel);
            if (appDiff != 0) {
                return appDiff;
            }

            int actDiff = collator.compare(t1.activityLabel, t2.activityLabel);
            if (actDiff != 0) {
                return actDiff;
            }

            return t1.key.compareTo(t2.key);
        });
    }
}
