package com.edujalont.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Live scores ("Marcadores"): up to MAX_SLOTS things the owner follows, each a competition from
 * CATALOG and optionally one team of it. Off by default; nothing is fetched until it is turned on.
 * <ul>
 * <li>ESPN's public scoreboard JSON (site.api.espn.com) for every competition: no key, no account.
 * It is not an official API, so it may change without notice.</li>
 * <li>API-Football (api-sports.io), only with the owner's own key and only when ESPN fails for a
 * football competition: one request for the whole day, cached, so the free 100 a day are plenty.</li>
 * <li>OpenF1 for the order during an F1 session that ESPN reports live; it may ask for an account
 * while a session runs (401), and then ESPN's order is kept.</li>
 * </ul>
 * Kept in the "home" preferences like the news: sports_enabled, sports_slot_N ("id" or
 * "id|teamId|teamName"), sports_apifootball_key.
 */
final class SportsFeed {

    static final int MAX_SLOTS = 10;
    /** How often Home refreshes the cache for the red dot, at most, while it is in front. */
    static final long DOT_REFRESH_MS = 5 * 60_000L;
    private static final int CONNECT_MS = 6000, READ_MS = 9000;
    private static final int MAX_ROWS = 8;
    private static final String ESPN = "https://site.api.espn.com/apis/site/v2/sports/";
    private static final String API_FOOTBALL = "https://v3.football.api-sports.io/fixtures";
    private static final String OPENF1 = "https://api.openf1.org/v1/";

    /** One followable competition: ESPN's sport/league path, API-Football's league id (0: none). */
    static final class Comp {
        final String id, name, espn;
        final int apiFootball;
        final boolean f1;

        Comp(String id, String name, String espn, int apiFootball, boolean f1) {
            this.id = id;
            this.name = name;
            this.espn = espn;
            this.apiFootball = apiFootball;
            this.f1 = f1;
        }
    }

    static final Comp[] CATALOG = {
            new Comp("ecu.1", "LigaPro Ecuador", "soccer/ecu.1", 242, false),
            new Comp("libertadores", "Copa Libertadores", "soccer/conmebol.libertadores", 13, false),
            new Comp("sudamericana", "Copa Sudamericana", "soccer/conmebol.sudamericana", 11, false),
            new Comp("wcq.conmebol", "Eliminatorias Sudamericanas", "soccer/fifa.worldq.conmebol", 34, false),
            new Comp("fifa.world", "Mundial", "soccer/fifa.world", 1, false),
            new Comp("champions", "Champions League", "soccer/uefa.champions", 2, false),
            new Comp("eng.1", "Premier League", "soccer/eng.1", 39, false),
            new Comp("esp.1", "LaLiga", "soccer/esp.1", 140, false),
            new Comp("ita.1", "Serie A", "soccer/ita.1", 135, false),
            new Comp("ger.1", "Bundesliga", "soccer/ger.1", 78, false),
            new Comp("usa.1", "MLS", "soccer/usa.1", 253, false),
            new Comp("nba", "NBA", "basketball/nba", 0, false),
            new Comp("nfl", "NFL", "football/nfl", 0, false),
            new Comp("mlb", "MLB", "baseball/mlb", 0, false),
            new Comp("nhl", "NHL", "hockey/nhl", 0, false),
            new Comp("f1", "Fórmula 1", "racing/f1", 0, true),
    };

    static final int PRE = 0, LIVE = 1, POST = 2;

    /** One line of the panel: a game, an F1 session or one F1 position. */
    static final class Row {
        int state;
        long start;
        /** Short status: the minute or period while live, a time before, "Final" after (filled by the UI when null). */
        String status;
        String text;
    }

    /** One followed thing: its title and its rows (empty: nothing in these days). */
    static final class Group {
        String title;
        final List<Row> rows = new ArrayList<>();
        boolean failed;
    }

    interface Listener {
        void onGroups(List<Group> groups);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean FETCHING = new AtomicBoolean();
    private static volatile List<Group> cache = Collections.emptyList();
    private static volatile long cachedAt;
    /** API-Football's day of fixtures, shared by every football slot that needs it. */
    private static JSONArray apiDay;
    private static long apiDayAt;
    private static String apiDayDate;

    private SportsFeed() {}

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(HomePrefs.PREFS_NAME, Context.MODE_PRIVATE);
    }

    static boolean enabled(Context ctx) {
        return prefs(ctx).getBoolean("sports_enabled", false);
    }

    static String slotKey(int slot) {
        return "sports_slot_" + (slot + 1);
    }

    static String slot(SharedPreferences sp, int slot) {
        return sp.getString(slotKey(slot), "").trim();
    }

    static Comp comp(String id) {
        for (Comp c : CATALOG) if (c.id.equals(id)) return c;
        return null;
    }

    /** "LigaPro Ecuador" or "Emelec · LigaPro Ecuador"; null when the slot is empty or unknown. */
    static String slotLabel(String value) {
        if (value.isEmpty()) return null;
        String[] p = value.split("\\|", -1);
        Comp c = comp(p[0]);
        if (c == null) return null;
        return p.length >= 3 && !p[2].isEmpty() ? p[2] + " · " + c.name : c.name;
    }

    static int followed(SharedPreferences sp) {
        int n = 0;
        for (int i = 0; i < MAX_SLOTS; i++) if (slotLabel(slot(sp, i)) != null) n++;
        return n;
    }

    /** The options panel's line under "Marcadores". */
    static String summary(Context ctx) {
        if (!enabled(ctx)) return ctx.getString(R.string.off);
        return ctx.getString(R.string.scores_following, followed(prefs(ctx)));
    }

    static List<Group> cached() {
        return cache;
    }

    static boolean dotStale() {
        return System.currentTimeMillis() - cachedAt > DOT_REFRESH_MS;
    }

    static boolean anyLive(List<Group> groups) {
        if (groups == null) return false;
        for (Group g : groups) for (Row r : g.rows) if (r.state == LIVE) return true;
        return false;
    }

    /** Fetches every followed slot off the main thread; the listener runs on it. One flight at a time. */
    static void fetch(Context ctx, Listener listener) {
        Context app = ctx.getApplicationContext();
        if (!enabled(app) || !FETCHING.compareAndSet(false, true)) return;
        new Thread(() -> {
            List<Group> groups = new ArrayList<>();
            SharedPreferences sp = prefs(app);
            String key = sp.getString("sports_apifootball_key", "").trim();
            for (int i = 0; i < MAX_SLOTS; i++) {
                String value = slot(sp, i);
                if (slotLabel(value) == null) continue;
                String[] p = value.split("\\|", -1);
                Comp c = comp(p[0]);
                String teamId = p.length >= 2 ? p[1] : "";
                String teamName = p.length >= 3 ? p[2] : "";
                Group g = new Group();
                g.title = slotLabel(value);
                try {
                    if (c.f1) f1(app, g);
                    else espn(c, teamId, g);
                } catch (Exception e) {
                    g.rows.clear();
                    g.failed = true;
                }
                if (g.failed && c.apiFootball > 0 && !key.isEmpty()) {
                    try {
                        apiFootball(key, c, teamName, g);
                        g.failed = false;
                    } catch (Exception ignored) {
                    }
                }
                groups.add(g);
            }
            cache = groups;
            cachedAt = System.currentTimeMillis();
            MAIN.post(() -> {
                FETCHING.set(false);
                listener.onGroups(groups);
            });
        }, "scores").start();
    }

    // ---- ESPN --------------------------------------------------------------------------------------

    private static void espn(Comp c, String teamId, Group g) throws Exception {
        JSONObject board = new JSONObject(get(ESPN + c.espn + "/scoreboard", null));
        List<Row> rows = new ArrayList<>();
        JSONArray events = board.optJSONArray("events");
        if (events != null) {
            for (int i = 0; i < events.length(); i++) {
                Row r = espnGame(events.getJSONObject(i), teamId);
                if (r != null) rows.add(r);
            }
        }
        // A team with no game on the current scoreboard: its last result and its next game.
        if (!teamId.isEmpty() && rows.isEmpty()) {
            JSONObject sched = new JSONObject(get(ESPN + c.espn + "/teams/" + teamId + "/schedule", null));
            JSONArray ev = sched.optJSONArray("events");
            Row last = null, next = null;
            if (ev != null) {
                for (int i = 0; i < ev.length(); i++) {
                    Row r = espnGame(ev.getJSONObject(i), teamId);
                    if (r == null) continue;
                    if (r.state == POST) {
                        if (last == null || r.start > last.start) last = r;
                    } else if (next == null || r.start < next.start) next = r;
                }
            }
            if (next != null) rows.add(next);
            if (last != null) rows.add(last);
        }
        sortAndTrim(rows);
        g.rows.addAll(rows);
    }

    /** One ESPN event as a row, or null when it is not about this team (teamId empty: any). */
    private static Row espnGame(JSONObject ev, String teamId) {
        JSONArray comps = ev.optJSONArray("competitions");
        if (comps == null || comps.length() == 0) return null;
        JSONObject comp = comps.optJSONObject(0);
        JSONArray teams = comp.optJSONArray("competitors");
        if (teams == null || teams.length() < 2) return null;
        JSONObject home = null, away = null;
        for (int i = 0; i < teams.length(); i++) {
            JSONObject t = teams.optJSONObject(i);
            if ("away".equals(t.optString("homeAway"))) away = t;
            else home = t;
        }
        if (home == null || away == null) return null;
        if (!teamId.isEmpty() && !teamId.equals(teamIdOf(home)) && !teamId.equals(teamIdOf(away))) return null;
        JSONObject status = comp.optJSONObject("status");
        if (status == null) status = ev.optJSONObject("status");
        JSONObject type = status == null ? null : status.optJSONObject("type");
        String state = type == null ? "pre" : type.optString("state", "pre");
        Row r = new Row();
        r.start = parseTime(ev.optString("date", comp.optString("date")));
        String h = teamName(home), a = teamName(away);
        if ("pre".equals(state)) {
            r.state = PRE;
            r.text = h + "  –  " + a;
        } else {
            r.state = "in".equals(state) ? LIVE : POST;
            r.text = h + "  " + score(home) + " – " + score(away) + "  " + a;
            if (r.state == LIVE && type != null) r.status = type.optString("shortDetail", "");
            // Postponed or cancelled: say so instead of "Final".
            if (r.state == POST && type != null && !type.optBoolean("completed", true)) {
                r.status = type.optString("shortDetail", "");
            }
        }
        return r;
    }

    private static String teamIdOf(JSONObject competitor) {
        JSONObject t = competitor.optJSONObject("team");
        return t == null ? competitor.optString("id") : t.optString("id");
    }

    private static String teamName(JSONObject competitor) {
        JSONObject t = competitor.optJSONObject("team");
        if (t == null) return "?";
        String n = t.optString("shortDisplayName", "");
        return n.isEmpty() ? t.optString("displayName", "?") : n;
    }

    /** The scoreboard gives the score as text, a team's schedule as an object. */
    private static String score(JSONObject competitor) {
        Object s = competitor.opt("score");
        if (s instanceof JSONObject) return ((JSONObject) s).optString("displayValue", "0");
        return s == null ? "0" : String.valueOf(s);
    }

    /** Live first, then the next ones by time, then the results, newest first; at most MAX_ROWS. */
    private static void sortAndTrim(List<Row> rows) {
        Collections.sort(rows, (x, y) -> {
            if (x.state != y.state) return Integer.compare(rank(x.state), rank(y.state));
            return x.state == POST ? Long.compare(y.start, x.start) : Long.compare(x.start, y.start);
        });
        while (rows.size() > MAX_ROWS) rows.remove(rows.size() - 1);
    }

    private static int rank(int state) {
        return state == LIVE ? 0 : state == PRE ? 1 : 2;
    }

    // ---- F1 (ESPN, OpenF1 while live) -----------------------------------------------------------------

    private static void f1(Context ctx, Group g) throws Exception {
        JSONObject board = new JSONObject(get(ESPN + "racing/f1/scoreboard", null));
        JSONArray events = board.optJSONArray("events");
        if (events == null || events.length() == 0) return;
        JSONObject ev = events.getJSONObject(0);
        String gp = ev.optString("shortName", ev.optString("name", ""));
        g.title = g.title + " · " + gp;
        JSONArray sessions = ev.optJSONArray("competitions");
        if (sessions == null) return;
        JSONObject live = null, last = null, next = null;
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject s = sessions.getJSONObject(i);
            JSONObject type = s.optJSONObject("status") == null ? null : s.optJSONObject("status").optJSONObject("type");
            String state = type == null ? "pre" : type.optString("state", "pre");
            if ("in".equals(state)) live = s;
            else if ("post".equals(state)) last = s;          // sessions come in order: keep the latest
            else if (next == null) next = s;
        }
        JSONObject shown = live != null ? live : last;
        if (shown != null) {
            Row head = new Row();
            head.state = live != null ? LIVE : POST;
            head.start = parseTime(shown.optString("date"));
            head.text = sessionName(ctx, shown);
            g.rows.add(head);
            List<String> order = live != null ? openF1Order() : null;
            if (order == null) order = espnOrder(shown);
            for (int i = 0; i < order.size() && i < (live != null ? 10 : 5); i++) {
                Row r = new Row();
                r.state = head.state;
                r.status = "P" + (i + 1);
                r.text = order.get(i);
                g.rows.add(r);
            }
        }
        if (next != null) {
            Row r = new Row();
            r.state = PRE;
            r.start = parseTime(next.optString("date"));
            r.text = sessionName(ctx, next);
            g.rows.add(r);
        }
    }

    private static String sessionName(Context ctx, JSONObject session) {
        JSONObject type = session.optJSONObject("type");
        String abbr = type == null ? "" : type.optString("abbreviation", "");
        switch (abbr) {
            case "Race": return ctx.getString(R.string.scores_f1_race);
            case "Qual": return ctx.getString(R.string.scores_f1_qual);
            case "Sprint": return ctx.getString(R.string.scores_f1_sprint);
            default: return abbr.isEmpty() ? ctx.getString(R.string.scores_f1_session) : abbr;
        }
    }

    private static List<String> espnOrder(JSONObject session) {
        JSONArray c = session.optJSONArray("competitors");
        List<JSONObject> list = new ArrayList<>();
        if (c != null) for (int i = 0; i < c.length(); i++) list.add(c.optJSONObject(i));
        Collections.sort(list, (x, y) -> Integer.compare(x.optInt("order", 99), y.optInt("order", 99)));
        List<String> out = new ArrayList<>();
        for (JSONObject o : list) {
            JSONObject a = o.optJSONObject("athlete");
            out.add(a == null ? "?" : a.optString("shortName", a.optString("displayName", "?")));
        }
        return out;
    }

    /** The current order from OpenF1, or null when it does not answer (it can ask for an account while live). */
    private static List<String> openF1Order() {
        try {
            JSONArray pos = new JSONArray(get(OPENF1 + "position?session_key=latest", null));
            JSONArray drivers = new JSONArray(get(OPENF1 + "drivers?session_key=latest", null));
            Map<Integer, String> names = new HashMap<>();
            for (int i = 0; i < drivers.length(); i++) {
                JSONObject d = drivers.getJSONObject(i);
                names.put(d.optInt("driver_number"), d.optString("broadcast_name", d.optString("name_acronym", "?")));
            }
            // The feed is every change over the session: the latest line of each driver is his place.
            Map<Integer, Integer> place = new HashMap<>();
            Map<Integer, String> at = new HashMap<>();
            for (int i = 0; i < pos.length(); i++) {
                JSONObject p = pos.getJSONObject(i);
                int n = p.optInt("driver_number");
                String date = p.optString("date");
                if (!at.containsKey(n) || date.compareTo(at.get(n)) > 0) {
                    at.put(n, date);
                    place.put(n, p.optInt("position"));
                }
            }
            if (place.isEmpty()) return null;
            String[] byPlace = new String[place.size() + 1];
            for (Map.Entry<Integer, Integer> e : place.entrySet()) {
                int p = e.getValue();
                if (p >= 1 && p < byPlace.length) byPlace[p] = names.containsKey(e.getKey()) ? names.get(e.getKey()) : "#" + e.getKey();
            }
            List<String> out = new ArrayList<>();
            for (int i = 1; i < byPlace.length; i++) if (byPlace[i] != null) out.add(byPlace[i]);
            return out.isEmpty() ? null : out;
        } catch (Exception e) {
            return null;
        }
    }

    // ---- API-Football (owner's key, fallback for football) ------------------------------------------

    private static void apiFootball(String key, Comp c, String teamName, Group g) throws Exception {
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        String today = day.format(new Date());
        JSONArray all;
        synchronized (SportsFeed.class) {
            if (apiDay == null || !today.equals(apiDayDate) || System.currentTimeMillis() - apiDayAt > DOT_REFRESH_MS) {
                Map<String, String> h = new HashMap<>();
                h.put("x-apisports-key", key);
                String tz = TimeZone.getDefault().getID();
                JSONObject res = new JSONObject(get(API_FOOTBALL + "?date=" + today + "&timezone=" + tz, h));
                apiDay = res.optJSONArray("response");
                if (apiDay == null) throw new IOException("API-Football: " + res.optString("errors"));
                apiDayDate = today;
                apiDayAt = System.currentTimeMillis();
            }
            all = apiDay;
        }
        String want = fold(teamName);
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < all.length(); i++) {
            JSONObject f = all.getJSONObject(i);
            JSONObject league = f.optJSONObject("league");
            if (league == null || league.optInt("id") != c.apiFootball) continue;
            JSONObject teams = f.optJSONObject("teams");
            String h = teams.optJSONObject("home").optString("name");
            String a = teams.optJSONObject("away").optString("name");
            if (!want.isEmpty() && !fold(h).contains(want) && !fold(a).contains(want)) continue;
            JSONObject fx = f.optJSONObject("fixture");
            JSONObject st = fx.optJSONObject("status");
            String s = st == null ? "NS" : st.optString("short", "NS");
            JSONObject goals = f.optJSONObject("goals");
            Row r = new Row();
            r.start = parseTime(fx.optString("date"));
            if ("NS".equals(s) || "TBD".equals(s)) {
                r.state = PRE;
                r.text = h + "  –  " + a;
            } else {
                boolean over = "FT".equals(s) || "AET".equals(s) || "PEN".equals(s)
                        || "PST".equals(s) || "CANC".equals(s) || "ABD".equals(s) || "AWD".equals(s) || "WO".equals(s);
                r.state = over ? POST : LIVE;
                r.text = h + "  " + goals.optInt("home") + " – " + goals.optInt("away") + "  " + a;
                if (r.state == LIVE) r.status = st.isNull("elapsed") ? s : st.optInt("elapsed") + "'";
                else if (!"FT".equals(s)) r.status = s;
            }
            rows.add(r);
        }
        sortAndTrim(rows);
        g.rows.clear();
        g.rows.addAll(rows);
    }

    /** Lower case without accents, to match "Barcelona SC" with "barcelona" and "Delfín" with "delfin". */
    static String fold(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).trim();
    }

    // ---- helpers -----------------------------------------------------------------------------------

    /** The teams of an ESPN competition for the settings picker: {id, name}, sorted by name. */
    static List<String[]> teams(Comp c) throws Exception {
        JSONObject o = new JSONObject(get(ESPN + c.espn + "/teams", null));
        JSONArray teams = o.getJSONArray("sports").getJSONObject(0).getJSONArray("leagues")
                .getJSONObject(0).getJSONArray("teams");
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < teams.length(); i++) {
            JSONObject t = teams.getJSONObject(i).getJSONObject("team");
            out.add(new String[]{t.optString("id"), t.optString("shortDisplayName", t.optString("displayName"))});
        }
        Collections.sort(out, (x, y) -> fold(x[1]).compareTo(fold(y[1])));
        return out;
    }

    static long parseTime(String iso) {
        if (iso == null || iso.isEmpty()) return 0;
        String[] patterns = {"yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mmXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"};
        for (String p : patterns) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(p, Locale.US);
                return f.parse(iso).getTime();
            } catch (ParseException ignored) {
            }
        }
        return 0;
    }

    /** "19:00" today, "sáb 19:00" another day (the TV's language and time zone). */
    static String startLabel(long start) {
        if (start == 0) return "";
        Calendar now = Calendar.getInstance(), at = Calendar.getInstance();
        at.setTimeInMillis(start);
        boolean today = now.get(Calendar.YEAR) == at.get(Calendar.YEAR)
                && now.get(Calendar.DAY_OF_YEAR) == at.get(Calendar.DAY_OF_YEAR);
        return new SimpleDateFormat(today ? "HH:mm" : "EEE HH:mm", Locale.getDefault()).format(new Date(start));
    }

    private static String get(String url, Map<String, String> headers) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(CONNECT_MS);
        conn.setReadTimeout(READ_MS);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "Pixora TV (Android TV launcher)");
        if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) conn.setRequestProperty(e.getKey(), e.getValue());
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > 4 * 1024 * 1024) throw new IOException("too large");
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            conn.disconnect();
        }
    }
}
