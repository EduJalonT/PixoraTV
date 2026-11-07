package com.edujalont.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Copies the Home settings (the "home" preferences: dock, order, columns, news feeds, weather, profile,
 * background...) from one TV to another over adb, so a household's TVs can be set up alike.
 * <pre>
 *   adb shell am broadcast -n app.pixora.tv/com.edujalont.tvhome.ConfigReceiver -a com.edujalont.tvhome.EXPORT
 *   adb shell am broadcast -n app.pixora.tv/com.edujalont.tvhome.ConfigReceiver -a com.edujalont.tvhome.IMPORT --es data &lt;base64&gt;
 * </pre>
 * EXPORT returns the settings as base64 JSON in the broadcast's result data; IMPORT replaces them with
 * the given ones and restarts Home. Each value keeps its type ({"t":"s|b|i|l|f|set","v":...}).
 * Only callers holding WRITE_SECURE_SETTINGS can reach it (manifest): adb's shell and the system, not
 * other apps.
 */
public class ConfigReceiver extends BroadcastReceiver {

    static final String EXPORT = "com.edujalont.tvhome.EXPORT";
    static final String IMPORT = "com.edujalont.tvhome.IMPORT";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        SharedPreferences sp = ctx.getSharedPreferences(HomePrefs.PREFS_NAME, Context.MODE_PRIVATE);
        try {
            if (EXPORT.equals(intent.getAction())) {
                setResult(1, b64(export(sp).toString()), null);
            } else if (IMPORT.equals(intent.getAction())) {
                String data = intent.getStringExtra("data");
                if (data == null) {
                    setResult(0, "no data", null);
                    return;
                }
                String json = new String(Base64.decode(data, Base64.DEFAULT), StandardCharsets.UTF_8);
                int n = apply(sp, new JSONObject(json));
                NewsFeed.clearCache(ctx);          // the feeds may have changed: fetch them afresh
                setResult(1, "imported " + n, null);
                // Home rereads everything on a fresh start.
                ctx.startActivity(new Intent(ctx, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            }
        } catch (Exception e) {
            setResult(0, "error: " + e, null);
        }
    }

    private static String b64(String s) {
        return Base64.encodeToString(s.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    static JSONObject export(SharedPreferences sp) throws JSONException {
        JSONObject out = new JSONObject();
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            Object v = e.getValue();
            JSONObject item = new JSONObject();
            if (v instanceof String) item.put("t", "s").put("v", v);
            else if (v instanceof Boolean) item.put("t", "b").put("v", v);
            else if (v instanceof Integer) item.put("t", "i").put("v", v);
            else if (v instanceof Long) item.put("t", "l").put("v", v);
            else if (v instanceof Float) item.put("t", "f").put("v", (double) (Float) v);
            else if (v instanceof Set) {
                JSONArray a = new JSONArray();
                for (Object o : (Set<?>) v) a.put(String.valueOf(o));
                item.put("t", "set").put("v", a);
            } else continue;
            out.put(e.getKey(), item);
        }
        return out;
    }

    static int apply(SharedPreferences sp, JSONObject in) throws JSONException {
        SharedPreferences.Editor ed = sp.edit().clear();
        int n = 0;
        for (Iterator<String> it = in.keys(); it.hasNext(); ) {
            String k = it.next();
            JSONObject item = in.getJSONObject(k);
            switch (item.getString("t")) {
                case "s": ed.putString(k, item.getString("v")); break;
                case "b": ed.putBoolean(k, item.getBoolean("v")); break;
                case "i": ed.putInt(k, item.getInt("v")); break;
                case "l": ed.putLong(k, item.getLong("v")); break;
                case "f": ed.putFloat(k, (float) item.getDouble("v")); break;
                case "set": {
                    JSONArray a = item.getJSONArray("v");
                    Set<String> s = new HashSet<>();
                    for (int i = 0; i < a.length(); i++) s.add(a.getString(i));
                    ed.putStringSet(k, s);
                    break;
                }
                default: continue;
            }
            n++;
        }
        ed.commit();
        return n;
    }
}
