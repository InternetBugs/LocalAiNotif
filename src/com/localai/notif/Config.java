package com.localai.notif;

public final class Config {
    public static final String AUTHORITY = "com.localai.notif.provider";
    public static final String PREFS_NAME = "localai_notif_prefs";

    public static final String KEY_SUMMARIZE_SCREEN_ON = "summarize_screen_on";
    public static final String KEY_NOTIFICATION_THRESHOLD = "notification_threshold";
    public static final String KEY_WAIT_SECONDS = "wait_seconds";
    public static final boolean DEFAULT_SUMMARIZE_SCREEN_ON = true;
    public static final int DEFAULT_NOTIFICATION_THRESHOLD = 1;
    public static final int DEFAULT_WAIT_SECONDS = 0;
    public static final String KEY_MODEL_PATH = "model_path";
    public static final String DEFAULT_MODEL_PATH =
            "/data/user/0/com.localai.notif/files/models/qwen3.5-0.8b-mnn";
    public static final String KEY_CLASSIFY_ENABLED = "classify_enabled";
    public static final boolean DEFAULT_CLASSIFY_ENABLED = true;
    public static final String KEY_PRIORITY_ENABLED = "priority_enabled";
    public static final boolean DEFAULT_PRIORITY_ENABLED = true;
    public static final String KEY_PROMOTION_FILTER = "promotion_filter";
    public static final boolean DEFAULT_PROMOTION_FILTER = false;
    static final String ACTION_PROMOTION_SETTINGS = "com.localai.notif.PROMOTION_SETTINGS";
    public static final String KEY_REDUCE_ENABLED = "reduce_interruptions_enabled";
    public static final boolean DEFAULT_REDUCE_ENABLED = false;
    public static final String KEY_REDUCE_LOCK_SCREEN_ONLY = "reduce_lock_screen_only";
    public static final boolean DEFAULT_REDUCE_LOCK_SCREEN_ONLY = false;
    public static final String KEY_REDUCE_FILTER_ICONS = "reduce_filter_icons";
    public static final boolean DEFAULT_REDUCE_FILTER_ICONS = false;
    public static final String KEY_REDUCE_NORMAL_SOUNDS = "reduce_normal_sounds";
    static final String METHOD_SET_PRIORITY_ENABLED = "set_priority_enabled";
    static final String METHOD_SET_CONFIG = "set_config";
    static final String METHOD_SYNC_REDUCE_STATE = "sync_reduce_state";
    static final String METHOD_SET_MODEL_PATH = "set_model_path";
    static final String METHOD_RESET_ENGINE = "reset_engine";
    public static final String ACTION_CONFIG_CHANGED = "com.localai.notif.CONFIG_CHANGED";

    public static final String KEY_ALL_APPS = "all_apps";
    public static final boolean DEFAULT_ALL_APPS = true;
    public static final String KEY_MIN_LENGTH = "min_length";
    public static final String KEY_GROUP_SUMMARY = "group_summary";
    public static final boolean DEFAULT_GROUP_SUMMARY = true;
    static final String KEY_INCLUDE_ITEM_SUMMARIES = "include_item_summaries";
    static final String KEY_NOTIFICATION_RECAP = "notification_recap";
    static final boolean DEFAULT_NOTIFICATION_RECAP = false;
    public static final String KEY_RECAP_SCREEN_ON = "recap_screen_on";
    public static final boolean DEFAULT_RECAP_SCREEN_ON = true;
    public static final String KEY_RECAP_MIN_LENGTH = "recap_min_length";
    public static final int DEFAULT_RECAP_MIN_LENGTH = 80;
    static final int[] RECAP_MIN_LENGTH_OPTIONS = {50, 80, 120, 200};

    public static final String KEY_DOWNLOAD_SOURCE = "download_source";
    public static final String DOWNLOAD_SOURCE_MODELSCOPE = "modelscope";
    public static final String DOWNLOAD_SOURCE_HF_MIRROR = "hf_mirror";
    public static final String DOWNLOAD_SOURCE_HUGGINGFACE = "huggingface";
    public static final String DEFAULT_DOWNLOAD_SOURCE = DOWNLOAD_SOURCE_MODELSCOPE;
    public static final String KEY_WIFI_ONLY_DOWNLOAD = "wifi_only_download";
    public static final boolean DEFAULT_WIFI_ONLY_DOWNLOAD = true;
    public static final String KEY_ALWAYS_LOAD_MODEL = "always_load_model";
    public static final boolean DEFAULT_ALWAYS_LOAD_MODEL = false;
    public static final String KEY_SHOW_IN_SYSTEM_SETTINGS = "show_in_system_settings";
    public static final boolean DEFAULT_SHOW_IN_SYSTEM_SETTINGS = false;
    public static final String KEY_GLOW_STYLE = "glow_style";
    public static final String GLOW_STYLE_XIAOAI = "xiaoai";
    public static final String GLOW_STYLE_NEBULA = "nebula";
    public static final String DEFAULT_GLOW_STYLE = GLOW_STYLE_XIAOAI;
    public static final String ACTION_GLOW_STYLE_CHANGED = "com.localai.notif.GLOW_STYLE_CHANGED";
    public static final String EXTRA_FROM_SYSTEM_SETTINGS = "localai_from_system_settings";
    public static final String LAUNCHER_ALIAS_CLASS = "com.localai.notif.MainActivityLauncher";
    static final int MODULE_VERSION_CODE = 25;
    static final String MODULE_VERSION_NAME = "2.1.1";
    static final String KEY_MODEL_READY = "model_ready";
    static final String KEY_ENGINE_LOADED = "engine_loaded";
    static final String KEY_ENGINE_LOADING = "engine_loading";
    static final String KEY_SYSTEM_HOOK_ACTIVE = "system_hook_active";
    static final String KEY_SYSTEM_HOOK_VERSION_CODE = "system_hook_version_code";
    static final String KEY_SYSTEM_HOOK_FRAMEWORK = "system_hook_framework";
    static final String KEY_RESET_PRELOAD = "preload";
    static final String METHOD_REPORT_HOOK_ACTIVE = "report_hook_active";
    public static final String ACTION_PING_SYSTEM_HOOK = "com.localai.notif.PING_SYSTEM_HOOK";
    static final String ACTION_HOOK_STATE_CHANGED = "com.localai.notif.HOOK_STATE_CHANGED";
    static final String ACTION_ENGINE_STATE_CHANGED = "com.localai.notif.ENGINE_STATE_CHANGED";

    public static final int DEFAULT_MIN_LENGTH = 15;

    static String sanitizeGlowStyle(String raw) {
        return GLOW_STYLE_NEBULA.equals(raw) ? GLOW_STYLE_NEBULA : GLOW_STYLE_XIAOAI;
    }

    static boolean isXiaoAiGlow(String raw) {
        return !GLOW_STYLE_NEBULA.equals(raw);
    }

    static int sanitizeRecapMinLength(int value) {
        for (int option : RECAP_MIN_LENGTH_OPTIONS) {
            if (option == value) return option;
        }
        return DEFAULT_RECAP_MIN_LENGTH;
    }

    private Config() {}
}
