package com.merxury.sharepanel;

import android.content.Intent;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Independent rule namespace for an operation and its content type. */
public final class RuleScope {
    public static final RuleScope DEFAULT_SHARE = new RuleScope("share", "default");
    public static final List<RuleScope> PRESETS = Collections.unmodifiableList(Arrays.asList(
            DEFAULT_SHARE,
            fromKey("share:image/*"),
            fromKey("share:video/*"),
            fromKey("share:audio/*"),
            fromKey("share:text/*"),
            fromKey("open:application/pdf"),
            fromKey("open:text/*"),
            fromKey("open:application/msword"),
            fromKey("open:application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            fromKey("open:image/*"),
            fromKey("open:video/*"),
            fromKey("open:audio/*"),
            fromKey("open:web")
    ));

    public final String key;
    public final String label;
    private final String operation;

    private RuleScope(String operation, String type) {
        this.operation = operation;
        key = operation + ":" + type;
        label = "default".equals(type) ? "分享 · 默认规则" : actionTitle() + " · " + typeLabel(operation, type);
    }

    public static RuleScope fromIntent(Intent intent) {
        String action = intent.getAction();
        String operation;
        if (Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            operation = "share";
        } else if (Intent.ACTION_VIEW.equals(action)) {
            operation = "open";
        } else if (Intent.ACTION_EDIT.equals(action)) {
            operation = "edit";
        } else if (Intent.ACTION_PROCESS_TEXT.equals(action)) {
            operation = "process_text";
        } else {
            operation = "other";
        }

        String type = intent.getType();
        if (type != null) {
            int separator = type.indexOf(';');
            type = (separator < 0 ? type : type.substring(0, separator)).trim().toLowerCase(Locale.ROOT);
        }
        if (type == null || type.isEmpty()) {
            String scheme = intent.getScheme();
            if ("open".equals(operation) && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                type = "web";
            } else if (intent.hasExtra(Intent.EXTRA_TEXT) || Intent.ACTION_PROCESS_TEXT.equals(action)) {
                type = "text/*";
            } else {
                type = "unknown";
            }
        }
        if (type.startsWith("image/")) type = "image/*";
        else if (type.startsWith("video/")) type = "video/*";
        else if (type.startsWith("audio/")) type = "audio/*";
        else if (type.startsWith("text/")) type = "text/*";
        return new RuleScope(operation, type);
    }

    public static RuleScope fromKey(String key) {
        if (key == null || DEFAULT_SHARE.key.equals(key)) return DEFAULT_SHARE;
        int separator = key.indexOf(':');
        if (separator < 1 || separator == key.length() - 1) return DEFAULT_SHARE;
        String operation = key.substring(0, separator);
        if (!"share".equals(operation) && !"open".equals(operation) && !"edit".equals(operation)
                && !"process_text".equals(operation) && !"other".equals(operation)) {
            return DEFAULT_SHARE;
        }
        return new RuleScope(operation, key.substring(separator + 1));
    }

    public String actionTitle() {
        switch (operation) {
            case "share": return "分享";
            case "open": return "打开方式";
            case "edit": return "编辑方式";
            case "process_text": return "文本处理";
            default: return "其他操作";
        }
    }

    private static String typeLabel(String operation, String type) {
        switch (type) {
            case "image/*": return "图片";
            case "video/*": return "视频";
            case "audio/*": return "音频";
            case "text/*": return "share".equals(operation) ? "文字" : "TXT / 文本";
            case "application/pdf": return "PDF";
            case "application/msword": return "DOC（Word）";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document": return "DOCX（Word）";
            case "web": return "网页链接";
            case "unknown": return "未指定类型";
            default: return type;
        }
    }

    @Override public String toString() { return label; }
    @Override public int hashCode() { return key.hashCode(); }
    @Override public boolean equals(Object other) {
        return other instanceof RuleScope && key.equals(((RuleScope) other).key);
    }
}
