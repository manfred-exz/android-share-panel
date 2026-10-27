package com.merxury.sharepanel;

import android.app.AlertDialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Adapter for rendering share targets and their context actions.
 */
public final class TargetAdapter extends BaseAdapter {

    public interface OnTargetActionListener {
        void onTargetClick(ShareTarget target);
        void onMoveTarget(ShareTarget target, boolean toTop);
        void onHideTarget(ShareTarget target);
        boolean isDispatchingShare();
        boolean onStartDrag(ShareTarget target, int position, View itemView);
    }

    private final Context context;
    private final LayoutInflater inflater;
    private final RuleStore ruleStore;
    private final IconLoader iconLoader;
    private final OnTargetActionListener listener;
    private final List<ShareTarget> targets = new ArrayList<>();

    public TargetAdapter(Context context,
                         RuleStore ruleStore,
                         IconLoader iconLoader,
                         OnTargetActionListener listener) {
        this.context = Objects.requireNonNull(context, "context cannot be null");
        this.inflater = LayoutInflater.from(context);
        this.ruleStore = Objects.requireNonNull(ruleStore, "ruleStore cannot be null");
        this.iconLoader = Objects.requireNonNull(iconLoader, "iconLoader cannot be null");
        this.listener = Objects.requireNonNull(listener, "listener cannot be null");
    }

    public void setTargets(List<ShareTarget> newTargets) {
        targets.clear();
        if (newTargets != null) {
            targets.addAll(newTargets);
        }
        notifyDataSetChanged();
    }

    public List<ShareTarget> getTargets() {
        return new ArrayList<>(targets);
    }

    public void moveItem(int fromPosition, int toPosition) {
        if (fromPosition < 0 || fromPosition >= targets.size()
                || toPosition < 0 || toPosition >= targets.size()
                || fromPosition == toPosition) {
            return;
        }
        ShareTarget item = targets.remove(fromPosition);
        targets.add(toPosition, item);
        notifyDataSetChanged();
    }

    private String draggingKey = null;

    public void setDraggingKey(String key) {
        this.draggingKey = key;
    }

    public String getDraggingKey() {
        return draggingKey;
    }

    @Override
    public int getCount() {
        return targets.size();
    }

    @Override
    public ShareTarget getItem(int position) {
        return targets.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_share_target, parent, false);
            holder = new ViewHolder(convertView);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        ShareTarget target = getItem(position);
        bindView(holder, target, position);
        return convertView;
    }

    private void bindView(ViewHolder holder, ShareTarget target, int position) {
        // Visual drag feedback with recycling safety
        boolean isDraggingThis = draggingKey != null && target.key.equals(draggingKey);
        boolean isHidden = context instanceof SettingsActivity && ruleStore.isHidden(target.key);
        holder.itemView.setAlpha(isDraggingThis ? 0.25f : isHidden ? 0.55f : 1.0f);
        // App label
        holder.tvAppLabel.setText(target.appLabel != null && !target.appLabel.isEmpty()
                ? target.appLabel
                : target.component.getPackageName());

        holder.tvPinnedBadge.setText(R.string.hidden_badge);
        holder.tvPinnedBadge.setVisibility(isHidden ? View.VISIBLE : View.GONE);

        // Disambiguation: activity label or component identity
        String disambiguationText = resolveDisambiguation(target);
        if (disambiguationText != null && !disambiguationText.isEmpty()) {
            holder.tvActivityLabel.setText(disambiguationText);
            holder.tvActivityLabel.setVisibility(View.VISIBLE);
        } else {
            holder.tvActivityLabel.setVisibility(View.GONE);
        }

        // Component name for accessibility / detailed inspection
        holder.tvComponentName.setText(target.component.flattenToShortString());
        holder.tvComponentName.setVisibility(View.GONE);

        // Async icon loading with recycling safety
        iconLoader.loadIcon(target, holder.ivIcon);

        // Row click -> launch
        holder.itemView.setOnClickListener(v -> {
            if (listener.isDispatchingShare()) return;
            listener.onTargetClick(target);
        });

        // Long press -> show action dialog
        holder.itemView.setOnLongClickListener(v -> {
            if (listener.isDispatchingShare()) return false;
            showActionDialog(target);
            return true;
        });

        // Overflow button -> show action dialog
        holder.btnOverflow.setOnClickListener(v -> {
            if (listener.isDispatchingShare()) return;
            showActionDialog(target);
        });

        // Drag handle touch -> start native drag session
        holder.ivDragHandle.setOnTouchListener((v, event) -> {
            if (listener.isDispatchingShare()) {
                return false;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                return listener.onStartDrag(target, position, holder.itemView);
            }
            return false;
        });

        // Drag handle click -> accessibility hint
        holder.ivDragHandle.setOnClickListener(v -> {
            Toast.makeText(context, R.string.desc_drag_handle, Toast.LENGTH_SHORT).show();
        });
    }

    private String resolveDisambiguation(ShareTarget target) {
        if (target.activityLabel != null
                && !target.activityLabel.isEmpty()
                && !target.activityLabel.equals(target.appLabel)) {
            return target.activityLabel;
        }
        // Fallback to short class name if activity label matches app label
        String shortClassName = target.component.getShortClassName();
        if (shortClassName != null && !shortClassName.isEmpty()) {
            return shortClassName;
        }
        return target.component.getPackageName();
    }

    private void showActionDialog(ShareTarget target) {
        String hideActionText = context.getString(
                context instanceof SettingsActivity && ruleStore.isHidden(target.key)
                        ? R.string.action_unhide : R.string.action_hide);

        String[] options = new String[]{
                context.getString(R.string.move_to_top),
                context.getString(R.string.move_to_bottom),
                hideActionText
        };

        new AlertDialog.Builder(context)
                .setTitle(target.appLabel != null && !target.appLabel.isEmpty() ? target.appLabel : target.component.getPackageName())
                .setItems(options, (dialog, which) -> {
                    if (which == 0) listener.onMoveTarget(target, true);
                    else if (which == 1) listener.onMoveTarget(target, false);
                    else listener.onHideTarget(target);
                })
                .setNegativeButton(R.string.action_close, null)
                .show();
    }

    private static final class ViewHolder {
        final View itemView;
        final ImageView ivIcon;
        final TextView tvAppLabel;
        final TextView tvPinnedBadge;
        final TextView tvActivityLabel;
        final TextView tvComponentName;
        final ImageButton btnOverflow;
        final ImageView ivDragHandle;

        ViewHolder(View view) {
            this.itemView = view;
            this.ivIcon = view.findViewById(R.id.iv_app_icon);
            this.tvAppLabel = view.findViewById(R.id.tv_app_label);
            this.tvPinnedBadge = view.findViewById(R.id.tv_pinned_badge);
            this.tvActivityLabel = view.findViewById(R.id.tv_activity_label);
            this.tvComponentName = view.findViewById(R.id.tv_component_name);
            this.btnOverflow = view.findViewById(R.id.btn_overflow);
            this.ivDragHandle = view.findViewById(R.id.iv_drag_handle);
        }
    }
}
