package io.github.finall1008.hyperainotif;

public final class Config {
    public static final String AUTHORITY = "io.github.finall1008.hyperainotif.provider";
    public static final String PREFS_NAME = "hyper_ai_notif_prefs";

    public static final String KEY_API_URL = "api_url";
    public static final String KEY_API_KEY = "api_key";
    public static final String KEY_MODEL = "model";
    public static final String KEY_SYSTEM_PROMPT = "system_prompt";
    public static final String KEY_SUMMARIZE_SCREEN_ON = "summarize_screen_on";
    public static final String KEY_NOTIFICATION_THRESHOLD = "notification_threshold";
    public static final String KEY_WAIT_SECONDS = "wait_seconds";
    public static final boolean DEFAULT_SUMMARIZE_SCREEN_ON = true;
    public static final int DEFAULT_NOTIFICATION_THRESHOLD = 1;
    public static final int DEFAULT_WAIT_SECONDS = 0;
    public static final String ACTION_CONFIG_CHANGED = "io.github.finall1008.hyperainotif.CONFIG_CHANGED";
    public static final String KEY_ALL_APPS = "all_apps";
    public static final String KEY_MIN_LENGTH = "min_length";

    public static final String DEFAULT_API_URL = "https://opencode.ai/zen/go/v1/chat/completions";
    public static final String DEFAULT_API_KEY = "";
    public static final String DEFAULT_MODEL = "deepseek-v4.1-flash";
    public static final String DEFAULT_SYSTEM_PROMPT =
            "你是一个手机通知智能摘要助手。请将用户收到的通知内容总结为简短、精准的一句话摘要（控制在15到25个字以内），直接输出摘要文本，不要包含引号、前缀或多余解释。";
    public static final int DEFAULT_MIN_LENGTH = 15;
}
