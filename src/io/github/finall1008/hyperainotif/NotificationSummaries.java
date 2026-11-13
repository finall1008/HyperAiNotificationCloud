package io.github.finall1008.hyperainotif;

import android.app.Notification;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/** Owns requests on the NMS handler; reads live records only under the NMS lock. */
final class NotificationSummaries {
    private static final String TAG = "HyperAiNotifCloud";
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final Context context;
    private final Handler handler;
    private final Object service;
    private final Object hyperService;
    private final Object lock;
    private final Object listeners;
    private final Field records;
    private final Method getSbn;
    private final Method notifyPosted;
    private final Constructor<?> aiNotification;
    private final Method setOutput;
    private final Method applySummary;
    private final Map<?, ?> allowedPackages;
    private final AsyncConfig config;
    private final Map<String, String> attempted = new HashMap<>();
    private final Map<String, String> submittedItems = new HashMap<>();
    private final Set<String> inFlight = new HashSet<>();
    private final Map<String, CachedGroup> cachedGroups = new HashMap<>();
    private final SummaryScheduler scheduler;
    private final Runnable reconcile;

    NotificationSummaries(Context context, Object service, Object hyperService,
            ClassLoader loader, Supplier<Bundle> config) throws Exception {
        this.context = context;
        this.service = service;
        this.hyperService = hyperService;
        Class<?> nms = service.getClass();
        Class<?> record = loader.loadClass("com.android.server.notification.NotificationRecord");
        Class<?> ai = loader.loadClass("com.android.server.notification.HyperAINotification");
        Class<?> hyper = hyperService.getClass();
        handler = (Handler) ModuleMain.findField(nms, "mHandler").get(service);
        lock = ModuleMain.findField(nms, "mNotificationLock").get(service);
        records = ModuleMain.findField(nms, "mNotificationList");
        listeners = ModuleMain.findField(nms, "mListeners").get(service);
        getSbn = ModuleMain.findMethod(record, "getSbn");
        notifyPosted = ModuleMain.findMethod(listeners.getClass(), "notifyPostedLocked", record, record);
        aiNotification = ai.getDeclaredConstructor(String.class, String.class, String.class, record);
        aiNotification.setAccessible(true);
        setOutput = ModuleMain.findMethod(ai, "setOutputText", String.class);
        applySummary = ModuleMain.findMethod(hyper, "copeNotificationLocked", ai);
        allowedPackages = (Map<?, ?>) ModuleMain.findField(hyper,
                "mAllowAINotificaitonPackagers").get(null);
        this.config = new AsyncConfig(handler, Executors.newSingleThreadExecutor(), config,
                () -> schedulerRefresh());
        scheduler = new SummaryScheduler(context, handler, this.config, this::dispatch, this::pendingCount);
        reconcile = this.config::refresh;
        android.content.IntentFilter filter = new android.content.IntentFilter();
        filter.addAction(android.content.Intent.ACTION_SCREEN_ON);
        filter.addAction(android.content.Intent.ACTION_SCREEN_OFF);
        filter.addAction(Config.ACTION_CONFIG_CHANGED);
        context.registerReceiver(new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, android.content.Intent intent) {
                refresh();
            }
        }, filter, null, handler, Context.RECEIVER_EXPORTED);
        refresh();
    }

    synchronized void refresh() {
        // Coalesce the post/ranking callbacks belonging to one native regroup operation.
        if (!handler.hasCallbacks(reconcile)) handler.postDelayed(reconcile, 150);
    }

    private Bundle settings() {
        return config.get();
    }

    private void schedulerRefresh() {
        scheduler.refresh();
    }

    private List<Batch> collect(Bundle settings) throws Exception {
        synchronized (lock) {
            Map<String, List<Entry>> groups = new LinkedHashMap<>();
            Map<String, Entry> summaries = new HashMap<>();
            Set<String> liveItems = new HashSet<>();
            boolean allApps = settings.getBoolean(Config.KEY_ALL_APPS, true);
            int minimum = settings.getInt(Config.KEY_MIN_LENGTH, Config.DEFAULT_MIN_LENGTH);
            boolean enabled = settings.getBoolean(Config.KEY_GROUP_SUMMARY, true);
            for (Object record : (List<?>) records.get(service)) {
                StatusBarNotification sbn = (StatusBarNotification) getSbn.invoke(record);
                Notification notification = sbn.getNotification();
                if ((notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
                    summaries.put(sbn.getGroupKey(), new Entry(record, sbn, false));
                    continue;
                }
                boolean eligible = (allApps || allowedPackages.containsKey(sbn.getPackageName()))
                        && (notification.flags & (0x08000000 | 0x40 | 0x2)) == 0;
                if (eligible) GroupSummaryData.rememberOriginal(notification);
                Entry entry = new Entry(record, sbn, eligible);
                groups.computeIfAbsent(sbn.getGroupKey(), key -> new ArrayList<>()).add(entry);
                liveItems.add(sbn.getKey());
            }
            submittedItems.keySet().retainAll(liveItems);
            ArrayList<Batch> batches = new ArrayList<>();
            Set<String> liveGroups = new HashSet<>();
            for (Map.Entry<String, List<Entry>> group : groups.entrySet()) {
                List<Entry> children = group.getValue();
                Entry summary = summaries.get(group.getKey());
                boolean eligible = enabled && summary != null && children.size() >= 2;
                int total = 0;
                for (Entry child : children) {
                    eligible &= child.eligible && !child.text.isEmpty();
                    total += child.text.trim().length();
                }
                eligible &= total > minimum;
                if (eligible) {
                    Batch batch = new Batch("g:" + group.getKey(), summary, children, minimum);
                    batches.add(batch);
                    liveGroups.add(batch.id);
                    CachedGroup cached = cachedGroups.get(batch.id);
                    if (cached != null && !cached.fingerprint.equals(batch.fingerprint)) {
                        cachedGroups.remove(batch.id);
                        cached = null;
                    }
                    if (cached != null) setGroup(summary, batch.fingerprint, cached.summary);
                    else if (!batch.fingerprint.equals(summary.sbn.getNotification().extras
                            .getString(GroupSummaryData.FINGERPRINT))) clearGroup(summary);
                } else {
                    if (summary != null) clearGroup(summary);
                    for (Entry child : children) {
                        if (child.eligible && child.text.trim().length() > minimum
                                && (child.sbn.getNotification().flags & GroupSummaryData.AI_FLAG) == 0) {
                            batches.add(new Batch("s:" + child.sbn.getKey(), null,
                                    List.of(child), minimum));
                        }
                    }
                }
            }
            for (Map.Entry<String, Entry> summary : summaries.entrySet()) {
                if (!groups.containsKey(summary.getKey())) clearGroup(summary.getValue());
            }
            cachedGroups.keySet().retainAll(liveGroups);
            Set<String> liveBatches = new HashSet<>();
            for (Batch batch : batches) liveBatches.add(batch.id);
            attempted.keySet().retainAll(liveBatches);
            return batches;
        }
    }

    private int unsent(Batch batch) {
        if (inFlight.contains(batch.id) || batch.fingerprint.equals(attempted.get(batch.id))) return 0;
        int count = 0;
        for (Entry child : batch.children) {
            if (!child.fingerprint.equals(submittedItems.get(child.sbn.getKey()))) count++;
        }
        // A newly formed group also needs a summary when its children were summarized alone.
        return count == 0 && !attempted.containsKey(batch.id) ? batch.children.size() : count;
    }

    private int pendingCount() {
        try {
            int count = 0;
            for (Batch batch : collect(settings())) count += unsent(batch);
            return count;
        } catch (Exception e) {
            Log.e(TAG, "Failed to collect active notifications", e);
            return 0;
        }
    }

    private void dispatch() {
        try {
            for (Batch batch : collect(settings())) {
                if (unsent(batch) == 0) continue;
                attempted.put(batch.id, batch.fingerprint);
                inFlight.add(batch.id);
                for (Entry entry : batch.children) {
                    submittedItems.put(entry.sbn.getKey(), entry.fingerprint);
                }
                executor.execute(() -> request(batch));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to dispatch summaries", e);
        }
    }

    private void request(Batch batch) {
        Bundle reply = null;
        try {
            Bundle input = new Bundle();
            ArrayList<String> messages = new ArrayList<>();
            ArrayList<String> titles = new ArrayList<>();
            ArrayList<String> categories = new ArrayList<>();
            for (Entry entry : batch.requested) {
                messages.add(entry.text);
                titles.add(entry.title);
                categories.add(category(entry));
            }
            input.putStringArrayList("messageList", messages);
            input.putStringArrayList("titles", titles);
            input.putStringArrayList("category", categories);
            input.putString("package", batch.children.get(0).sbn.getPackageName());
            input.putInt("line_count", Math.min(batch.children.size(), 3));
            input.putInt(Config.KEY_MIN_LENGTH, batch.minimum);
            Log.i(TAG, "Requesting " + (batch.summary == null ? "single" : "group")
                    + " summary: " + batch.requested.size() + " notification(s)");
            reply = context.getContentResolver().call(Uri.parse("content://" + Config.AUTHORITY),
                    batch.summary == null ? "summarize" : "summarize_group", null, input);
        } catch (Exception e) {
            Log.e(TAG, "Cloud summary request failed", e);
        }
        Bundle result = reply;
        handler.post(() -> finish(batch, result));
    }

    private void finish(Batch requested, Bundle reply) {
        try {
            Bundle settings = settings();
            boolean screenOn = settings.getBoolean(Config.KEY_SUMMARIZE_SCREEN_ON,
                    Config.DEFAULT_SUMMARIZE_SCREEN_ON);
            if (!screenOn && context.getSystemService(android.os.PowerManager.class).isInteractive()) {
                attempted.remove(requested.id);
                return;
            }
            if (reply == null || !"0".equals(reply.getString("status"))) return;
            synchronized (lock) {
                Batch current = null;
                for (Batch batch : collect(settings)) {
                    if (batch.id.equals(requested.id) && batch.fingerprint.equals(requested.fingerprint)) {
                        current = batch;
                        break;
                    }
                }
                if (current == null) {
                    Log.i(TAG, "Discarded summary for changed notification membership/content");
                    return;
                }
                ArrayList<String> outputs = reply.getStringArrayList("outputtext_list");
                ArrayList<String> errors = reply.getStringArrayList("errorcode_list");
                if (outputs == null || outputs.size() != requested.requested.size()) return;
                Map<String, Entry> active = new HashMap<>();
                for (Entry child : current.children) active.put(child.sbn.getKey(), child);
                for (int i = 0; i < requested.requested.size(); i++) {
                    Entry entry = active.get(requested.requested.get(i).sbn.getKey());
                    String output = outputs.get(i);
                    boolean success = errors == null || (i < errors.size() && "0".equals(errors.get(i)));
                    if (success && output != null && !output.isBlank()
                            && entry.text.trim().length() > current.minimum) {
                        Object ai = aiNotification.newInstance(entry.text, "notification", category(entry), entry.record);
                        setOutput.invoke(ai, output);
                        applySummary.invoke(hyperService, ai);
                    }
                }
                String groupSummary = reply.getString("group_summary", "");
                if (current.summary != null && !groupSummary.isBlank()) {
                    cachedGroups.put(current.id, new CachedGroup(current.fingerprint, groupSummary));
                    setGroup(current.summary, current.fingerprint, groupSummary);
                }
                Log.i(TAG, "Applied " + (current.summary == null ? "single" : "group") + " cloud summary");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply cloud summary", e);
        } finally {
            inFlight.remove(requested.id);
            refresh();
        }
    }

    private String category(Entry entry) {
        Object category = allowedPackages.get(entry.sbn.getPackageName());
        return category instanceof String ? (String) category : "1_IM";
    }

    private void setGroup(Entry entry, String fingerprint, String summary) throws Exception {
        Bundle extras = entry.sbn.getNotification().extras;
        if (fingerprint.equals(extras.getString(GroupSummaryData.FINGERPRINT))
                && summary.equals(extras.getString(GroupSummaryData.SUMMARY))) return;
        extras.putString(GroupSummaryData.FINGERPRINT, fingerprint);
        extras.putString(GroupSummaryData.SUMMARY, summary);
        notifyPosted.invoke(listeners, entry.record, entry.record);
    }

    private void clearGroup(Entry entry) throws Exception {
        Bundle extras = entry.sbn.getNotification().extras;
        if (!extras.containsKey(GroupSummaryData.SUMMARY)
                && !extras.containsKey(GroupSummaryData.FINGERPRINT)) return;
        extras.remove(GroupSummaryData.FINGERPRINT);
        extras.remove(GroupSummaryData.SUMMARY);
        notifyPosted.invoke(listeners, entry.record, entry.record);
    }

    private static final class Entry {
        final Object record;
        final StatusBarNotification sbn;
        final boolean eligible;
        final String title;
        final String text;
        final String fingerprint;

        Entry(Object record, StatusBarNotification sbn, boolean eligible) {
            this.record = record;
            this.sbn = sbn;
            this.eligible = eligible;
            title = GroupSummaryData.title(sbn.getNotification());
            text = GroupSummaryData.text(sbn.getNotification());
            fingerprint = GroupSummaryData.itemFingerprint(sbn);
        }
    }

    private static final class Batch {
        final String id;
        final Entry summary;
        final List<Entry> children;
        final List<Entry> requested;
        final String fingerprint;
        final int minimum;

        Batch(String id, Entry summary, List<Entry> children, int minimum) {
            this.id = id;
            this.summary = summary;
            this.children = new ArrayList<>(children);
            this.minimum = minimum;
            ArrayList<StatusBarNotification> sbns = new ArrayList<>();
            for (Entry child : children) sbns.add(child.sbn);
            fingerprint = GroupSummaryData.fingerprint(sbns);
            ArrayList<Entry> sorted = new ArrayList<>(children);
            sorted.sort(Comparator.comparingLong((Entry entry) -> entry.sbn.getNotification().when)
                    .thenComparing(entry -> entry.sbn.getKey()));
            requested = new ArrayList<>(sorted.subList(Math.max(0, sorted.size() - GroupSummaryData.MAX_ITEMS), sorted.size()));
        }
    }

    private static final class CachedGroup {
        final String fingerprint;
        final String summary;

        CachedGroup(String fingerprint, String summary) {
            this.fingerprint = fingerprint;
            this.summary = summary;
        }
    }
}
