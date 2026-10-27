package com.merxury.sharepanel;

import android.app.Activity;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.net.Uri;
import android.os.Parcelable;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Encapsulates the inbound share request from Intent.ACTION_CHOOSER.
 */
public final class ShareRequest {
    public final String title;
    public final String description;
    public final RuleScope ruleScope;

    private final List<Intent> payloadIntents;
    private final List<Intent> initialIntents;
    private final Set<ComponentName> excludedComponents;
    private final IntentSender chosenComponentSender;

    private ShareRequest(String title,
                         String description,
                         List<Intent> payloadIntents,
                         List<Intent> initialIntents,
                         Set<ComponentName> excludedComponents,
                         IntentSender chosenComponentSender,
                         RuleScope ruleScope) {
        this.title = title;
        this.description = description;
        this.payloadIntents = payloadIntents;
        this.initialIntents = initialIntents;
        this.excludedComponents = excludedComponents;
        this.chosenComponentSender = chosenComponentSender;
        this.ruleScope = ruleScope;
    }

    public static ShareRequest fromIntent(Intent chooser) throws IllegalArgumentException {
        if (chooser == null) {
            throw new IllegalArgumentException("Chooser intent cannot be null");
        }

        Intent primaryIntent = null;
        try {
            Parcelable extra = chooser.getParcelableExtra(Intent.EXTRA_INTENT);
            if (extra instanceof Intent) {
                primaryIntent = (Intent) extra;
            }
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("无法读取分享内容", error);
        }

        if (primaryIntent == null) {
            String directAction = chooser.getAction();
            if (Intent.ACTION_SEND.equals(directAction) || Intent.ACTION_SEND_MULTIPLE.equals(directAction)
                    || Intent.ACTION_VIEW.equals(directAction) || Intent.ACTION_EDIT.equals(directAction)
                    || Intent.ACTION_PROCESS_TEXT.equals(directAction)) {
                primaryIntent = chooser;
            }
        }

        if (primaryIntent == null) {
            throw new IllegalArgumentException("No valid embedded share intent found in Intent.EXTRA_INTENT");
        }

        String action = primaryIntent.getAction();
        if (action == null || action.trim().isEmpty()) {
            throw new IllegalArgumentException("Embedded target intent has empty action");
        }
        RuleScope ruleScope = RuleScope.fromIntent(primaryIntent);

        // Parse title
        CharSequence rawTitle = null;
        try {
            rawTitle = chooser.getCharSequenceExtra(Intent.EXTRA_TITLE);
            if (rawTitle == null || rawTitle.length() == 0) {
                rawTitle = primaryIntent.getCharSequenceExtra(Intent.EXTRA_TITLE);
            }
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("无法读取分享标题", error);
        }
        String resolvedTitle = (rawTitle != null && rawTitle.length() > 0) ? rawTitle.toString() : ruleScope.label;

        // Parse description
        String resolvedDescription = buildDescription(primaryIntent);

        // Parse payload intents: primary + EXTRA_ALTERNATE_INTENTS
        List<Intent> payloads = new ArrayList<>();
        payloads.add(primaryIntent);
        try {
            Parcelable[] altArray = chooser.getParcelableArrayExtra(Intent.EXTRA_ALTERNATE_INTENTS);
            if (altArray != null) {
                for (Parcelable p : altArray) {
                    if (p instanceof Intent) {
                        Intent alt = (Intent) p;
                        if (alt.getAction() != null && !alt.getAction().trim().isEmpty()) {
                            payloads.add(alt);
                        }
                    }
                }
            }
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("无法读取替代分享内容", error);
        }

        // Parse EXTRA_INITIAL_INTENTS
        List<Intent> initials = new ArrayList<>();
        try {
            Parcelable[] initArray = chooser.getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS);
            if (initArray != null) {
                for (Parcelable p : initArray) {
                    if (p instanceof Intent) {
                        initials.add((Intent) p);
                    }
                }
            }
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("无法读取附加分享入口", error);
        }

        // Parse EXTRA_EXCLUDE_COMPONENTS
        Set<ComponentName> excluded = new HashSet<>();
        try {
            Parcelable[] exclArray = chooser.getParcelableArrayExtra(Intent.EXTRA_EXCLUDE_COMPONENTS);
            if (exclArray != null) {
                for (Parcelable p : exclArray) {
                    if (p instanceof ComponentName) {
                        excluded.add((ComponentName) p);
                    }
                }
            }
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("无法读取排除的分享入口", error);
        }

        // Parse callbacks
        IntentSender chosenSender = null;
        try {
            chosenSender = chooser.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT_INTENT_SENDER);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("无法读取分享回调", error);
        }

        return new ShareRequest(
                resolvedTitle,
                resolvedDescription,
                Collections.unmodifiableList(payloads),
                Collections.unmodifiableList(initials),
                Collections.unmodifiableSet(excluded),
                chosenSender,
                ruleScope
        );
    }

    public List<Intent> payloadIntents() {
        return payloadIntents;
    }

    public List<Intent> initialIntents() {
        return initialIntents;
    }

    public Set<ComponentName> excludedComponents() {
        return excludedComponents;
    }

    public void notifySelected(Context context, ComponentName component) {
        if (context == null || component == null || chosenComponentSender == null) return;
        Intent fillIn = new Intent();
        fillIn.putExtra(Intent.EXTRA_CHOSEN_COMPONENT, component);

        try {
            chosenComponentSender.sendIntent(context, Activity.RESULT_OK, fillIn, null, null);
        } catch (IntentSender.SendIntentException error) {
            Log.w("SharePanel", "The caller's selection callback is no longer available", error);
        }
    }

    private static String buildDescription(Intent target) {
        String action = target.getAction();
        String mime = target.getType();
        CharSequence text = null;

        try {
            text = target.getCharSequenceExtra(Intent.EXTRA_TEXT);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("无法读取分享文本", error);
        }

        ClipData clipData = null;
        try {
            clipData = target.getClipData();
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("无法读取分享附件", error);
        }

        if ((text == null || text.length() == 0) && clipData != null) {
            for (int i = 0; i < clipData.getItemCount(); i++) {
                ClipData.Item item = clipData.getItemAt(i);
                if (item != null && item.getText() != null && item.getText().length() > 0) {
                    text = item.getText();
                    break;
                }
            }
        }

        int uriCount = 0;
        if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            try {
                ArrayList<Uri> streams = target.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (streams != null) {
                    for (Uri u : streams) {
                        if (u != null) uriCount++;
                    }
                }
            } catch (RuntimeException error) {
                throw new IllegalArgumentException("无法读取多文件分享", error);
            }
        } else if (Intent.ACTION_SEND.equals(action)) {
            try {
                Uri stream = target.getParcelableExtra(Intent.EXTRA_STREAM);
                if (stream != null) {
                    uriCount = 1;
                }
            } catch (RuntimeException error) {
                throw new IllegalArgumentException("无法读取文件分享", error);
            }
        }

        if (uriCount == 0 && target.getData() != null) {
            uriCount = 1;
        }

        if (uriCount == 0 && clipData != null) {
            for (int i = 0; i < clipData.getItemCount(); i++) {
                ClipData.Item item = clipData.getItemAt(i);
                if (item != null && item.getUri() != null) {
                    uriCount++;
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        if (uriCount > 0) {
            String itemType;
            if (mime != null) {
                if (mime.startsWith("image/")) itemType = "张图片";
                else if (mime.startsWith("video/")) itemType = "个视频";
                else if (mime.startsWith("audio/")) itemType = "个音频";
                else itemType = "个文件";
            } else {
                itemType = "项内容";
            }
            sb.append(uriCount).append(" ").append(itemType);

            if (text != null && text.length() > 0) {
                String clean = text.toString().replace('\n', ' ').trim();
                if (!clean.isEmpty()) {
                    if (clean.length() > 40) clean = clean.substring(0, 40) + "…";
                    sb.append(" · ").append(clean);
                }
            }
        } else if (text != null && text.length() > 0) {
            String clean = text.toString().replace('\n', ' ').trim();
            if (clean.length() > 50) clean = clean.substring(0, 50) + "…";
            sb.append(clean);
        } else if (mime != null && !mime.isEmpty()) {
            sb.append(mime);
        } else {
            sb.append("分享内容");
        }

        return sb.toString();
    }
}
