package com.localai.notif;

/**
 * Holds runtime LSPosed self-hook metadata for the module app process.
 * Populated via reflection by {@link ModuleMain} when {@code com.localai.notif} loads.
 */
final class ModuleStatus {
    private static volatile boolean sSelfHooked;
    private static volatile String sFrameworkName = "";
    private static volatile String sFrameworkVersion = "";
    private static volatile int sApiVersion;

    private ModuleStatus() {}

    static void markActivated(String frameworkName, String frameworkVersion, int apiVersion) {
        sFrameworkName = frameworkName != null ? frameworkName.trim() : "";
        sFrameworkVersion = frameworkVersion != null ? frameworkVersion.trim() : "";
        sApiVersion = apiVersion;
        sSelfHooked = true;
    }

    static boolean isSelfHooked() {
        return sSelfHooked;
    }

    static String getFrameworkName() {
        return sFrameworkName;
    }

    static String getFrameworkVersion() {
        return sFrameworkVersion;
    }

    static int getApiVersion() {
        return sApiVersion;
    }

    static String formatFrameworkSummary() {
        if (!sSelfHooked) {
            return "未检测到框架注入";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(sFrameworkName.isEmpty() ? "LSPosed" : sFrameworkName);
        if (!sFrameworkVersion.isEmpty()) {
            sb.append(' ').append(sFrameworkVersion);
        }
        if (sApiVersion > 0) {
            sb.append(" (API ").append(sApiVersion).append(')');
        }
        return sb.toString();
    }
}
