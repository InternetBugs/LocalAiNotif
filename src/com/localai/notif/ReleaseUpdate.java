package com.localai.notif;

import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

final class ReleaseUpdate {
    private static final String REPOSITORY = "InternetBugs/LocalAiNotif";
    static final String REPO_URL = "https://github.com/" + REPOSITORY;
    private static final String LATEST_URL = "https://api.github.com/repos/" + REPOSITORY + "/releases/latest";
    private static final Pattern VERSION = Pattern.compile("v?(\\d+)\\.(\\d+)\\.(\\d+)");

    final String tag;
    final String apkName;
    final String downloadUrl;
    final long size;

    private ReleaseUpdate(String tag, String apkName, String downloadUrl, long size) {
        this.tag = tag;
        this.apkName = apkName;
        this.downloadUrl = downloadUrl;
        this.size = size;
    }

    static ReleaseUpdate check(String installedVersion) throws IOException, JSONException {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(LATEST_URL).openConnection();
        try {
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(15_000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "LocalAiNotif/" + installedVersion);
            try (InputStream input = connection.getInputStream()) {
                String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                return fromRelease(new JSONObject(json), installedVersion);
            }
        } finally {
            connection.disconnect();
        }
    }

    static ReleaseUpdate fromRelease(JSONObject release, String installedVersion) throws JSONException {
        String tag = release.getString("tag_name");
        if (release.getBoolean("draft") || release.getBoolean("prerelease")) {
            throw new JSONException("Expected a published stable release");
        }
        Matcher latest = VERSION.matcher(tag);
        Matcher installed = VERSION.matcher(installedVersion);
        if (!latest.matches() || !installed.matches()) {
            throw new JSONException("Unrecognized release version");
        }
        int difference = 0;
        for (int part = 1; part <= 3 && difference == 0; part++) {
            difference = Integer.compare(Integer.parseInt(latest.group(part)), Integer.parseInt(installed.group(part)));
        }
        if (difference <= 0) return null;

        String version = tag.startsWith("v") ? tag.substring(1) : tag;
        String apkName = "LocalAiNotif-v" + version + ".apk";
        String expectedUrl = REPO_URL + "/releases/download/" + tag + "/" + apkName;
        JSONArray assets = release.getJSONArray("assets");
        for (int index = 0; index < assets.length(); index++) {
            JSONObject asset = assets.getJSONObject(index);
            if (apkName.equals(asset.optString("name")) && "uploaded".equals(asset.optString("state"))
                    && expectedUrl.equals(asset.optString("browser_download_url")) && asset.optLong("size") > 0) {
                return new ReleaseUpdate(tag, apkName, expectedUrl, asset.getLong("size"));
            }
        }
        return new ReleaseUpdate(tag, apkName, null, 0);
    }

    enum DownloadState { QUEUED, IN_PROGRESS, ALREADY_DOWNLOADED }

    static synchronized DownloadState download(Context context, ReleaseUpdate update) throws IOException {
        DownloadManager manager = context.getSystemService(DownloadManager.class);
        if (manager == null) throw new IOException("Download manager unavailable");
        DownloadManager.Query query = new DownloadManager.Query().setFilterByStatus(
                DownloadManager.STATUS_PENDING | DownloadManager.STATUS_PAUSED
                        | DownloadManager.STATUS_RUNNING | DownloadManager.STATUS_SUCCESSFUL);
        try (Cursor downloads = manager.query(query)) {
            while (downloads != null && downloads.moveToNext()) {
                if (!update.downloadUrl.equals(downloads.getString(downloads.getColumnIndexOrThrow(
                        DownloadManager.COLUMN_URI)))) continue;
                int status = downloads.getInt(downloads.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                if (status != DownloadManager.STATUS_SUCCESSFUL) return DownloadState.IN_PROGRESS;
                long id = downloads.getLong(downloads.getColumnIndexOrThrow(DownloadManager.COLUMN_ID));
                try (ParcelFileDescriptor file = manager.openDownloadedFile(id)) {
                    if (file != null) return DownloadState.ALREADY_DOWNLOADED;
                } catch (FileNotFoundException ignored) {
                }
            }
        }
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(update.downloadUrl))
                .setTitle(update.apkName)
                .setMimeType("application/vnd.android.package-archive")
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, update.apkName)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        if (manager.enqueue(request) < 0) throw new IOException("Could not enqueue APK");
        return DownloadState.QUEUED;
    }
}
