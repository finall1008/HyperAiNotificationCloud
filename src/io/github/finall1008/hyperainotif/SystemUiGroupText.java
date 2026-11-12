package io.github.finall1008.hyperainotif;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Renders one merged multi-line TextView over the collapsed single-line slots inside
 * NotificationChildrenContainer while keeping native child heights, card stack, clipping,
 * and expand/collapse transitions completely untouched.
 */
final class SystemUiGroupText {
    private static final String TAG = "HyperAiNotifCloud";

    private final ModuleMain module;
    private final Class<?> rowClass;
    private final Method getEntry;
    private final Method getChildren;
    private final Method getParent;
    private final Method getChildrenContainer;
    private final Method getSingleLine;
    private final Method shouldShowPublic;
    private final Method isGroupExpansionChanging;
    private final Method showingAsLowPriority;
    private final Method isBundle;
    private final Method getCollapsedHeaderMargin;
    private final Method getMaxAllowedVisibleChildren;
    private final Method getHybridTextView;
    private final Method getHybridTitleView;
    private final Method addImage;
    private final Method showMiuiStyle;
    private final Field sbnField;
    private final Field containingRow;
    private final Field childrenExpandedField;
    private final Field childPaddingField;
    private final Field headerTranslationField;
    private final Field minSingleLineHeightField;
    private final Field overflowNumberField;
    private final Field injectorField;
    private final Field overflowMarginEndField;
    private final TimeInterpolator expandInterpolator;
    private final TimeInterpolator collapseInterpolator;

    private final WeakHashMap<ViewGroup, OverlayState> overlays = new WeakHashMap<>();
    private final Set<View> hiddenHybrids = Collections.newSetFromMap(new WeakHashMap<>());

    static void install(ModuleMain module, ClassLoader loader) {
        try {
            new SystemUiGroupText(module, loader);
            module.log(Log.INFO, TAG, "Hooked merged group summary view");
        } catch (Exception e) {
            module.log(Log.ERROR, TAG, "Failed to hook merged group summary view", e);
        }
    }

    private SystemUiGroupText(ModuleMain module, ClassLoader loader) throws Exception {
        this.module = module;
        rowClass = loader.loadClass("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow");
        Class<?> containerClass = loader.loadClass(
                "com.android.systemui.statusbar.notification.stack.NotificationChildrenContainer");
        Class<?> hybridClass = loader.loadClass(
                "com.android.systemui.statusbar.notification.row.HybridNotificationView");
        Class<?> entryClass = loader.loadClass(
                "com.android.systemui.statusbar.notification.collection.NotificationEntry");

        getEntry = ModuleMain.findMethod(rowClass, "getEntry");
        getChildren = ModuleMain.findMethod(rowClass, "getAttachedChildren");
        getParent = ModuleMain.findMethod(rowClass, "getNotificationParent");
        getChildrenContainer = ModuleMain.findMethod(rowClass, "getChildrenContainer");
        getSingleLine = ModuleMain.findMethod(rowClass, "getSingleLineView");
        shouldShowPublic = ModuleMain.findMethod(rowClass, "shouldShowPublic");
        isGroupExpansionChanging = ModuleMain.findMethod(rowClass, "isGroupExpansionChanging");
        sbnField = ModuleMain.findField(entryClass, "mSbn");

        containingRow = ModuleMain.findField(containerClass, "mContainingNotification");
        childrenExpandedField = ModuleMain.findField(containerClass, "mChildrenExpanded");
        childPaddingField = ModuleMain.findField(containerClass, "mChildPadding");
        headerTranslationField = ModuleMain.findField(containerClass, "mCurrentHeaderTranslation");
        minSingleLineHeightField = ModuleMain.findField(containerClass, "mMinSingleLineHeight");
        overflowNumberField = ModuleMain.findField(containerClass, "mOverflowNumber");
        injectorField = ModuleMain.findField(containerClass, "mInjector");
        Field marginEnd = null;
        if (injectorField != null) {
            marginEnd = ModuleMain.findField(injectorField.getType(), "mOverflowNumberMarginEnd");
        }
        overflowMarginEndField = marginEnd;

        showingAsLowPriority = ModuleMain.findMethod(containerClass, "showingAsLowPriority");
        isBundle = ModuleMain.findMethod(containerClass, "isBundle");
        getCollapsedHeaderMargin = ModuleMain.findMethod(containerClass, "getCollapsedHeaderMargin");
        getMaxAllowedVisibleChildren = ModuleMain.findMethod(
                containerClass, "getMaxAllowedVisibleChildren", boolean.class);

        getHybridTextView = ModuleMain.findMethod(hybridClass, "getTextView");
        getHybridTitleView = ModuleMain.findMethod(hybridClass, "getTitleView");

        Method badgeMethod = null;
        try {
            Class<?> binderCompanion = loader.loadClass(
                    "com.android.systemui.statusbar.notification.row.NotificationRowContentBinderInjectorImpl$Companion");
            badgeMethod = ModuleMain.findMethod(binderCompanion, "addImage", Context.class, CharSequence.class);
        } catch (Throwable ignored) {}
        addImage = badgeMethod;

        Method miuiStyleMethod = null;
        try {
            Class<?> helper = loader.loadClass("com.miui.systemui.notification.NotificationSettingsHelper");
            miuiStyleMethod = ModuleMain.findMethod(helper, "showMiuiStyle");
        } catch (Throwable ignored) {}
        showMiuiStyle = miuiStyleMethod;

        TimeInterpolator expInterp = new DecelerateInterpolator();
        TimeInterpolator colInterp = new AccelerateInterpolator();
        try {
            Class<?> animExt = loader.loadClass(
                    "com.android.systemui.statusbar.notification.stack.MiuiNotificationAnimationExtensionsKt");
            Field expField = ModuleMain.findField(animExt, "GROUP_ELEMENT_INTERPOLATOR");
            Field colField = ModuleMain.findField(animExt, "GROUP_ELEMENT_COLLAPSE_INTERPOLATOR");
            if (expField != null && expField.get(null) instanceof TimeInterpolator ti) expInterp = ti;
            if (colField != null && colField.get(null) instanceof TimeInterpolator ti) colInterp = ti;
        } catch (Throwable ignored) {}
        expandInterpolator = expInterp;
        collapseInterpolator = colInterp;

        hookHybridView(hybridClass);
        hookContainer(containerClass);
        hookRow(rowClass);
    }

    private void hookHybridView(Class<?> hybridClass) {
        for (Method method : hybridClass.getDeclaredMethods()) {
            String name = method.getName();
            Class<?>[] params = method.getParameterTypes();
            if (!name.equals("bind") && !name.equals("setVisible")
                    && !name.equals("transformTo") && !name.equals("transformFrom")) continue;
            method.setAccessible(true);
            boolean dragTransform = (name.equals("transformTo") || name.equals("transformFrom"))
                    && params.length == 2 && params[0] == float.class;
            module.hook(method).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    View hybrid = (View) chain.getThisObject();
                    Object row = findRow(hybrid);
                    if (row == null) return result;
                    Object parent = getParent.invoke(row);
                    if (parent == null) return result;
                    if (name.equals("bind")) {
                        update(parent);
                        return result;
                    }
                    List<?> children = (List<?>) getChildren.invoke(parent);
                    if (children == null || children.isEmpty() || children.get(0) != row) return result;
                    ViewGroup container = (ViewGroup) getChildrenContainer.invoke(parent);
                    OverlayState state = container == null ? null : overlays.get(container);
                    if (state == null || state.rawText == null) return result;

                    if (name.equals("setVisible")) {
                        boolean visible = (Boolean) chain.getArg(0);
                        cancelAnimation(state);
                        state.view.setTranslationY(0f);
                        state.view.setAlpha(visible ? 1f : 0f);
                        state.view.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
                    } else if (dragTransform) {
                        syncDrag(state, hybrid);
                    } else if (name.equals("transformTo")) {
                        startFade(state, false);
                    } else if (name.equals("transformFrom")) {
                        startFade(state, true);
                    }
                } catch (Exception e) {
                    module.log(Log.ERROR, TAG, "Failed to sync hybrid transition", e);
                }
                return result;
            });
        }
    }

    private void hookContainer(Class<?> containerClass) {
        for (Method method : containerClass.getDeclaredMethods()) {
            String name = method.getName();
            if (!name.equals("onMeasure") && !name.equals("onLayout")
                    && !name.equals("updateState") && !name.equals("onNotificationUpdated")
                    && !name.equals("updateChildrenAppearance") && !name.equals("removeNotification")
                    && !name.equals("onConfigurationChanged")) continue;
            method.setAccessible(true);
            module.hook(method).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    ViewGroup container = (ViewGroup) chain.getThisObject();
                    switch (name) {
                        case "onMeasure" -> {
                            OverlayState state = overlays.get(container);
                            if (state != null && state.view.getVisibility() != View.GONE) {
                                int width = View.MeasureSpec.getSize((Integer) chain.getArg(0));
                                measureOverlay(container, state, width, false);
                            }
                        }
                        case "onLayout" -> {
                            OverlayState state = overlays.get(container);
                            if (state != null && state.view.getVisibility() != View.GONE) {
                                int width = (Integer) chain.getArg(3) - (Integer) chain.getArg(1);
                                measureOverlay(container, state, width, true);
                            }
                        }
                        case "removeNotification" -> {
                            Object removed = chain.getArg(0);
                            if (removed != null) restoreSingleLine((View) getSingleLine.invoke(removed));
                            update(containingRow.get(container));
                        }
                        default -> update(containingRow.get(container));
                    }
                } catch (Exception e) {
                    module.log(Log.ERROR, TAG, "Failed in container hook: " + name, e);
                }
                return result;
            });
        }
    }

    private void hookRow(Class<?> rowClass) {
        for (Method method : rowClass.getDeclaredMethods()) {
            String name = method.getName();
            if (!name.equals("addChildNotification") && !name.equals("onNotificationUpdated")
                    && !name.equals("setHideSensitive") && !name.equals("setSensitive")
                    && !name.equals("setIsSummaryWithChildren")) continue;
            method.setAccessible(true);
            module.hook(method).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object row = chain.getThisObject();
                    Object parent = getParent.invoke(row);
                    update(parent == null ? row : parent);
                } catch (Exception e) {
                    module.log(Log.ERROR, TAG, "Failed to refresh group summary row state", e);
                }
                return result;
            });
        }
    }

    private Object findRow(View view) {
        ViewParent parent = view.getParent();
        while (parent != null) {
            if (rowClass.isInstance(parent)) return parent;
            parent = parent.getParent();
        }
        return null;
    }

    private StatusBarNotification sbn(Object row) throws Exception {
        Object entry = getEntry.invoke(row);
        return entry == null ? null : (StatusBarNotification) sbnField.get(entry);
    }

    private int maxCollapsedSlots(ViewGroup container) throws Exception {
        return Math.max(2, (Integer) getMaxAllowedVisibleChildren.invoke(container, true));
    }

    private String summaryFor(ViewGroup container, Object parent, List<?> children) throws Exception {
        if (container == null || parent == null || children == null || children.size() < 2) return null;
        if ((Boolean) showingAsLowPriority.invoke(container)) return null;
        if (isBundle != null && (Boolean) isBundle.invoke(container)) return null;
        if ((Boolean) shouldShowPublic.invoke(parent)) return null;
        StatusBarNotification summary = sbn(parent);
        if (summary == null) return null;
        Bundle extras = summary.getNotification().extras;
        String text = extras.getString(GroupSummaryData.SUMMARY);
        if (text == null || text.isBlank()) return null;

        ArrayList<StatusBarNotification> active = new ArrayList<>(children.size());
        for (Object child : children) {
            if ((Boolean) shouldShowPublic.invoke(child)) return null;
            StatusBarNotification notification = sbn(child);
            if (notification == null || !summary.getGroupKey().equals(notification.getGroupKey())) return null;
            active.add(notification);
        }
        return GroupSummaryData.fingerprint(active).equals(extras.getString(GroupSummaryData.FINGERPRINT))
                ? text : null;
    }

    private void update(Object parent) throws Exception {
        if (parent == null) return;
        ViewGroup container = (ViewGroup) getChildrenContainer.invoke(parent);
        List<?> children = (List<?>) getChildren.invoke(parent);
        if (container == null) {
            if (children != null) {
                for (Object child : children) restoreSingleLine((View) getSingleLine.invoke(child));
            }
            return;
        }

        String summaryText = summaryFor(container, parent, children);
        if (summaryText == null) {
            OverlayState state = overlays.get(container);
            if (state != null) {
                cancelAnimation(state);
                state.rawText = null;
                state.view.setAlpha(0f);
                state.view.setVisibility(View.GONE);
            }
            if (children != null) {
                for (Object child : children) restoreSingleLine((View) getSingleLine.invoke(child));
            }
            return;
        }

        View hybrid0 = (View) getSingleLine.invoke(children.get(0));
        if (hybrid0 == null) return;

        int slots = Math.min(children.size(), maxCollapsedSlots(container));
        for (int i = 0; i < children.size(); i++) {
            View hybrid = (View) getSingleLine.invoke(children.get(i));
            if (i < slots) hideSingleLine(hybrid);
            else restoreSingleLine(hybrid);
        }

        OverlayState state = ensureOverlay(container);
        syncStyleAndText(container, state, hybrid0, summaryText, slots);

        if (!state.animating) {
            boolean show = hybrid0.getVisibility() == View.VISIBLE;
            state.view.setTranslationY(0f);
            state.view.setAlpha(show ? 1f : 0f);
            state.view.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        }

        if (container.getWidth() > 0) {
            measureOverlay(container, state, container.getWidth(), true);
            container.invalidate();
        } else {
            container.requestLayout();
        }
    }

    private OverlayState ensureOverlay(ViewGroup container) {
        OverlayState state = overlays.get(container);
        if (state != null && state.view.getParent() == container) return state;
        TextView view = new TextView(container.getContext());
        view.setClickable(false);
        view.setLongClickable(false);
        view.setFocusable(false);
        view.setSingleLine(false);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        view.setTextDirection(View.TEXT_DIRECTION_LOCALE);
        float density = container.getResources().getDisplayMetrics().density;
        view.setLineSpacing(Math.round(2f * density), 1f);
        view.setVisibility(View.GONE);
        container.addView(view);
        OverlayState created = new OverlayState(view);
        container.getViewTreeObserver().addOnPreDrawListener(() -> {
            if (created.rawText != null && created.view.getVisibility() != View.GONE
                    && container.getWidth() > 0) {
                try {
                    measureOverlay(container, created, container.getWidth(), true);
                } catch (Exception e) {
                    module.log(Log.ERROR, TAG, "Failed to align group summary on pre-draw", e);
                }
            }
            return true;
        });
        overlays.put(container, created);
        return created;
    }

    private void syncStyleAndText(ViewGroup container, OverlayState state, View hybrid0,
            String summaryText, int maxLines) throws Exception {
        TextView refText = (TextView) getHybridTextView.invoke(hybrid0);
        if (refText != null) {
            if (state.view.getTextSize() != refText.getTextSize()) {
                state.view.setTextSize(TypedValue.COMPLEX_UNIT_PX, refText.getTextSize());
            }
            if (refText.getTextColors() != null) {
                state.view.setTextColor(refText.getTextColors());
            }
            if (state.view.getIncludeFontPadding() != refText.getIncludeFontPadding()) {
                state.view.setIncludeFontPadding(refText.getIncludeFontPadding());
            }
            Typeface tf = refText.getTypeface();
            Typeface upright = tf != null && tf.isItalic() ? Typeface.create(tf, Typeface.NORMAL) : tf;
            if (!Objects.equals(state.view.getTypeface(), upright)) {
                state.view.setTypeface(upright);
            }
        }
        float density = container.getResources().getDisplayMetrics().density;
        state.view.setLineSpacing(Math.round(2f * density), 1f);
        if (state.view.getMaxLines() != maxLines) {
            state.view.setMaxLines(maxLines);
        }

        Configuration cfg = container.getResources().getConfiguration();
        float fontScale = cfg.fontScale;
        int nightMask = cfg.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if (!summaryText.equals(state.rawText) || state.fontScale != fontScale || state.nightMask != nightMask) {
            state.rawText = summaryText;
            state.fontScale = fontScale;
            state.nightMask = nightMask;
            state.view.setText(formatSummary(container.getContext(), summaryText));
        }
    }

    private CharSequence formatSummary(Context context, String text) {
        if (addImage != null) {
            try {
                Object span = addImage.invoke(null, context, text);
                if (span instanceof CharSequence cs) return cs;
            } catch (Throwable ignored) {}
        }
        return "✨ " + text;
    }

    private void measureOverlay(ViewGroup container, OverlayState state, int containerWidth,
            boolean layout) throws Exception {
        int[] rect = bounds(container, state, containerWidth);
        if (rect == null) return;
        int w = rect[2] - rect[0];
        int h = rect[3] - rect[1];
        if (!layout || state.view.getMeasuredWidth() != w || state.view.getMeasuredHeight() != h) {
            state.view.measure(
                    View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        }
        if (layout && (state.view.getLeft() != rect[0] || state.view.getTop() != rect[1]
                || state.view.getRight() != rect[2] || state.view.getBottom() != rect[3])) {
            state.view.layout(rect[0], rect[1], rect[2], rect[3]);
        }
    }

    private int[] bounds(ViewGroup container, OverlayState state, int containerWidth) throws Exception {
        Object parent = containingRow.get(container);
        if (parent == null || containerWidth <= 0) return null;
        List<?> children = (List<?>) getChildren.invoke(parent);
        if (children == null || children.size() < 2) return null;
        View hybrid0 = (View) getSingleLine.invoke(children.get(0));
        if (hybrid0 == null) return null;

        int maxVisible = maxCollapsedSlots(container);
        int slots = Math.min(children.size(), maxVisible);
        boolean rtl = container.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        int startPad = hybrid0.getPaddingStart();
        int endPad = hybrid0.getPaddingEnd();
        int left = rtl ? endPad : startPad;
        int right = containerWidth - (rtl ? startPad : endPad);

        View overflow = (View) overflowNumberField.get(container);
        if (overflow != null && overflow.getVisibility() != View.GONE
                && overflow.getMeasuredWidth() > 0 && children.size() > maxVisible) {
            int margin = 0;
            if (injectorField != null && overflowMarginEndField != null) {
                Object inj = injectorField.get(container);
                if (inj != null) margin = overflowMarginEndField.getInt(inj);
            }
            int indent = overflow.getMeasuredWidth() + margin;
            if (rtl) left += indent;
            else right -= indent;
        }
        if (right <= left) return null;

        boolean expanded = childrenExpandedField != null && childrenExpandedField.getBoolean(container);
        boolean changing = isGroupExpansionChanging != null
                && (Boolean) isGroupExpansionChanging.invoke(parent);
        View hybridLast = (View) getSingleLine.invoke(children.get(slots - 1));
        if (!expanded && !changing && container.isShown() && hybrid0.getHeight() > 0
                && hybridLast != null && hybridLast.getHeight() > 0) {
            int[] locContainer = new int[2];
            int[] locFirst = new int[2];
            int[] locLast = new int[2];
            container.getLocationOnScreen(locContainer);
            hybrid0.getLocationOnScreen(locFirst);
            hybridLast.getLocationOnScreen(locLast);
            int measuredTop = locFirst[1] - locContainer[1];
            int measuredBottom = (locLast[1] + hybridLast.getHeight()) - locContainer[1];
            if (measuredTop > 0 && measuredBottom > measuredTop) {
                state.collapsedTop = measuredTop;
                state.collapsedBottom = measuredBottom;
                state.cachedSlots = slots;
            }
        }
        if (state.cachedSlots == slots && state.collapsedBottom > state.collapsedTop) {
            return new int[]{left, state.collapsedTop, right, state.collapsedBottom};
        }

        int gap = childPaddingField.getInt(container);
        int fallbackH = minSingleLineHeightField.getInt(container);
        int height = 0;
        for (int i = 0; i < slots; i++) {
            if (i > 0) height += gap;
            View h = (View) getSingleLine.invoke(children.get(i));
            int slotH = h != null && h.getMeasuredHeight() > 0 ? h.getMeasuredHeight()
                    : (h != null && h.getHeight() > 0 ? h.getHeight() : fallbackH);
            height += slotH;
        }
        if (height <= 0) return null;
        int top = isMiuiStyle()
                ? Math.round(13f * container.getResources().getDisplayMetrics().density)
                : (Integer) getCollapsedHeaderMargin.invoke(container)
                        + headerTranslationField.getInt(container);
        return new int[]{left, top, right, top + height};
    }

    private TextView activeInnerText(View hybrid) throws Exception {
        TextView text = (TextView) getHybridTextView.invoke(hybrid);
        if (text != null && text.getVisibility() != View.GONE) return text;
        return (TextView) getHybridTitleView.invoke(hybrid);
    }

    private void syncDrag(OverlayState state, View hybrid) throws Exception {
        cancelAnimation(state);
        TextView ref = activeInnerText(hybrid);
        float alpha = ref != null ? ref.getAlpha() : 0f;
        boolean visible = hybrid.getVisibility() == View.VISIBLE
                && ref != null && ref.getVisibility() == View.VISIBLE && alpha > 0f;
        state.view.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        state.view.setAlpha(alpha);
        state.view.setTranslationY(ref != null ? ref.getTranslationY() : 0f);
    }

    private void startFade(OverlayState state, boolean fadeIn) {
        cancelAnimation(state);
        if (fadeIn) {
            if (state.view.getVisibility() != View.VISIBLE) {
                state.view.setAlpha(0f);
                state.view.setVisibility(View.VISIBLE);
            }
        } else if (state.view.getVisibility() != View.VISIBLE) {
            state.view.setAlpha(0f);
            return;
        }
        state.view.setTranslationY(0f);
        state.animating = true;
        long duration = isMiuiStyle() ? 210L : 150L;
        TimeInterpolator interpolator = fadeIn ? collapseInterpolator : expandInterpolator;
        state.view.animate()
                .alpha(fadeIn ? 1f : 0f)
                .setDuration(duration)
                .setInterpolator(interpolator)
                .setListener(new AnimatorListenerAdapter() {
                    private boolean canceled;

                    @Override
                    public void onAnimationCancel(Animator animation) {
                        canceled = true;
                        state.animating = false;
                    }

                    @Override
                    public void onAnimationEnd(Animator animation) {
                        state.animating = false;
                        state.view.animate().setListener(null);
                        if (!canceled) {
                            state.view.setAlpha(fadeIn ? 1f : 0f);
                            state.view.setVisibility(fadeIn ? View.VISIBLE : View.INVISIBLE);
                        }
                    }
                })
                .start();
    }

    private void cancelAnimation(OverlayState state) {
        if (state.animating) {
            state.animating = false;
            state.view.animate().setListener(null);
            state.view.animate().cancel();
        }
    }

    private boolean isMiuiStyle() {
        if (showMiuiStyle != null) {
            try {
                return (Boolean) showMiuiStyle.invoke(null);
            } catch (Throwable ignored) {}
        }
        return true;
    }

    private void hideSingleLine(View hybrid) {
        if (hybrid == null) return;
        hiddenHybrids.add(hybrid);
        if (hybrid.getAlpha() != 0f) hybrid.setAlpha(0f);
        if (hybrid.getImportantForAccessibility()
                != View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS) {
            hybrid.setImportantForAccessibility(
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
    }

    private void restoreSingleLine(View hybrid) {
        if (hybrid == null || !hiddenHybrids.remove(hybrid)) return;
        hybrid.setAlpha(1f);
        hybrid.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
    }

    private static final class OverlayState {
        final TextView view;
        String rawText;
        float fontScale;
        int nightMask = -1;
        int collapsedTop;
        int collapsedBottom;
        int cachedSlots;
        boolean animating;

        OverlayState(TextView view) {
            this.view = view;
        }
    }
}
