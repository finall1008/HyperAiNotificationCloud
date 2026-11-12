package io.github.finall1008.hyperainotif;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

@SuppressWarnings({"deprecation", "unchecked"})
public final class GroupSummaryTests extends Instrumentation {
    private static final String TAG = "hyperai.integration";
    private static final String GROUP = "hyperai.integration.group";
    private static final String CHANNEL = "hyperai_integration_tests";
    private Context context;
    private NotificationManager notifications;
    private SharedPreferences preferences;
    private Fixture fixture;
    private int assertions;
    private boolean liveOnly;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        liveOnly = arguments != null && "true".equals(arguments.getString("live"));
        start();
    }

    @Override
    public void onStart() {
        context = getTargetContext();
        notifications = context.getSystemService(NotificationManager.class);
        preferences = context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE);
        if (liveOnly) {
            Bundle result = new Bundle();
            try {
                Bundle request = new Bundle();
                request.putStringArrayList("messageList", new ArrayList<>(List.of(
                        "后端接口已联调完成，压测核心链路P99为45毫秒，计划今晚八点发版。",
                        "数据库迁移脚本已经审核通过，请在今晚八点发版之前备份数据库，校验码8829。")));
                request.putStringArrayList("titles", new ArrayList<>(List.of("发版准备", "数据库备份")));
                request.putInt("line_count", 2);
                request.putString("package", "hyperai.integration");
                Bundle reply = context.getContentResolver().call(android.net.Uri.parse("content://" + Config.AUTHORITY),
                        "summarize_group", null, request);
                check(reply != null && "0".equals(reply.getString("status")), "live cloud response");
                String group = reply.getString("group_summary", "");
                check(!group.isBlank(), "live group JSON protocol");
                ArrayList<String> items = reply.getStringArrayList("outputtext_list");
                check(items != null && items.size() == 2 && !items.get(0).isBlank() && !items.get(1).isBlank(),
                        "live individual JSON protocol");
                result.putString("result", "PASS: live model returned merged group summary and individual summaries");
                result.putString("group", group);
                finish(Activity.RESULT_OK, result);
            } catch (Throwable e) {
                result.putString("result", "FAIL: " + e);
                finish(Activity.RESULT_CANCELED, result);
            }
            return;
        }
        Map<String, ?> saved = preferences.getAll();
        Bundle result = new Bundle();
        int code = Activity.RESULT_OK;
        try (Fixture server = new Fixture()) {
            fixture = server;
            dataTests();
            preferences.edit().putString(Config.KEY_API_URL, server.url())
                    .putString(Config.KEY_API_KEY, "local-fixture")
                    .putString(Config.KEY_MODEL, "fixture")
                    .putBoolean(Config.KEY_ALL_APPS, true)
                    .putBoolean(Config.KEY_GROUP_SUMMARY, true)
                    .putBoolean(Config.KEY_SUMMARIZE_SCREEN_ON, true)
                    .putInt(Config.KEY_MIN_LENGTH, 15)
                    .putInt(Config.KEY_WAIT_SECONDS, 1)
                    .putInt(Config.KEY_NOTIFICATION_THRESHOLD, 1).commit();
            changed();
            notifications.createNotificationChannel(new NotificationChannel(CHANNEL,
                    "HyperAI integration tests", NotificationManager.IMPORTANCE_DEFAULT));
            integrationTests();
            result.putString("result", "PASS: " + assertions + " assertions; "
                    + fixture.groups.size() + " grouped requests; " + fixture.singles.size() + " single requests");
        } catch (Throwable e) {
            code = Activity.RESULT_CANCELED;
            result.putString("result", "FAIL: " + e);
            result.putString("stack", android.util.Log.getStackTraceString(e));
        } finally {
            for (int i = 9300; i <= 9305; i++) notifications.cancel(TAG, i);
            notifications.deleteNotificationChannel(CHANNEL);
            SharedPreferences.Editor editor = preferences.edit().clear();
            for (Map.Entry<String, ?> entry : saved.entrySet()) {
                Object value = entry.getValue();
                String key = entry.getKey();
                if (value instanceof String) editor.putString(key, (String) value);
                else if (value instanceof Integer) editor.putInt(key, (Integer) value);
                else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
                else if (value instanceof Long) editor.putLong(key, (Long) value);
                else if (value instanceof Float) editor.putFloat(key, (Float) value);
                else if (value instanceof Set) editor.putStringSet(key, (Set<String>) value);
            }
            editor.commit();
            changed();
        }
        finish(code, result);
    }

    private void dataTests() throws Exception {
        String json = "<think>reasoning</think>\n```json\n{\"group\":\"今晚八点准备发版，需提前备份数据库\","
                + "\"items\":{\"1\":\"单条摘要\"}}\n```";
        Bundle parsed = GroupSummaryData.parse(json, 2);
        check("今晚八点准备发版，需提前备份数据库".equals(parsed.getString("group_summary")), "fenced JSON parsing");
        check(parsed.getStringArrayList("outputtext_list").equals(List.of("单条摘要", "")), "optional item summaries");
        check(GroupSummaryData.parse("{\"group\":{\"bad\":1}}", 2)
                .getString("group_summary", "").isEmpty(), "reject non-text group summary");
        check(GroupSummaryData.parse("{\"items\":{\"1\":\"保留\"}}", 2)
                .getString("group_summary", "").isEmpty(), "reject missing group summary");
        Notification a = builder("", "短文本").setStyle(new Notification.BigTextStyle()
                .bigText("一字不差保存的完整长通知，包含数字8829与原始标点。")).build();
        GroupSummaryData.rememberOriginal(a);
        String original = GroupSummaryData.text(a);
        StatusBarNotification first = sbn(1, a);
        StatusBarNotification second = sbn(2, builder("", "第二条通知").build());
        String fingerprint = GroupSummaryData.fingerprint(List.of(first, second));
        a.extras.putCharSequence(Notification.EXTRA_TEXT, "模型生成的摘要");
        a.flags |= GroupSummaryData.AI_FLAG;
        GroupSummaryData.rememberOriginal(a);
        check(GroupSummaryData.text(a).equals(original), "preserve original after summary writeback");
        check(fingerprint.equals(GroupSummaryData.fingerprint(List.of(second, first))), "fingerprint ignores UI order and summary replacement");
        second.getNotification().extras.putCharSequence(Notification.EXTRA_TEXT, "通知更新");
        check(!fingerprint.equals(GroupSummaryData.fingerprint(List.of(first, second))), "fingerprint detects content update");
        check(!fingerprint.equals(GroupSummaryData.fingerprint(List.of(first))), "fingerprint detects removal");
    }

    private StatusBarNotification sbn(int id, Notification notification) {
        return new StatusBarNotification(context.getPackageName(), context.getPackageName(), id,
                "data-test", android.os.Process.myUid(), 0, 0, notification,
                android.os.Process.myUserHandle(), System.currentTimeMillis());
    }

    private void integrationTests() throws Exception {
        notifications.notify(TAG, 9300, builder("测试组", "组父通知").setGroup(GROUP).setGroupSummary(true).build());
        post(9301, "后端联调完成");
        post(9302, "今晚八点发版");
        Thread.sleep(1700);
        check(fixture.groups.isEmpty(), "combined minimum length still applies");
        post(9301, "后端接口联调完成");
        post(9302, "今晚八点准备发版");
        await(() -> groupSummary().contains("综合摘要2条"), "two short notifications form a group summary");
        check(fixture.groups.size() == 1, "two notifications share one HTTP request");
        check(fixture.singles.isEmpty(), "short notifications skip individual API requests");
        check((active(9301).getNotification().flags & GroupSummaryData.AI_FLAG) == 0,
                "short children keep their original text");
        assertPreview();

        int count = fixture.groups.size();
        fixture.holdNext();
        post(9303, "发版前备份库");
        fixture.awaitHeld();
        notifications.cancel(TAG, 9303);
        Thread.sleep(450);
        fixture.release();
        Thread.sleep(2000);
        check(groupSummary().isEmpty(), "removed child invalidates old and in-flight group text");
        check(fixture.groups.size() == count + 1, "clearing a child does not request another summary");

        post(9303, "压测报告通过");
        await(() -> groupSummary().contains("综合摘要3条"), "three notifications update merged group summary");
        String longText = "完整原文：数据库迁移脚本已经审核通过，请在今晚八点发版之前备份数据库，校验码8829。";
        post(9301, longText);
        await(() -> (active(9301).getNotification().flags & GroupSummaryData.AI_FLAG) != 0
                && groupSummary().contains("综合摘要3条"), "long child receives its individual summary");
        check(longText.contentEquals(active(9301).getNotification().extras
                .getCharSequence(Notification.EXTRA_BIG_TEXT)), "expanded child keeps exact original text");

        fixture.holdNext();
        post(9304, "上线后巡检");
        fixture.awaitHeld();
        String updated = "更新后的完整原文：发版时间调整为今晚九点，提前备份数据库，校验码9911。";
        post(9301, updated);
        Thread.sleep(400);
        fixture.release();
        await(() -> updated.equals(String.valueOf(active(9301).getNotification().extras
                .getCharSequence(Notification.EXTRA_BIG_TEXT))) && groupSummary().contains("综合摘要4条"),
                "discard stale response and summarize latest content");
        for (JSONObject input : fixture.groups) {
            check(!input.toString().contains("单条摘要"), "group inputs always use original content");
        }
        check(fixture.groups.get(fixture.groups.size() - 1).toString().contains("9911"), "latest request includes updated notification");

        preferences.edit().putBoolean(Config.KEY_GROUP_SUMMARY, false)
                .putBoolean(Config.KEY_SUMMARIZE_SCREEN_ON, false).commit();
        changed();
        await(() -> groupSummary().isEmpty(), "turning the feature off restores native previews");
        await(() -> containsText(uiRoot(), "上线后巡检"),
                "feature switch restores native child preview text");
        check(active(9301).getNotification().extras.getCharSequence(Notification.EXTRA_TEXT)
                .toString().contains("单条摘要"), "feature switch preserves individual summaries");

        for (int i = 9300; i <= 9304; i++) notifications.cancel(TAG, i);
        preferences.edit().putBoolean(Config.KEY_SUMMARIZE_SCREEN_ON, true).commit();
        changed();
        String single = "单条通知原文：您的包裹已到达营业点，请在今晚九点之前领取，取件码8829。";
        notifications.notify(TAG, 9305, builder("独立通知", single).build());
        await(() -> active(9305) != null && (active(9305).getNotification().flags & GroupSummaryData.AI_FLAG) != 0,
                "standalone notifications retain existing summary behavior");
        check(single.contentEquals(active(9305).getNotification().extras.getCharSequence(Notification.EXTRA_BIG_TEXT)),
                "standalone expanded text remains exact");
        notifications.cancel(TAG, 9305);

        preferences.edit().putBoolean(Config.KEY_GROUP_SUMMARY, true)
                .putInt(Config.KEY_NOTIFICATION_THRESHOLD, 3).commit();
        changed();
        int beforeAuto = fixture.groups.size();
        notifications.notify(TAG, 9301, builder("自动分组1", "自动分组测试第一条").build());
        notifications.notify(TAG, 9302, builder("自动分组2", "自动分组测试第二条").build());
        Thread.sleep(1800);
        check(fixture.groups.size() == beforeAuto, "grouping respects pending notification threshold");
        long posted = System.currentTimeMillis();
        notifications.notify(TAG, 9303, builder("自动分组3", "自动分组测试第三条").build());
        await(() -> {
            for (StatusBarNotification notification : notifications.getActiveNotifications()) {
                String value = notification.getNotification().extras.getString(GroupSummaryData.SUMMARY);
                if (value != null && value.contains("综合摘要3条")) return true;
            }
            return false;
        }, "HyperOS automatic group receives a combined summary");
        check(fixture.groups.size() == beforeAuto + 1, "automatic group uses one combined request");
        check(System.currentTimeMillis() - posted >= 900, "grouping respects configured wait time");
    }

    private void assertPreview() throws Exception {
        android.accessibilityservice.AccessibilityServiceInfo info = getUiAutomation().getServiceInfo();
        info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        getUiAutomation().setServiceInfo(info);
        shell("wm dismiss-keyguard");
        Thread.sleep(500);
        shell("cmd statusbar expand-notifications");
        try {
            await(() -> countContaining(uiRoot(), "综合摘要2条") == 1
                    && !containsText(uiRoot(), "后端接口联调完成"),
                    "SystemUI displays one merged TextView and hides separate single-line previews");
            AccessibilityNodeInfo group = findId(uiRoot(),
                    "com.android.systemui:id/notification_children_container");
            while (group != null && !group.isClickable()) group = group.getParent();
            AccessibilityNodeInfo merged = findContaining(uiRoot(), "综合摘要2条");
            android.graphics.Rect cardBounds = new android.graphics.Rect();
            android.graphics.Rect textBounds = new android.graphics.Rect();
            if (group != null) group.getBoundsInScreen(cardBounds);
            if (merged != null) merged.getBoundsInScreen(textBounds);
            check(group != null && merged != null
                    && textBounds.top - cardBounds.top >= 20
                    && cardBounds.bottom - textBounds.bottom >= 20,
                    "merged summary view preserves top and bottom card padding: card="
                            + cardBounds + " text=" + textBounds);
            check(group != null && group.performAction(AccessibilityNodeInfo.ACTION_CLICK), "native group expand action");
            await(() -> containsText(uiRoot(), "后端接口联调完成")
                    && countContaining(uiRoot(), "综合摘要2条") == 0,
                    "expanded group fades out merged view and shows original short child text");
            group = findId(uiRoot(),
                    "com.android.systemui:id/collapse_button");
            while (group != null && !group.isClickable()) group = group.getParent();
            check(group != null && group.performAction(AccessibilityNodeInfo.ACTION_CLICK), "native group collapse action");
            await(() -> countContaining(uiRoot(), "综合摘要2条") == 1
                    && !containsText(uiRoot(), "后端接口联调完成"),
                    "collapsing restores the single merged summary view");
        } catch (AssertionError e) {
            StringBuilder tree = new StringBuilder();
            dump(uiRoot(), tree);
            throw new AssertionError(e.getMessage() + "\n" + tree);
        }
    }

    private void shell(String command) throws Exception {
        try (InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                getUiAutomation().executeShellCommand(command))) {
            input.readAllBytes();
        }
    }

    private AccessibilityNodeInfo uiRoot() {
        for (android.view.accessibility.AccessibilityWindowInfo window : getUiAutomation().getWindows()) {
            AccessibilityNodeInfo root = window.getRoot();
            if (findId(root, "com.android.systemui:id/notification_stack_scroller") != null) return root;
        }
        return getUiAutomation().getRootInActiveWindow();
    }

    private void dump(AccessibilityNodeInfo node, StringBuilder tree) {
        if (node == null) return;
        tree.append(node.getViewIdResourceName()).append(" text=").append(node.getText())
                .append(" desc=").append(node.getContentDescription()).append('\n');
        for (int i = 0; i < node.getChildCount(); i++) dump(node.getChild(i), tree);
    }

    private AccessibilityNodeInfo findContaining(AccessibilityNodeInfo node, String substring) {
        if (node == null) return null;
        CharSequence text = node.getText();
        if (node.isVisibleToUser() && text != null && text.toString().contains(substring)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo result = findContaining(node.getChild(i), substring);
            if (result != null) return result;
        }
        return null;
    }

    private int countContaining(AccessibilityNodeInfo node, String substring) {
        if (node == null) return 0;
        CharSequence text = node.getText();
        int count = node.isVisibleToUser() && text != null && text.toString().contains(substring) ? 1 : 0;
        for (int i = 0; i < node.getChildCount(); i++) {
            count += countContaining(node.getChild(i), substring);
        }
        return count;
    }

    private boolean containsText(AccessibilityNodeInfo node, String text) {
        if (node == null) return false;
        CharSequence nodeText = node.getText();
        if (node.isVisibleToUser() && nodeText != null
                && (text.contentEquals(nodeText) || nodeText.toString().contains(text))) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            if (containsText(node.getChild(i), text)) return true;
        }
        return false;
    }

    private AccessibilityNodeInfo findId(AccessibilityNodeInfo node, String id) {
        if (node == null) return null;
        if (id.equals(node.getViewIdResourceName())) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo result = findId(node.getChild(i), id);
            if (result != null) return result;
        }
        return null;
    }

    private Notification.Builder builder(String title, String text) {
        return new Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title).setContentText(text).setOnlyAlertOnce(true);
    }

    private void post(int id, String text) {
        notifications.notify(TAG, id, builder("测试子通知" + id, text).setGroup(GROUP).build());
    }

    private StatusBarNotification active(int id) {
        for (StatusBarNotification notification : notifications.getActiveNotifications()) {
            if (TAG.equals(notification.getTag()) && notification.getId() == id) return notification;
        }
        return null;
    }

    private String groupSummary() {
        StatusBarNotification summary = active(9300);
        String value = summary == null ? null
                : summary.getNotification().extras.getString(GroupSummaryData.SUMMARY);
        return value == null ? "" : value;
    }

    private void changed() {
        context.sendBroadcast(new Intent(Config.ACTION_CONFIG_CHANGED).setPackage("android"));
    }

    private void await(BooleanSupplier condition, String description) throws Exception {
        long end = System.currentTimeMillis() + 12000;
        do {
            if (condition.getAsBoolean()) {
                check(true, description);
                return;
            }
            Thread.sleep(100);
        } while (System.currentTimeMillis() < end);
        throw new AssertionError("Timed out: " + description);
    }

    private void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        assertions++;
    }

    private static final class Fixture implements AutoCloseable {
        final ServerSocket server;
        final List<JSONObject> groups = Collections.synchronizedList(new ArrayList<>());
        final List<String> singles = Collections.synchronizedList(new ArrayList<>());
        volatile CountDownLatch held;
        volatile CountDownLatch released;
        volatile boolean hold;

        Fixture() throws Exception {
            server = new ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"));
            Thread worker = new Thread(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket socket = server.accept();
                        new Thread(() -> respond(socket), "hyperai-test-response").start();
                    } catch (Exception e) {
                        if (!server.isClosed()) throw new RuntimeException(e);
                    }
                }
            }, "hyperai-test-http");
            worker.setDaemon(true);
            worker.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getLocalPort() + "/v1/chat/completions";
        }

        void holdNext() {
            held = new CountDownLatch(1);
            released = new CountDownLatch(1);
            hold = true;
        }

        void awaitHeld() throws Exception {
            if (!held.await(12, TimeUnit.SECONDS)) throw new AssertionError("Expected group HTTP request");
        }

        void release() {
            if (released != null) released.countDown();
        }

        private void respond(Socket socket) {
            try (socket) {
                socket.setSoTimeout(15000);
                InputStream input = socket.getInputStream();
                String line;
                int length = 0;
                while (!(line = readLine(input)).isEmpty()) {
                    if (line.toLowerCase().startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
                }
                JSONObject request = new JSONObject(new String(input.readNBytes(length), StandardCharsets.UTF_8));
                String user = request.getJSONArray("messages").getJSONObject(1).getString("content");
                String content;
                if (user.startsWith("{")) {
                    JSONObject data = new JSONObject(user);
                    groups.add(data);
                    if (hold) {
                        hold = false;
                        held.countDown();
                        released.await(15, TimeUnit.SECONDS);
                    }
                    JSONArray notifications = data.getJSONArray("notifications");
                    String group = "综合摘要" + notifications.length() + "条：今晚八点发版并提前完成备份";
                    JSONObject items = new JSONObject();
                    for (int i = 0; i < notifications.length(); i++) {
                        if (notifications.getJSONObject(i).getBoolean("needs_item_summary")) {
                            items.put(Integer.toString(i + 1), "单条摘要" + (i + 1));
                        }
                    }
                    content = new JSONObject().put("group", group).put("items", items).toString();
                } else {
                    singles.add(user);
                    content = "单条摘要取件码8829";
                }
                byte[] response = new JSONObject().put("choices", new JSONArray().put(new JSONObject()
                        .put("message", new JSONObject().put("content", content)))).toString().getBytes(StandardCharsets.UTF_8);
                OutputStream output = socket.getOutputStream();
                output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                        + response.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                output.write(response);
                output.flush();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private String readLine(InputStream input) throws Exception {
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            int value;
            while ((value = input.read()) >= 0 && value != '\n') {
                if (value != '\r') result.write(value);
            }
            if (value < 0) throw new java.io.EOFException();
            return result.toString(StandardCharsets.US_ASCII);
        }

        @Override
        public void close() throws Exception {
            release();
            server.close();
        }
    }
}
