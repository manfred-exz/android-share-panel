package com.merxury.sharepanel;

import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Thread-safe, bounded in-memory icon loader with recycled-view safety.
 */
public final class IconLoader {
    private static final int MAX_CACHE_ENTRIES = 120;

    private final TargetRepository repository;
    private final LruCache<String, Drawable> iconCache;
    private final ExecutorService executor;
    private final Handler mainHandler;

    public IconLoader(TargetRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository cannot be null");
        this.iconCache = new LruCache<>(MAX_CACHE_ENTRIES);
        this.executor = Executors.newFixedThreadPool(3);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * Binds icon to the target ImageView asynchronously.
     * Uses view tag matching to prevent stale icon display in recycled ListView rows.
     */
    public void loadIcon(ShareTarget target, ImageView imageView) {
        if (imageView == null) return;
        if (target == null) {
            imageView.setTag(null);
            imageView.setImageResource(R.drawable.ic_app_placeholder);
            return;
        }

        Drawable cached = iconCache.get(target.key);
        if (cached != null) {
            imageView.setTag(target.key);
            imageView.setImageDrawable(cached);
            return;
        }

        // Set tag and initial placeholder
        imageView.setTag(target.key);
        imageView.setImageResource(R.drawable.ic_app_placeholder);

        final String expectedKey = target.key;
        executor.execute(() -> {
            Drawable icon = null;
            try {
                icon = repository.loadIcon(target);
            } catch (Exception ignored) {
                // Keep placeholder on load failure
            }

            final Drawable finalIcon = icon;
            if (finalIcon != null) {
                iconCache.put(expectedKey, finalIcon);
            }

            mainHandler.post(() -> {
                // View recycling check
                if (Objects.equals(imageView.getTag(), expectedKey)) {
                    if (finalIcon != null) {
                        imageView.setImageDrawable(finalIcon);
                    } else {
                        imageView.setImageResource(R.drawable.ic_app_placeholder);
                    }
                }
            });
        });
    }

    public void clear() {
        iconCache.evictAll();
    }

    public void destroy() {
        executor.shutdown();
    }
}
