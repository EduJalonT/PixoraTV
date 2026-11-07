package com.edujalont.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * League logos, team crests and drivers' flags for the scores panel. Each image is fetched once (small,
 * see SportsFeed.small), kept on disk in cacheDir/logos for LOGO_DAYS and in memory while the panel
 * lives; an ImageView keeps its URL as its tag, so a recycled row never shows another row's logo.
 */
final class LogoCache {

    private static final long LOGO_MS = 14L * 24 * 60 * 60 * 1000;
    private static final int MAX_BYTES = 256 * 1024;
    private static final int MAX_FILES = 400;
    private static final LruCache<String, Bitmap> MEMORY = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private LogoCache() {}

    /** Shows the image at url in view (hidden while there is none). */
    static void load(ImageView view, String url) {
        view.setTag(url);
        if (url == null || url.isEmpty()) {
            view.setImageDrawable(null);
            view.setVisibility(android.view.View.GONE);
            return;
        }
        Bitmap hit = MEMORY.get(url);
        if (hit != null) {
            view.setImageBitmap(hit);
            view.setVisibility(android.view.View.VISIBLE);
            return;
        }
        view.setImageDrawable(null);
        view.setVisibility(android.view.View.INVISIBLE);      // keeps the row's layout steady
        Context app = view.getContext().getApplicationContext();
        POOL.execute(() -> {
            Bitmap b = fetch(app, url);
            MAIN.post(() -> {
                if (!url.equals(view.getTag())) return;
                if (b == null) {
                    view.setVisibility(android.view.View.GONE);
                } else {
                    view.setImageBitmap(b);
                    view.setVisibility(android.view.View.VISIBLE);
                }
            });
        });
    }

    private static Bitmap fetch(Context ctx, String url) {
        try {
            File dir = new File(ctx.getCacheDir(), "logos");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File file = new File(dir, hash(url) + ".img");
            if (!file.exists() || System.currentTimeMillis() - file.lastModified() > LOGO_MS) {
                trim(dir);
                download(url, file);
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;   // crests need their transparency
            Bitmap b = BitmapFactory.decodeFile(file.getPath(), o);
            if (b != null && (b.getWidth() > 128 || b.getHeight() > 128)) {
                float s = 96f / Math.max(b.getWidth(), b.getHeight());
                b = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * s), Math.round(b.getHeight() * s), true);
            }
            if (b != null) MEMORY.put(url, b);
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    private static void download(String url, File to) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("User-Agent", "Pixora TV (Android TV launcher)");
        try {
            if (conn.getResponseCode() != 200) throw new Exception("HTTP " + conn.getResponseCode());
            File tmp = new File(to.getPath() + ".part");
            try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int n, total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_BYTES) throw new Exception("too large");
                    out.write(buf, 0, n);
                }
            }
            if (!tmp.renameTo(to)) throw new Exception("rename");
        } finally {
            conn.disconnect();
        }
    }

    /** The folder never grows past MAX_FILES: the oldest go first. */
    private static void trim(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length < MAX_FILES) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - MAX_FILES + 50; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    private static String hash(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
