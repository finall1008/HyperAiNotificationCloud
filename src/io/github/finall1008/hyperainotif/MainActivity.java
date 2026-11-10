package io.github.finall1008.hyperainotif;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private EditText etApiUrl;
    private EditText etApiKey;
    private EditText etModel;
    private EditText etPrompt;
    private EditText etMinLength;
    private CheckBox cbScreenOn;
    private EditText etNotificationThreshold;
    private EditText etWaitSeconds;
    private CheckBox cbAllApps;
    private TextView tvStatus;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int testNotifId = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("澎湃通知 AI 云端摘要 (LSPosed)");

        if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1001);
        }

        SharedPreferences prefs = getSharedPreferences(Config.PREFS_NAME, MODE_PRIVATE);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(Color.parseColor("#F5F6F8"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, pad);
        scrollView.addView(root);

        // Header Card
        LinearLayout headerCard = createCard();
        TextView title = new TextView(this);
        title.setText("HyperOS 通知智能摘要 · 云端模型接管");
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#1A1A1A"));
        headerCard.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("在 LSPosed 中勾选「系统框架 (android)」、「通知管理服务 (com.miui.notification)」与「设置 (com.android.settings)」后生效。");
        subtitle.setTextSize(13);
        subtitle.setTextColor(Color.parseColor("#666666"));
        subtitle.setPadding(0, dp(6), 0, 0);
        headerCard.addView(subtitle);
        root.addView(headerCard);

        // API Config Card
        LinearLayout apiCard = createCard();
        addSectionTitle(apiCard, "云端大模型 API 配置 (OpenAI 兼容)");

        addLabel(apiCard, "API 接口地址 (Base URL 或完整 /v1/chat/completions)");
        etApiUrl = createInput(prefs.getString(Config.KEY_API_URL, Config.DEFAULT_API_URL),
                "https://api.deepseek.com/v1/chat/completions", false);
        apiCard.addView(etApiUrl);

        addLabel(apiCard, "API Key (Bearer Token)");
        etApiKey = createInput(prefs.getString(Config.KEY_API_KEY, Config.DEFAULT_API_KEY), "填写你的 API Key", true);
        apiCard.addView(etApiKey);

        addLabel(apiCard, "模型名称 (Model)");
        etModel = createInput(prefs.getString(Config.KEY_MODEL, Config.DEFAULT_MODEL),
                "deepseek-chat / gpt-4o-mini / qwen-plus", false);
        apiCard.addView(etModel);

        addLabel(apiCard, "系统提示词 (System Prompt)");
        etPrompt = createInput(prefs.getString(Config.KEY_SYSTEM_PROMPT, Config.DEFAULT_SYSTEM_PROMPT),
                Config.DEFAULT_SYSTEM_PROMPT, false);
        etPrompt.setMinLines(3);
        apiCard.addView(etPrompt);

        root.addView(apiCard);

        // Strategy Card
        LinearLayout ruleCard = createCard();
        addSectionTitle(ruleCard, "触发策略增强 (解除小米原厂限制)");

        cbScreenOn = new CheckBox(this);
        cbScreenOn.setText("亮屏总结（关闭后仅在息屏时总结）");
        cbScreenOn.setTextSize(14);
        cbScreenOn.setTextColor(Color.parseColor("#222222"));
        cbScreenOn.setChecked(prefs.getBoolean(Config.KEY_SUMMARIZE_SCREEN_ON,
                Config.DEFAULT_SUMMARIZE_SCREEN_ON));
        ruleCard.addView(cbScreenOn);

        addLabel(ruleCard, "通知数量阈值（待总结通知达到此数量后开始等待，至少 1 条）");
        etNotificationThreshold = createInput(String.valueOf(prefs.getInt(
                Config.KEY_NOTIFICATION_THRESHOLD, Config.DEFAULT_NOTIFICATION_THRESHOLD)), "1", false);
        etNotificationThreshold.setInputType(InputType.TYPE_CLASS_NUMBER);
        ruleCard.addView(etNotificationThreshold);

        addLabel(ruleCard, "等待时间（秒，达到数量阈值后等待；0 表示立即总结）");
        etWaitSeconds = createInput(String.valueOf(prefs.getInt(
                Config.KEY_WAIT_SECONDS, Config.DEFAULT_WAIT_SECONDS)), "0", false);
        etWaitSeconds.setInputType(InputType.TYPE_CLASS_NUMBER);
        ruleCard.addView(etWaitSeconds);

        cbAllApps = new CheckBox(this);
        cbAllApps.setText("支持所有应用通知（解除原厂 76 个白名单应用限制）");
        cbAllApps.setTextSize(14);
        cbAllApps.setTextColor(Color.parseColor("#222222"));
        cbAllApps.setChecked(prefs.getBoolean(Config.KEY_ALL_APPS, true));
        ruleCard.addView(cbAllApps);

        addLabel(ruleCard, "触发总结的最小通知字数（原厂默认 > 28 字）");
        etMinLength = createInput(String.valueOf(prefs.getInt(Config.KEY_MIN_LENGTH, Config.DEFAULT_MIN_LENGTH)),
                "15", false);
        etMinLength.setInputType(InputType.TYPE_CLASS_NUMBER);
        ruleCard.addView(etMinLength);

        root.addView(ruleCard);

        // Action Buttons
        Button btnSave = createButton("保存配置", "#0D84FF");
        btnSave.setOnClickListener(v -> savePrefs(true));
        root.addView(btnSave);

        Button btnTestApi = createButton("1. 测试云端 API 连通性", "#2E7D32");
        btnTestApi.setOnClickListener(v -> testCloudApi());
        root.addView(btnTestApi);

        Button btnSendTestNotif = createButton("2. 发送一条长通知测试系统 AI 总结效果", "#6A1B9A");
        btnSendTestNotif.setOnClickListener(v -> sendTestNotification());
        root.addView(btnSendTestNotif);

        Button btnOpenMiuiSetting = createButton("3. 打开澎湃系统「通知列表总结」设置页", "#455A64");
        btnOpenMiuiSetting.setOnClickListener(v -> {
            try {
                Intent intent = new Intent();
                intent.setClassName("com.miui.notification",
                        "miui.notification.management.activity.AiNotificationSettingsActivity");
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(this, "打开失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        root.addView(btnOpenMiuiSetting);

        tvStatus = new TextView(this);
        tvStatus.setTextSize(13);
        tvStatus.setTextColor(Color.parseColor("#333333"));
        tvStatus.setPadding(dp(4), dp(10), dp(4), dp(20));
        root.addView(tvStatus);

        setContentView(scrollView);
    }

    private boolean savePrefs(boolean showToast) {
        Integer minLen = readInteger(etMinLength, 0);
        Integer threshold = readInteger(etNotificationThreshold, 1);
        Integer waitSeconds = readInteger(etWaitSeconds, 0);
        if (minLen == null || threshold == null || waitSeconds == null) return false;

        SharedPreferences prefs = getSharedPreferences(Config.PREFS_NAME, MODE_PRIVATE);
        prefs.edit()
                .putString(Config.KEY_API_URL, etApiUrl.getText().toString().trim())
                .putString(Config.KEY_API_KEY, etApiKey.getText().toString().trim())
                .putString(Config.KEY_MODEL, etModel.getText().toString().trim())
                .putString(Config.KEY_SYSTEM_PROMPT, etPrompt.getText().toString().trim())
                .putBoolean(Config.KEY_SUMMARIZE_SCREEN_ON, cbScreenOn.isChecked())
                .putInt(Config.KEY_NOTIFICATION_THRESHOLD, threshold)
                .putInt(Config.KEY_WAIT_SECONDS, waitSeconds)
                .putBoolean(Config.KEY_ALL_APPS, cbAllApps.isChecked())
                .putInt(Config.KEY_MIN_LENGTH, minLen)
                .apply();

        sendBroadcast(new Intent(Config.ACTION_CONFIG_CHANGED).setPackage("android"));
        if (showToast) {
            Toast.makeText(this, "配置已保存（即时生效，无需重启）", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private Integer readInteger(EditText input, int minimum) {
        try {
            int value = Integer.parseInt(input.getText().toString().trim());
            if (value >= minimum) return value;
        } catch (NumberFormatException ignored) {}
        input.setError("请输入不小于 " + minimum + " 的整数");
        input.requestFocus();
        return null;
    }

    private void testCloudApi() {
        if (!savePrefs(false)) return;
        tvStatus.setText("正在请求云端模型进行摘要测试...");
        SharedPreferences prefs = getSharedPreferences(Config.PREFS_NAME, MODE_PRIVATE);
        final String sample = "【顺丰速运】您的快件 SF142988371625 已到达【北京海淀中关村营业点】，派件员张师傅（电话13800138000）正在为您派送，请保持电话畅通，签收码 8829。";
        executor.execute(() -> {
            long start = System.currentTimeMillis();
            try {
                String res = SummarizeProvider.callCloudLlm(prefs, sample, "notification");
                long cost = System.currentTimeMillis() - start;
                mainHandler.post(() -> tvStatus.setText("✅ 云端 API 测试成功 (" + cost + "ms)\n原文：" + sample + "\n摘要：✨ " + res));
            } catch (Exception e) {
                mainHandler.post(() -> tvStatus.setText("❌ 云端 API 调用失败：\n" + e.getMessage()));
            }
        });
    }

    private void sendTestNotification() {
        if (!savePrefs(false)) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        String channelId = "ai_summary_test_channel";
        NotificationChannel channel = new NotificationChannel(
                channelId, "测试通知智能摘要", NotificationManager.IMPORTANCE_HIGH);
        nm.createNotificationChannel(channel);

        String longText = "【京东物流】您购买的「小米 18 Pro 16GB+1TB 钛金属版」包裹已由北京通州分拣中心发出，预计今天下午 18:00 前送达您家门口，配送员李师傅联系电话 13912345678，请留意查收。";

        Notification notif = new Notification.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("京东物流派送提醒 #" + (testNotifId - 1000))
                .setContentText(longText)
                .setAutoCancel(true)
                .build();

        nm.notify(testNotifId++, notif);
        Toast.makeText(this, "已发送长通知，将按数量阈值、等待时间和亮屏设置触发总结。", Toast.LENGTH_LONG).show();
    }

    private LinearLayout createCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(14));
        card.setBackground(bg);
        int p = dp(16);
        card.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        card.setLayoutParams(lp);
        return card;
    }

    private void addSectionTitle(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(15);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(Color.parseColor("#0D84FF"));
        tv.setPadding(0, 0, 0, dp(6));
        parent.addView(tv);
    }

    private void addLabel(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(Color.parseColor("#555555"));
        tv.setPadding(0, dp(8), 0, dp(4));
        parent.addView(tv);
    }

    private EditText createInput(String value, String hint, boolean isPassword) {
        EditText et = new EditText(this);
        et.setText(value);
        et.setHint(hint);
        et.setTextSize(14);
        et.setTextColor(Color.parseColor("#111111"));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#F2F3F5"));
        bg.setCornerRadius(dp(8));
        et.setBackground(bg);
        int p = dp(10);
        et.setPadding(p, p, p, p);
        if (isPassword) {
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        return et;
    }

    private Button createButton(String text, String colorHex) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(14);
        btn.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(colorHex));
        bg.setCornerRadius(dp(10));
        btn.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        lp.bottomMargin = dp(10);
        btn.setLayoutParams(lp);
        return btn;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
