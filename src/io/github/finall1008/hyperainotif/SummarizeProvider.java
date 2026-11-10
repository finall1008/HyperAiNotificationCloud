package io.github.finall1008.hyperainotif;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public class SummarizeProvider extends ContentProvider {
    private static final String TAG = "HyperAiNotifCloud";

    @Override
    public boolean onCreate() {
        return true;
    }

    private SharedPreferences getPrefs() {
        Context ctx = getContext();
        if (ctx == null) return null;
        return ctx.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE);
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle reply = new Bundle();
        SharedPreferences prefs = getPrefs();
        if (prefs == null) {
            reply.putString("error", "prefs_null");
            return reply;
        }

        if ("get_config".equals(method)) {
            reply.putBoolean(Config.KEY_SUMMARIZE_SCREEN_ON, prefs.getBoolean(
                    Config.KEY_SUMMARIZE_SCREEN_ON, Config.DEFAULT_SUMMARIZE_SCREEN_ON));
            reply.putInt(Config.KEY_NOTIFICATION_THRESHOLD, prefs.getInt(
                    Config.KEY_NOTIFICATION_THRESHOLD, Config.DEFAULT_NOTIFICATION_THRESHOLD));
            reply.putInt(Config.KEY_WAIT_SECONDS, prefs.getInt(
                    Config.KEY_WAIT_SECONDS, Config.DEFAULT_WAIT_SECONDS));
            reply.putBoolean(Config.KEY_ALL_APPS, prefs.getBoolean(Config.KEY_ALL_APPS, true));
            reply.putInt(Config.KEY_MIN_LENGTH, prefs.getInt(Config.KEY_MIN_LENGTH, Config.DEFAULT_MIN_LENGTH));
            return reply;
        }

        if ("summarize".equals(method) && extras != null) {
            ArrayList<String> messages = extras.getStringArrayList("messageList");
            if (messages == null) {
                String single = extras.getString("messageList");
                if (single != null) {
                    messages = new ArrayList<>();
                    messages.add(single);
                }
            }
            ArrayList<String> categories = extras.getStringArrayList("category");
            if (messages == null || messages.isEmpty()) {
                reply.putString("status", "7");
                return reply;
            }

            ArrayList<String> outputs = new ArrayList<>(messages.size());
            ArrayList<String> errorCodes = new ArrayList<>(messages.size());

            for (int i = 0; i < messages.size(); i++) {
                String msg = messages.get(i);
                String cat = (categories != null && i < categories.size()) ? categories.get(i) : "";
                try {
                    String summary = callCloudLlm(prefs, msg, cat);
                    if (summary != null && !summary.trim().isEmpty()) {
                        outputs.add("✨ " + summary.trim());
                        errorCodes.add("0");
                    } else {
                        outputs.add("");
                        errorCodes.add("1");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Cloud LLM call failed for message " + i, e);
                    outputs.add("");
                    errorCodes.add("1");
                }
            }

            reply.putString("status", "0");
            reply.putStringArrayList("outputtext_list", outputs);
            reply.putStringArrayList("errorcode_list", errorCodes);
            return reply;
        }

        return reply;
    }

    public static String callCloudLlm(SharedPreferences prefs, String messageText, String category) throws Exception {
        String apiUrl = prefs.getString(Config.KEY_API_URL, Config.DEFAULT_API_URL).trim();
        String apiKey = prefs.getString(Config.KEY_API_KEY, Config.DEFAULT_API_KEY).trim();
        String model = prefs.getString(Config.KEY_MODEL, Config.DEFAULT_MODEL).trim();
        String sysPrompt = prefs.getString(Config.KEY_SYSTEM_PROMPT, Config.DEFAULT_SYSTEM_PROMPT).trim();

        if (apiUrl.isEmpty() || apiKey.isEmpty()) {
            throw new IllegalStateException("API URL or API Key is empty");
        }
        if (!apiUrl.endsWith("/chat/completions") && !apiUrl.contains("/chat/completions")) {
            if (apiUrl.endsWith("/")) {
                apiUrl = apiUrl + "v1/chat/completions";
            } else if (apiUrl.endsWith("/v1")) {
                apiUrl = apiUrl + "/chat/completions";
            }
        }

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("temperature", 0.3);
        body.put("max_tokens", 512);

        JSONArray msgs = new JSONArray();
        JSONObject sysObj = new JSONObject();
        sysObj.put("role", "system");
        sysObj.put("content", sysPrompt);
        msgs.put(sysObj);

        JSONObject userObj = new JSONObject();
        userObj.put("role", "user");
        userObj.put("content", "通知内容：" + messageText);
        msgs.put(userObj);

        body.put("messages", msgs);

        URL url = new URL(apiUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(20000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("x-opencode-session", "ses_hyper_ai_notif_01");

        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload);
        }

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (is != null) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
            }
        }

        if (code < 200 || code >= 300) {
            throw new RuntimeException("HTTP " + code + ": " + sb);
        }

        JSONObject resp = new JSONObject(sb.toString());
        JSONArray choices = resp.optJSONArray("choices");
        if (choices != null && choices.length() > 0) {
            JSONObject first = choices.getJSONObject(0);
            JSONObject msgObj = first.optJSONObject("message");
            if (msgObj != null) {
                String content = msgObj.optString("content", "").trim();
                // Strip thinking tags if reasoning model is used
                content = content.replaceAll("(?s)<think>.*?</think>", "").trim();
                return content;
            }
        }
        return null;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
