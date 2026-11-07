package com.edujalont.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The scores panel (the trophy on the top bar): one block per followed league, team or F1, live games
 * first. Refreshed every LIVE_MS while something is live and every IDLE_MS otherwise, only while this
 * screen is in front.
 */
public final class ScoresActivity extends Activity {

    private static final long LIVE_MS = 45_000, IDLE_MS = 5 * 60_000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout list;
    private TextView updated;
    private boolean resumed;
    private final Runnable tick = this::load;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_scores);
        list = findViewById(R.id.list);
        updated = findViewById(R.id.updated);
        findViewById(R.id.edit).setOnClickListener(v ->
                startActivity(new Intent(this, SportsSettingsActivity.class)));
        List<SportsFeed.Group> cached = SportsFeed.cached();
        if (!cached.isEmpty()) show(cached, false);
        else updated.setText(R.string.scores_loading);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        load();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        handler.removeCallbacks(tick);
    }

    private void load() {
        handler.removeCallbacks(tick);
        if (SportsFeed.followed(SportsFeed.prefs(this)) == 0 || !SportsFeed.enabled(this)) {
            list.removeAllViews();
            updated.setText(R.string.scores_empty_setup);
            findViewById(R.id.edit).requestFocus();
            return;
        }
        SportsFeed.fetch(this, groups -> {
            if (isDestroyed()) return;
            show(groups, true);
        });
        // A fetch already in flight answers nobody here: look again shortly.
        handler.postDelayed(tick, LIVE_MS);
    }

    private void show(List<SportsFeed.Group> groups, boolean fresh) {
        View focused = getCurrentFocus();
        int focusIndex = focused == null ? -1 : list.indexOfChild(focused);
        list.removeAllViews();
        float dp = getResources().getDisplayMetrics().density;
        for (SportsFeed.Group g : groups) {
            // The block's header: the competition's logo, then its name.
            LinearLayout headRow = new LinearLayout(this);
            headRow.setOrientation(LinearLayout.HORIZONTAL);
            headRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            hp.topMargin = (int) (22 * dp);
            hp.bottomMargin = (int) (4 * dp);
            headRow.setLayoutParams(hp);
            ImageView logo = new ImageView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (28 * dp), (int) (28 * dp));
            lp.setMarginEnd((int) (12 * dp));
            logo.setLayoutParams(lp);
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            headRow.addView(logo);
            LogoCache.load(logo, g.logo);
            TextView head = new TextView(this);
            head.setText(g.title.toUpperCase(Locale.getDefault()));
            head.setTextColor(0xFF8AB4F8);
            head.setTextSize(14);
            head.setLetterSpacing(0.12f);
            head.setTypeface(Typeface.DEFAULT_BOLD);
            headRow.addView(head);
            list.addView(headRow);
            if (g.rows.isEmpty()) {
                list.addView(row(dp, "", getString(g.failed ? R.string.scores_error : R.string.scores_no_games),
                        SportsFeed.PRE, null, null));
                continue;
            }
            for (SportsFeed.Row r : g.rows) {
                String status = r.status;
                if (status == null || status.isEmpty()) {
                    status = r.state == SportsFeed.POST ? getString(R.string.scores_final)
                            : r.state == SportsFeed.LIVE ? getString(R.string.scores_live)
                            : SportsFeed.startLabel(r.start);
                }
                list.addView(row(dp, status, r.text, r.state, r.leadLogo, r.trailLogo));
            }
        }
        if (fresh) {
            updated.setText(getString(R.string.scores_updated,
                    new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date())));
        }
        if (focusIndex >= 0 && focusIndex < list.getChildCount() && list.getChildAt(focusIndex).isFocusable()) {
            list.getChildAt(focusIndex).requestFocus();
        } else if (getCurrentFocus() == null || getCurrentFocus().getParent() == list) {
            for (int i = 0; i < list.getChildCount(); i++) {
                if (list.getChildAt(i).isFocusable()) {
                    list.getChildAt(i).requestFocus();
                    break;
                }
            }
        }
        if (resumed && fresh) {
            handler.removeCallbacks(tick);
            handler.postDelayed(tick, SportsFeed.anyLive(groups) ? LIVE_MS : IDLE_MS);
        }
    }

    /** A focusable line: the status on the left (red while live), the game on the right. */
    private View row(float dp, String status, String text, int state, String lead, String trail) {
        View line = LayoutInflater.from(this).inflate(R.layout.item_score_row, list, false);
        TextView st = line.findViewById(R.id.status);
        st.setText(status);
        if (state == SportsFeed.LIVE) {
            st.setTypeface(Typeface.DEFAULT_BOLD);
            st.setTextColor(0xFFFF5252);
        }
        ((TextView) line.findViewById(R.id.game)).setText(text);
        LogoCache.load(line.findViewById(R.id.leadLogo), lead);
        LogoCache.load(line.findViewById(R.id.trailLogo), trail);
        return line;
    }
}
