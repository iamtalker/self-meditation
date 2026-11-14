package com.selfmeditation.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// In-app updater: checks the latest GitHub release, downloads its APK and opens the installer.
// Ported from the shared "apk 업데이트 모듈" (Kotlin/Compose) to Java/Views.
// Android always asks the user to confirm the final "install" tap.
final class Updater {

    static final String REPO = "iamtalker/self-meditation";
    static final String RESULT_ACTION = "com.selfmeditation.app.INSTALL_RESULT";
    private static final long CHECK_EVERY_MS = 6 * 3600_000L;
    private static final int NOTICE_ID = 2;
    private static final String CHANNEL = "update";

    static final class Info {
        final String version;
        final String url;
        final String notes;

        Info(String version, String url, String notes) {
            this.version = version;
            this.url = url;
            this.notes = notes;
        }
    }

    // State shown by the UI. step: 0 idle, 1 downloading, 2 preparing, 3 confirm screen,
    // 4 installing, 5 done (app replaced).
    static volatile Info available;
    static volatile String status = "";
    static volatile boolean busy = false;
    static volatile int step = 0;
    static volatile float progress = -1f; // 0..1, negative = unknown length
    static volatile String checkedMsg = "";

    /** Called on the main thread whenever the state above changes. */
    static volatile Runnable listener;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private Updater() {}

    static void changed() {
        Runnable r = listener;
        if (r != null) MAIN.post(r);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("updater", Context.MODE_PRIVATE);
    }

    static boolean autoCheck(Context ctx) {
        return prefs(ctx).getBoolean("autoUpdate", true);
    }

    static void setAutoCheck(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean("autoUpdate", on).apply();
    }

    static String current(Context ctx) {
        try {
            String v = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
            return v != null ? v : "0";
        } catch (Exception e) {
            return "0";
        }
    }

    /** GitHub /releases/latest JSON -> release info if it carries an .apk, else null. */
    static Info parseRelease(String json) {
        try {
            JSONObject o = new JSONObject(json);
            String tag = o.getString("tag_name");
            JSONArray assets = o.getJSONArray("assets");
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                // One APK per release is assumed: take the first .apk, whatever its name.
                if (a.getString("name").toLowerCase(Locale.ROOT).endsWith(".apk")) {
                    String v = tag.startsWith("v") ? tag.substring(1) : tag;
                    return new Info(v, a.getString("browser_download_url"), o.optString("body"));
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Looks for a new version. Unless forced, skips when checked within the last 6 hours. */
    static void check(Context context, boolean force) {
        check(context, force, false);
    }

    /** With installIfNew, a found update is downloaded and handed to the installer right away. */
    static void check(Context context, boolean force, boolean installIfNew) {
        final Context ctx = context.getApplicationContext();
        IO.execute(() -> {
            SharedPreferences p = prefs(ctx);
            if (!force && (!autoCheck(ctx)
                    || System.currentTimeMillis() - p.getLong("updateChecked", 0L) < CHECK_EVERY_MS)) {
                return;
            }
            checkedMsg = "확인 중…";
            changed();
            Info info = null;
            try {
                HttpURLConnection c = (HttpURLConnection)
                        new URL("https://api.github.com/repos/" + REPO + "/releases/latest").openConnection();
                c.setConnectTimeout(10_000);
                c.setReadTimeout(15_000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                try (InputStream in = c.getInputStream()) {
                    info = parseRelease(readAll(in));
                }
            } catch (Exception ignored) {
            }
            if (info == null) {
                checkedMsg = "확인하지 못했습니다(인터넷 연결을 확인하세요)";
            } else {
                p.edit().putLong("updateChecked", System.currentTimeMillis()).apply();
                if (Versions.newer(info.version, current(ctx))) {
                    available = info;
                    checkedMsg = "새 버전 " + info.version + " 이 있습니다";
                    if (installIfNew) {
                        changed();
                        updateNow(ctx);
                        return;
                    }
                } else {
                    available = null;
                    checkedMsg = "최신 버전입니다";
                }
            }
            changed();
        });
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }

    /** Tapped: without install permission go to that setting, otherwise download and open the installer. */
    static void updateNow(Context context) {
        final Context ctx = context.getApplicationContext();
        final Info info = available;
        if (info == null || busy) return;
        if (!ctx.getPackageManager().canRequestPackageInstalls()) {
            status = "먼저 ‘이 출처 허용’을 켠 뒤 돌아와서 다시 누르세요";
            changed();
            try {
                ctx.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + ctx.getPackageName()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
            }
            return;
        }
        busy = true;
        step = 1;
        progress = 0f;
        status = "새 버전 " + info.version + " 받는 중 0%";
        changed();
        IO.execute(() -> {
            boolean installed = false;
            try {
                File file = download(ctx, info);
                progress = -1f;
                step = 2;
                status = "내려받기 완료 · 설치를 준비하는 중…";
                changed();
                notice(ctx, "설치를 준비하는 중…", -1);
                install(ctx, file);
                installed = true;
                notice(ctx, "설치 확인 화면에서 ‘설치/업데이트’를 누르세요 — 누르면 앱이 잠깐 꺼졌다가 다시 켜집니다", -1);
            } catch (Exception e) {
                step = 0;
                status = "업데이트하지 못했습니다: " + e.getMessage();
            } finally {
                busy = false;
                progress = -1f;
                if (!installed) cancelNotice(ctx);
                changed();
            }
        });
    }

    private static File download(Context ctx, Info info) throws Exception {
        File out = new File(ctx.getCacheDir(), "update.apk");
        File tmp = new File(ctx.getCacheDir(), "update.apk.tmp");
        HttpURLConnection c = (HttpURLConnection) new URL(info.url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        long total = c.getContentLengthLong();
        long got = 0;
        int lastPct = -1;
        try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                o.write(buf, 0, n);
                got += n;
                int pct = total > 0 ? (int) (got * 100 / total) : 0;
                if (pct != lastPct) {
                    lastPct = pct;
                    progress = total > 0 ? (float) got / total : -1f;
                    status = "새 버전 " + info.version + " 받는 중 " + pct + "%"
                            + (total > 0 ? String.format(Locale.US, " (%.1f / %.1fMB)",
                            got / 1048576.0, total / 1048576.0) : "");
                    notice(ctx, "새 버전 " + info.version + " 받는 중 " + pct + "%", pct);
                    changed();
                }
            }
        }
        if (total > 0 && got != total) {
            tmp.delete();
            throw new IllegalStateException("받은 크기가 다릅니다");
        }
        out.delete();
        tmp.renameTo(out);
        return out;
    }

    private static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.getSystemService(NotificationManager.class).createNotificationChannel(
                    new NotificationChannel(CHANNEL, "앱 업데이트", NotificationManager.IMPORTANCE_LOW));
        }
    }

    /** Progress notification while downloading/installing (silently skipped without permission). */
    private static void notice(Context ctx, String text, int pct) {
        try {
            ensureChannel(ctx);
            Notification.Builder b = new Notification.Builder(ctx, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle("앱 업데이트")
                    .setContentText(text)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true);
            if (pct >= 0 && pct <= 100) b.setProgress(100, pct, false);
            else b.setProgress(0, 0, true);
            ctx.getSystemService(NotificationManager.class).notify(NOTICE_ID, b.build());
        } catch (Exception ignored) {
        }
    }

    /** Right after the app was replaced by the new version. */
    static void notifyDone(Context ctx) {
        try {
            ensureChannel(ctx);
            PendingIntent open = PendingIntent.getActivity(ctx, 3,
                    new Intent(ctx, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
            ctx.getSystemService(NotificationManager.class).notify(NOTICE_ID,
                    new Notification.Builder(ctx, CHANNEL)
                            .setSmallIcon(android.R.drawable.stat_sys_download_done)
                            .setContentTitle("앱 업데이트 완료")
                            .setContentText("새 버전 " + current(ctx) + " 로 바뀌었습니다")
                            .setAutoCancel(true)
                            .setContentIntent(open)
                            .build());
        } catch (Exception ignored) {
        }
    }

    /** The version if the app is running for the first time after an update, else null (returned once). */
    static String justUpdated(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String now = current(ctx);
        String last = p.getString("lastRunVersion", null);
        p.edit().putString("lastRunVersion", now).apply();
        return last != null && !last.equals(now) ? now : null;
    }

    private static void cancelNotice(Context ctx) {
        try {
            ctx.getSystemService(NotificationManager.class).cancel(NOTICE_ID);
        } catch (Exception ignored) {
        }
    }

    private static void install(Context ctx, File apk) throws Exception {
        final PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        final int id = pi.createSession(params);

        // Once the install really starts, move to the "installing" step and keep a notification
        // while this app is being replaced.
        PackageInstaller.SessionCallback cb = new PackageInstaller.SessionCallback() {
            private void installing() {
                if (step == 2 || step == 3) {
                    step = 4;
                    status = "설치하는 중… 앱이 곧 꺼졌다가 새 버전으로 다시 켜집니다(약 10~30초). 꺼져도 정상입니다";
                    notice(ctx, "설치하는 중… 앱이 곧 다시 켜집니다", -1);
                    changed();
                }
            }

            @Override public void onCreated(int sessionId) {}
            @Override public void onBadgingChanged(int sessionId) {}

            @Override public void onActiveChanged(int sessionId, boolean active) {
                if (sessionId == id && active) installing();
            }

            @Override public void onProgressChanged(int sessionId, float p) {
                if (sessionId == id && p > 0.95f) installing();
            }

            @Override public void onFinished(int sessionId, boolean success) {
                if (sessionId != id) return;
                try {
                    pi.unregisterSessionCallback(this);
                } catch (Exception ignored) {
                }
                if (!success) {
                    step = 0;
                    status = "설치하지 못했거나 취소했습니다";
                    cancelNotice(ctx);
                    changed();
                }
            }
        };
        pi.registerSessionCallback(cb, MAIN);

        try (PackageInstaller.Session s = pi.openSession(id)) {
            // Preparing: write the APK into the install session, filling the bar as it goes.
            try (InputStream in = new FileInputStream(apk);
                 OutputStream o = s.openWrite("app.apk", 0, apk.length())) {
                long total = Math.max(1L, apk.length());
                byte[] buf = new byte[256 * 1024];
                long done = 0;
                int lastPct = -1;
                int n;
                while ((n = in.read(buf)) >= 0) {
                    o.write(buf, 0, n);
                    done += n;
                    int pct = (int) (done * 100 / total);
                    if (pct != lastPct) {
                        lastPct = pct;
                        progress = (float) done / total;
                        status = "설치를 준비하는 중… " + pct + "%";
                        notice(ctx, "설치를 준비하는 중… " + pct + "%", pct);
                        changed();
                    }
                }
                s.fsync(o);
            }
            progress = -1f; // the Android installer takes over and reports no progress
            changed();
            Intent intent = new Intent(ctx, UpdateReceiver.class)
                    .setAction(RESULT_ACTION).setPackage(ctx.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            s.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).getIntentSender());
        }
    }
}
