package com.edujalont.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Scores settings ("Marcadores"): the switch, up to SportsFeed.MAX_SLOTS things to follow (a competition
 * from the catalog, then the whole of it or one team) and the optional API-Football key. Picked from
 * lists, so nothing has to be typed with the remote except the key.
 */
public final class SportsSettingsActivity extends Activity {

    private SharedPreferences sp;
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_sports_settings);
        sp = SportsFeed.prefs(this);
        list = findViewById(R.id.list);
        fill();
    }

    private void fill() {
        int focused = -1;
        for (int i = 0; i < list.getChildCount(); i++) if (list.getChildAt(i).isFocused()) focused = i;
        list.removeAllViews();
        boolean on = SportsFeed.enabled(this);
        row(getString(R.string.scores_enabled, getString(on ? R.string.on : R.string.off)), () -> {
            sp.edit().putBoolean("sports_enabled", !on).apply();
            fill();
        });
        for (int slot = 0; slot < SportsFeed.MAX_SLOTS; slot++) {
            int s = slot;
            String label = SportsFeed.slotLabel(SportsFeed.slot(sp, s));
            row(getString(R.string.scores_slot, s + 1, label == null ? getString(R.string.scores_none) : label),
                    () -> pickComp(s));
        }
        String key = sp.getString("sports_apifootball_key", "").trim();
        row(getString(R.string.scores_apikey,
                getString(key.isEmpty() ? R.string.scores_apikey_none : R.string.scores_apikey_set)), this::editKey);
        if (focused >= 0 && focused < list.getChildCount()) list.getChildAt(focused).requestFocus();
        else if (list.getChildCount() > 0) list.getChildAt(0).requestFocus();
    }

    /** First step of a slot: which competition (or none). */
    private void pickComp(int slot) {
        // "None", the national teams (by confederation, next step), then every competition.
        String[] names = new String[SportsFeed.CATALOG.length + 2];
        names[0] = getString(R.string.scores_none);
        names[1] = getString(R.string.scores_national);
        for (int i = 0; i < SportsFeed.CATALOG.length; i++) names[i + 2] = SportsFeed.CATALOG[i].name;
        new AlertDialog.Builder(this)
                .setTitle(R.string.scores_pick_title)
                .setItems(names, (d, which) -> {
                    if (which == 0) {
                        save(slot, "");
                        return;
                    }
                    if (which == 1) {
                        pickConfederation(slot);
                        return;
                    }
                    SportsFeed.Comp c = SportsFeed.CATALOG[which - 2];
                    if (c.f1) save(slot, c.id);
                    else pickTeam(slot, c);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** National teams: which confederation, then all of its national teams or one of them. */
    private void pickConfederation(int slot) {
        String[] names = new String[SportsFeed.NATIONAL.length];
        for (int i = 0; i < names.length; i++) names[i] = SportsFeed.NATIONAL[i].name;
        new AlertDialog.Builder(this)
                .setTitle(R.string.scores_pick_confederation)
                .setItems(names, (d, which) -> pickTeam(slot, SportsFeed.NATIONAL[which]))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Second step: the whole competition or one of its teams, read from ESPN. */
    private void pickTeam(int slot, SportsFeed.Comp c) {
        Toast.makeText(this, R.string.scores_loading_teams, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            List<String[]> teams;
            try {
                teams = SportsFeed.teams(c);
            } catch (Exception e) {
                teams = null;
            }
            List<String[]> got = teams;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (got == null || got.isEmpty()) {     // no team list: follow the whole competition
                    save(slot, c.id);
                    return;
                }
                String[] names = new String[got.size() + 1];
                names[0] = getString(c.national ? R.string.scores_all_national : R.string.scores_whole_league);
                for (int i = 0; i < got.size(); i++) names[i + 1] = got.get(i)[1];
                new AlertDialog.Builder(this)
                        .setTitle(c.name)
                        .setItems(names, (d, which) -> {
                            if (which == 0) save(slot, c.id);
                            else {
                                String[] t = got.get(which - 1);
                                save(slot, c.id + "|" + t[0] + "|" + t[1].replace("|", " "));
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        }, "teams").start();
    }

    private void save(int slot, String value) {
        sp.edit().putString(SportsFeed.slotKey(slot), value).apply();
        fill();
    }

    private void editKey() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        input.setText(sp.getString("sports_apifootball_key", ""));
        input.setHint(R.string.scores_apikey_hint);
        new AlertDialog.Builder(this)
                .setTitle(R.string.scores_apikey_title)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    sp.edit().putString("sports_apifootball_key", input.getText().toString().trim()).apply();
                    fill();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void row(String text, Runnable action) {
        TextView line = new TextView(this, null, 0, R.style.SettingsRow);
        line.setText(text);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (8 * getResources().getDisplayMetrics().density);
        line.setLayoutParams(lp);
        line.setOnClickListener(v -> action.run());
        list.addView(line);
    }
}
