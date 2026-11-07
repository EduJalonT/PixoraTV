package com.edujalont.tvhome;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Updates the launcher from the latest GitHub release of {@link #REPO}: reads the release, downloads
 * its APK into the app's cache, checks the SHA-256 GitHub publishes for the asset, the package name,
 * the version and the signing key, then hands it to Android's package installer, which asks the user
 * to confirm (and refuses an APK signed with another key anyway). Nothing is installed silently.
 */
final class Updater {
    static final String REPO = "EduJalonT/PixoraTV";
    private static final String LATEST = "https://api.github.com/repos/" + REPO + "/releases/latest";
    /** Automatic check, at most this often, when Home comes back to the front. */
    static final long AUTO_EVERY_MS = 12L * 60 * 60 * 1000;
    private static final long MAX_APK_BYTES = 64L * 1024 * 1024;
    private static final int MAX_JSON_BYTES = 1024 * 1024;
    private static final String PREFS = "updater";   // own file: "restore the Home defaults" keeps it

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Updater() {
    }

    static final class Release {
        final String version;   // tag without the leading "v"
        final String apkUrl;    // null when the release has no .apk asset
        final String sha256;    // lowercase hex from the asset's "digest", or null
        final long size;
        final String notes;

        Release(String version, String apkUrl, String sha256, long size, String notes) {
            this.version = version;
            this.apkUrl = apkUrl;
            this.sha256 = sha256;
            this.size = size;
            this.notes = notes;
        }
    }

    /** Called on the main thread: a value, or null and a localized reason. */
    interface Result<T> {
        void done(T value, String error);
    }

    interface Progress {
        void at(int percent);
    }

    static String installedVersion(Context c) {
        try {
            String v = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            return v != null ? v : "";
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    /** Compares dotted versions number by number ("2026.11.06.002" > "2026.11.06.001"). */
    static int compare(String a, String b) {
        String[] x = a.split("[^0-9]+");
        String[] y = b.split("[^0-9]+");
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            long p = i < x.length ? number(x[i]) : 0;
            long q = i < y.length ? number(y[i]) : 0;
            if (p != q) return p < q ? -1 : 1;
        }
        return 0;
    }

    private static long number(String s) {
        if (s.isEmpty()) return 0;
        try {
            return Long.parseLong(s.length() > 18 ? s.substring(0, 18) : s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean autoEnabled(Context c) {
        return prefs(c).getBoolean("auto", true);
    }

    static void setAutoEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("auto", on).apply();
    }

    /** True when the automatic check is on and the last check is old (or in the future: clock reset). */
    static boolean autoDue(Context c) {
        if (!autoEnabled(c)) return false;
        long last = prefs(c).getLong("last_check", 0);
        long now = System.currentTimeMillis();
        return now < last || now - last >= AUTO_EVERY_MS;
    }

    static void markChecked(Context c) {
        prefs(c).edit().putLong("last_check", System.currentTimeMillis()).apply();
    }

    /** Reads the latest published release (GitHub leaves drafts and pre-releases out of "latest"). */
    static void fetchLatest(Context context, Result<Release> result) {
        Context c = context.getApplicationContext();
        String agent = "PixoraTV/" + installedVersion(c) + " (Android TV launcher updater)";
        IO.execute(() -> {
            Release release = null;
            String error = null;
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(LATEST).openConnection();
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(15_000);
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
                conn.setRequestProperty("User-Agent", agent);
                int code = conn.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    error = c.getString(code == HttpURLConnection.HTTP_NOT_FOUND
                            ? R.string.update_err_none_published : R.string.update_err_http, code);
                } else {
                    release = parse(readAll(conn.getInputStream(), MAX_JSON_BYTES));
                }
            } catch (Exception e) {
                error = c.getString(R.string.update_err_network);
            } finally {
                if (conn != null) conn.disconnect();
            }
            Release r = release;
            String err = error;
            MAIN.post(() -> result.done(r, err));
        });
    }

    static Release parse(String json) throws org.json.JSONException {
        JSONObject o = new JSONObject(json);
        String tag = o.optString("tag_name", "");
        String version = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
        String apkUrl = null;
        String sha = null;
        long size = 0;
        JSONArray assets = o.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a == null) continue;
                String name = a.optString("name", "").toLowerCase(Locale.ROOT);
                String url = a.optString("browser_download_url", "");
                if (!name.endsWith(".apk") || !url.startsWith("https://")) continue;
                apkUrl = url;
                size = a.optLong("size", 0);
                String digest = a.optString("digest", "");
                if (digest.startsWith("sha256:")) sha = digest.substring(7).toLowerCase(Locale.ROOT);
                break;
            }
        }
        return new Release(version, apkUrl, sha, size, o.optString("body", ""));
    }

    /**
     * The release notes' first list ("## Novedades ..."), reduced to the bold lead of each point or
     * its first sentence, without Markdown. At most {@code max} points.
     */
    static List<String> highlights(String body, int max) {
        List<String> out = new ArrayList<>();
        boolean inSection = false;
        for (String raw : body.split("\r?\n")) {
            String line = raw.trim();
            if (line.startsWith("## ")) {
                if (inSection) break;
                inSection = true;
                continue;
            }
            if (!inSection || !line.startsWith("- ")) continue;
            String text = line.substring(2).trim();
            if (text.startsWith("**")) {
                int end = text.indexOf("**", 2);
                if (end > 2) text = text.substring(2, end);
            } else {
                int dot = text.indexOf(". ");
                if (dot > 0) text = text.substring(0, dot + 1);
            }
            text = text.replaceAll("\\[([^\\]]*)]\\([^)]*\\)", "$1").replace("**", "").replace("`", "").trim();
            if (text.length() > 140) text = text.substring(0, 139) + "…";
            if (!text.isEmpty()) out.add(text);
            if (out.size() >= max) break;
        }
        return out;
    }

    /** Downloads the release's APK into the cache and checks it; {@code cancel} stops it between reads. */
    static void download(Context context, Release release, Progress progress, AtomicBoolean cancel,
                         Result<File> result) {
        Context c = context.getApplicationContext();
        IO.execute(() -> {
            File dir = new File(c.getCacheDir(), "update");
            File apk = new File(dir, "pixoratv.apk");
            String error = null;
            HttpURLConnection conn = null;
            try {
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cache");
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
                    f.delete();
                // GitHub answers with a redirect to its file host (https to https: followed here).
                conn = (HttpURLConnection) new URL(release.apkUrl).openConnection();
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(30_000);
                conn.setRequestProperty("User-Agent", "PixoraTV/" + installedVersion(c));
                int code = conn.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code);
                long total = release.size > 0 ? release.size : conn.getContentLength();
                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                long read = 0;
                int shown = -1;
                try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (cancel.get()) throw new InterruptedException();
                        read += n;
                        if (read > MAX_APK_BYTES) throw new IOException("too big");
                        sha.update(buf, 0, n);
                        out.write(buf, 0, n);
                        int pct = total > 0 ? (int) Math.min(100, read * 100 / total) : 0;
                        if (pct != shown) {
                            shown = pct;
                            MAIN.post(() -> progress.at(pct));
                        }
                    }
                }
                if (release.size > 0 && read != release.size) {
                    error = c.getString(R.string.update_err_network);
                } else if (release.sha256 != null && !release.sha256.equals(hex(sha.digest()))) {
                    error = c.getString(R.string.update_err_checksum);
                } else if (!samePackageNewerAndSameKey(c, apk)) {
                    error = c.getString(R.string.update_err_package);
                }
            } catch (InterruptedException e) {
                error = c.getString(R.string.update_cancelled);
            } catch (Exception e) {
                error = c.getString(R.string.update_err_network);
            } finally {
                if (conn != null) conn.disconnect();
            }
            if (error != null) //noinspection ResultOfMethodCallIgnored
                apk.delete();
            String err = error;
            MAIN.post(() -> result.done(err == null ? apk : null, err));
        });
    }

    @SuppressWarnings("deprecation")
    private static boolean samePackageNewerAndSameKey(Context c, File apk) {
        PackageManager pm = c.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo archive = pm.getPackageArchiveInfo(apk.getPath(), flags);
        if (archive == null || !c.getPackageName().equals(archive.packageName)) return false;
        try {
            PackageInfo installed = pm.getPackageInfo(c.getPackageName(), flags);
            if (versionCode(archive) <= versionCode(installed)) return false;
            Set<String> mine = certs(installed);
            Set<String> theirs = certs(archive);
            // Some Android versions do not read an archive's certificates; the installer still checks them.
            return mine == null || theirs == null || mine.equals(theirs);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private static long versionCode(PackageInfo p) {
        return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
    }

    @SuppressWarnings("deprecation")
    private static Set<String> certs(PackageInfo p) {
        Signature[] sigs;
        if (Build.VERSION.SDK_INT >= 28) {
            if (p.signingInfo == null) return null;
            sigs = p.signingInfo.hasMultipleSigners() ? p.signingInfo.getApkContentsSigners()
                    : p.signingInfo.getSigningCertificateHistory();
        } else {
            sigs = p.signatures;
        }
        if (sigs == null || sigs.length == 0) return null;
        Set<String> out = new HashSet<>();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Signature s : sigs) out.add(hex(md.digest(s.toByteArray())));
        } catch (Exception e) {
            return null;
        }
        return out;
    }

    /** Android asks for this per app ("Install unknown apps"); instalar.ps1 grants it over adb. */
    static boolean canInstall(Context c) {
        return c.getPackageManager().canRequestPackageInstalls();
    }

    static Intent unknownSourcesSettings(Context c) {
        return new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.getPackageName()));
    }

    /**
     * Writes the APK into a PackageInstaller session and commits it. Android shows its confirmation
     * (UpdateReceiver opens it) and then replaces this app, which ends this process.
     */
    static void install(Context context, File apk, Result<Boolean> result) {
        Context c = context.getApplicationContext();
        IO.execute(() -> {
            String error = null;
            PackageInstaller installer = c.getPackageManager().getPackageInstaller();
            PackageInstaller.Session session = null;
            try {
                PackageInstaller.SessionParams params =
                        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(c.getPackageName());
                params.setSize(apk.length());
                // Android 12+: no prompt once this app is the installer of record; else Android asks.
                if (Build.VERSION.SDK_INT >= 31) {
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                }
                int id = installer.createSession(params);
                session = installer.openSession(id);
                try (InputStream in = new FileInputStream(apk);
                     OutputStream out = session.openWrite("pixoratv.apk", 0, apk.length())) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    session.fsync(out);
                }
                Intent done = new Intent(c, UpdateReceiver.class);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent pi = PendingIntent.getBroadcast(c, id, done, flags);
                session.commit(pi.getIntentSender());
                session.close();
                session = null;
            } catch (Exception e) {
                error = c.getString(R.string.update_err_install);
                if (session != null) session.abandon();
            }
            String err = error;
            MAIN.post(() -> result.done(err == null, err));
        });
    }

    private static String readAll(InputStream in, int max) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = s.read(buf)) > 0) {
                if (out.size() + n > max) throw new IOException("too big");
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.toString();
    }
}
