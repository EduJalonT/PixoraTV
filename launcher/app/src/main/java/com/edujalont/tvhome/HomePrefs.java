package com.edujalont.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What the viewer changes from the remote: dock order, hidden apps, recently opened apps, the ultralight
 * mode and the weather place. Defaults come from res/values/config.xml.
 */
final class HomePrefs {

    /** Preference file this launcher uses. Public so other screens (InputCardsActivity) can read the
     *  same store instead of inventing a second one. */
    static final String PREFS_NAME = "home";

    private static final int MAX_RECENTS = 8;
    /** Weather older than this is fetched again, and only while the home screen is on screen. */
    static final long WEATHER_MAX_AGE_MS = 60 * 60_000L;

    /**
     * The three named experience presets, plus a marker for a configuration changed one option at a
     * time. Performance is the default for a new install; an upgrade keeps the old preferences and
     * starts as Custom until the viewer chooses a preset.
     */
    enum Experience {
        PERFORMANCE("performance"),
        BALANCED("balanced"),
        VISUAL("visual"),
        CUSTOM("custom");

        final String key;

        Experience(String key) {
            this.key = key;
        }

        static Experience from(String key) {
            for (Experience e : values()) {
                if (e.key.equals(key)) return e;
            }
            return CUSTOM;
        }
    }

    private static final String KEY_EXPERIENCE = "experience";

    private final SharedPreferences sp;
    private final Context ctx;

    HomePrefs(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.sp = this.ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        ensureExperience();
    }

    // ---- dock -----------------------------------------------------------------------------------

    List<String> dock() {
        String saved = sp.getString("dock", null);
        if (saved == null) return new ArrayList<>(Arrays.asList(ctx.getResources().getStringArray(R.array.dock_apps)));
        return split(saved);
    }

    void setDock(List<String> pkgs) {
        sp.edit().putString("dock", TextUtils.join(",", pkgs)).apply();
    }

    void resetDock() {
        sp.edit().remove("dock").apply();
    }

    // ---- app order -----------------------------------------------------------------------------

    /**
     * The viewer's own order of the apps outside the dock (set from a tile's menu), or empty for the
     * automatic one: recent apps first, then alphabetical. Apps it does not list go last, alphabetical.
     */
    List<String> appOrder() {
        return split(sp.getString("order", ""));
    }

    void setAppOrder(List<String> pkgs) {
        sp.edit().putString("order", TextUtils.join(",", pkgs)).apply();
    }

    void resetAppOrder() {
        sp.edit().remove("order").apply();
    }

    // ---- hidden apps ----------------------------------------------------------------------------

    /** Hidden by config.xml plus hidden by the viewer. */
    Set<String> hidden() {
        Set<String> all = new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.hidden_apps)));
        all.addAll(userHidden());
        return all;
    }

    Set<String> userHidden() {
        return new HashSet<>(split(sp.getString("hidden", "")));
    }

    void hide(String pkg) {
        Set<String> h = userHidden();
        h.add(pkg);
        sp.edit().putString("hidden", TextUtils.join(",", h)).apply();
    }

    void unhideAll() {
        sp.edit().remove("hidden").apply();
    }

    // ---- recently opened ------------------------------------------------------------------------

    /** Packages opened from this launcher, most recent first. */
    List<String> recents() {
        return split(sp.getString("recents", ""));
    }

    void touchRecent(String pkg) {
        List<String> r = recents();
        r.remove(pkg);
        r.add(0, pkg);
        while (r.size() > MAX_RECENTS) r.remove(r.size() - 1);
        sp.edit().putString("recents", TextUtils.join(",", r)).apply();
    }

    // ---- ultralight mode ------------------------------------------------------------------------

    /** Apps only: no ambient glow, no app title, no weather, no springs or tile animations. */
    boolean light() {
        return sp.getBoolean("light", true);   // Eddy TV default: instant focus, no motion
    }

    void setLight(boolean on) {
        sp.edit().putBoolean("light", on).putString(KEY_EXPERIENCE, Experience.CUSTOM.key).apply();
    }

    // ---- experience presets ---------------------------------------------------------------------

    /**
     * New installs start in Performance. An existing install only gets the new key and keeps its
     * saved light/wallpaper values, so an update never changes the look before the viewer chooses.
     */
    private void ensureExperience() {
        if (sp.contains(KEY_EXPERIENCE)) return;
        if (sp.getAll().isEmpty()) {
            sp.edit().putString(KEY_EXPERIENCE, Experience.PERFORMANCE.key)
                    .putBoolean("light", true).putString("background", "black").apply();
        } else {
            sp.edit().putString(KEY_EXPERIENCE, Experience.CUSTOM.key).apply();
        }
    }

    Experience experience() {
        return Experience.from(sp.getString(KEY_EXPERIENCE, Experience.CUSTOM.key));
    }

    /** Sets one of the three presets. Custom is only a marker; it does not change any value. */
    void applyExperience(Experience experience) {
        if (experience == Experience.CUSTOM) {
            sp.edit().putString(KEY_EXPERIENCE, Experience.CUSTOM.key).apply();
            return;
        }
        boolean light = experience == Experience.PERFORMANCE;
        String background = experience == Experience.VISUAL ? "aurora" : "black";
        sp.edit().putBoolean("light", light).putString("background", background)
                .putString(KEY_EXPERIENCE, experience.key).apply();
    }

    // ---- columns ---------------------------------------------------------------------------------

    /** Tiles per row chosen on the TV (4-6), or res/values/config.xml when nothing valid is saved. */
    int columns() {
        int saved = sp.getInt("columns", 0);
        return saved >= 4 && saved <= 6 ? saved : ctx.getResources().getInteger(R.integer.columns);
    }

    void setColumns(int n) {
        sp.edit().putInt("columns", n).apply();
    }

    // ---- wallpaper -------------------------------------------------------------------------------

    /** Background id from MainActivity.BG_IDS; ultralight always stays black. Older builds saved an on/off flag. */
    String wallpaper() {
        String id = sp.getString("background", null);
        return id != null ? id : sp.getBoolean("wallpaper", true) ? "aurora" : "black";
    }

    void setWallpaper(String id) {
        sp.edit().putString("background", id).putString(KEY_EXPERIENCE, Experience.CUSTOM.key).apply();
    }

    // ---- family profiles -------------------------------------------------------------------------

    /** Saved profiles by label: {label: {name, message, accent, background}}. No image data is stored. */
    JSONObject profiles() {
        try {
            return new JSONObject(sp.getString("profiles", "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    /** Saves the current name, message, accent and background under a label; an existing label is replaced. */
    void saveProfile(String label) {
        String key = label.trim();
        if (key.isEmpty()) return;
        try {
            JSONObject all = profiles();
            JSONObject profile = new JSONObject();
            profile.put("name", userName());
            profile.put("message", message());
            profile.put("accent", accent());
            profile.put("background", wallpaper());
            all.put(key, profile);
            sp.edit().putString("profiles", all.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    void applyProfile(String label) {
        JSONObject profile = profiles().optJSONObject(label);
        if (profile == null) return;
        sp.edit()
                .putString("name", profile.optString("name", "").trim())
                .putString("message", profile.optString("message", "").trim())
                .putInt("accent", profile.optInt("accent", 0))
                .putString("background", profile.optString("background",
                        profile.optBoolean("wallpaper", true) ? "aurora" : "black"))
                .putString(KEY_EXPERIENCE, Experience.CUSTOM.key)
                .apply();
    }

    void deleteProfile(String label) {
        JSONObject all = profiles();
        all.remove(label);
        sp.edit().putString("profiles", all.toString()).apply();
    }

    // ---- identity ----------------------------------------------------------------------------------

    /** The owner's name, or "" until one is typed: there is no built-in name. */
    String userName() {
        return sp.getString("name", "").trim();
    }

    void setUserName(String name) {
        sp.edit().putString("name", name.trim()).apply();
    }

    /** True once the owner typed a name. */
    boolean hasUserName() {
        return !sp.getString("name", "").trim().isEmpty();
    }

    /** "Good morning, everyone." instead of the name; always without a typed name. */
    boolean greetEveryone() {
        return !hasUserName() || sp.getBoolean("greet_everyone", false);
    }

    void setGreetEveryone(boolean everyone) {
        sp.edit().putBoolean("greet_everyone", everyone).apply();
    }

    /** Custom line under the greeting, or "" for the automatic one per time of day. */
    String message() {
        return sp.getString("message", "").trim();
    }

    void setMessage(String message) {
        sp.edit().putString("message", message.trim()).apply();
    }

    int accent() {
        return sp.getInt("accent", 0);
    }

    void setAccent(int index) {
        sp.edit().putInt("accent", index).apply();
    }

    // ---- Globoplay button remap (KeyRemapService) ---------------------------------------------

    static final String KEY_REMAP = "remap";
    static final String DEFAULT_REMAP = "com.unitvnet.tvod";

    /** App opened by the remote's Globoplay button, or "" to leave the button alone. */
    String remapTarget() {
        return sp.getString(KEY_REMAP, DEFAULT_REMAP);
    }

    void setRemapTarget(String pkg) {
        sp.edit().putString(KEY_REMAP, pkg).apply();
    }

    void listen(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        sp.registerOnSharedPreferenceChangeListener(listener);
    }

    /** Recovery: name, message, colour, motion, columns, wallpaper, profiles, dock and hidden apps back to the defaults. */
    void resetAll() {
        sp.edit().remove("name").remove("message").remove("accent").remove("light")
                .remove("columns").remove("wallpaper").remove("background").remove("profiles").remove("dock").remove("hidden")
                .remove("order").remove("greet_everyone")
                .putBoolean("light", true).putString("background", "black")
                .putString(KEY_EXPERIENCE, Experience.PERFORMANCE.key).apply();
    }

    // ---- weather --------------------------------------------------------------------------------

    boolean hasWeatherPlace() {
        return sp.contains("wx_lat");
    }

    String weatherPlace() {
        return sp.getString("wx_place", "");
    }

    double weatherLat() {
        return Double.longBitsToDouble(sp.getLong("wx_lat", 0));
    }

    double weatherLon() {
        return Double.longBitsToDouble(sp.getLong("wx_lon", 0));
    }

    void setWeatherPlace(String place, double lat, double lon) {
        sp.edit().putString("wx_place", place)
                .putLong("wx_lat", Double.doubleToRawLongBits(lat))
                .putLong("wx_lon", Double.doubleToRawLongBits(lon))
                .remove("wx_text").remove("wx_at").apply();
    }

    void clearWeather() {
        sp.edit().remove("wx_place").remove("wx_lat").remove("wx_lon").remove("wx_text").remove("wx_at")
                .remove("wx_code").remove("wx_day").apply();
    }

    /** Last fetched weather, e.g. "23° NUBLADO", or "" when none. */
    String weatherText() {
        return sp.getString("wx_text", "");
    }

    /** The city alone, without the region Weather.find appends ("Guayaquil, Guayas" → "Guayaquil"). */
    String weatherCity() {
        String place = weatherPlace();
        int comma = place.indexOf(',');
        return (comma < 0 ? place : place.substring(0, comma)).trim();
    }

    /** Font Awesome icon for the last fetched weather (Weather.icon). */
    int weatherIcon() {
        return Weather.icon(sp.getInt("wx_code", 3), sp.getBoolean("wx_day", true));
    }

    boolean weatherStale() {
        if (!sp.contains("wx_code")) return true;     // cached before the icon was stored
        return System.currentTimeMillis() - sp.getLong("wx_at", 0) > WEATHER_MAX_AGE_MS;
    }

    void setWeather(Weather.Now now) {
        sp.edit().putString("wx_text", now.text).putInt("wx_code", now.code).putBoolean("wx_day", now.day)
                .putLong("wx_at", System.currentTimeMillis()).apply();
    }

    private static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String p : s.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }
}
