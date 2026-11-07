package com.edujalont.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.ActivityOptions;
import android.app.role.RoleManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.HashSet;
import java.util.Set;

/**
 * Chromecast with Google TV, two jobs:
 * <ul>
 * <li>Opens the system's own quick settings panel for a double press of Home (MainActivity). An app
 * cannot start that panel itself: on Google TV it belongs to the system's notification handler and is
 * guarded by a system permission. performGlobalAction asks SystemUI to open it, the same path the
 * system uses.</li>
 * <li>Keeps Google TV Home off screen. On Google TV (Chromecast, Android 14, measured) the Home button
 * and boot open Google TV Home's own home activity whatever app holds the HOME role or the preferred
 * home. So the Home key itself is taken here: a short press opens this launcher before Google TV Home
 * can show (no flash), a held press opens the quick settings panel as Google TV does. As a fallback (at
 * boot, or if the key ever bypasses the filter) whenever that home activity comes to the front this
 * launcher replaces it at once. Only that activity: the quick settings panel is another one
 * (`.dashboard.DashboardHandler`), and it and every other window stay untouched, so Google TV Home can
 * stay enabled and keep serving that panel.</li>
 * </ul>
 * Some moments announce no window change for Google TV Home: the service starting (boot, an update of
 * this app), the screen waking up, Back out of the Assistant's results. There the active window's
 * package is looked at (checkFront) and Google TV Home replaced if it is in front.
 * On a plain Android TV (no Google TV Home, see isGoogleTv) the service does neither: it drops its key
 * filter and window events as soon as it is bound, and Home is left to the system's default home app.
 * Window changes and key events (res/xml/quick_panel_service.xml); every key but Home is handed straight
 * back untouched (Back is only looked at); of window content only the active window's package name is
 * read. Opt-in: only bound once enabled in Accessibility settings or with `settings put secure
 * enabled_accessibility_services` over adb.
 */
public class QuickPanelService extends AccessibilityService {

    static final String GOOGLE_TV_HOME = "com.google.android.apps.tv.launcherx";
    /** One appearance of Google TV Home can report several window changes: one replacement for them. */
    private static final long DUPLICATE_MS = 250;
    /** Re-read Google TV Home's activities at most this often when an unknown one shows (app update). */
    private static final long REFRESH_MS = 30_000;

    private static volatile QuickPanelService instance;
    /** Google TV Home's HOME activities (aliases and their targets), read from the package manager. */
    private final Set<String> stockHomeActivities = new HashSet<>();
    private long lastRefresh, lastReplace;
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Home is down and its long press already opened the panel: its key-up does nothing. */
    private boolean homeHeld;
    /** The key filter sees no key repeats, so a held Home is timed here. */
    private final Runnable homeLongPress = () -> {
        homeHeld = true;
        openPanel();
    };

    /** Home pressed twice within this window: the quick settings panel instead of a second Home. */
    static final long DOUBLE_HOME_MS = 500;
    /** Set on the Home intents sent from here, so MainActivity does not count the double press again. */
    static final String EXTRA_FROM_KEY = "com.edujalont.tvhome.FROM_HOME_KEY";
    private long lastHomeUp;
    private boolean googleTv;

    /** True when the service is enabled on Google TV and SystemUI accepted the request. */
    static boolean openPanel() {
        QuickPanelService s = instance;
        if (s == null || !s.googleTv) return false;
        return s.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                || s.performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
    }

    /**
     * Google TV (Chromecast with Google TV and Google TV sets): Google TV Home is installed, enabled or
     * not. On a plain Android TV (an Android TV 9 set, say) the system honours the default home app and
     * has no Google TV panel, so both jobs of this service are off there.
     */
    static boolean isGoogleTv(Context ctx) {
        try {
            ctx.getPackageManager().getApplicationInfo(GOOGLE_TV_HOME, PackageManager.MATCH_DISABLED_COMPONENTS);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        googleTv = isGoogleTv(this);
        if (!googleTv) {
            // Plain Android TV: take no keys and no window changes; Home stays the system's.
            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                info.eventTypes = 0;
                info.flags &= ~AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
                setServiceInfo(info);
            }
            return;
        }
        refreshHomeActivities();
        IntentFilter wake = new IntentFilter(Intent.ACTION_SCREEN_ON);
        wake.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenOn, wake);
        screenOnRegistered = true;
        // Boot, or this app was just updated: Google TV Home may already be in front, unannounced.
        handler.postDelayed(checkFront, FRONT_CHECK_MS);
        // At boot one look was not enough: Google TV Home brings its home activity forward again, and
        // later its boot dispatcher, after that look and sometimes without a window change this service
        // is told about. So for a while after the service starts it is looked at every second and
        // replaced whenever it is in front.
        watchUntil = SystemClock.elapsedRealtime() + BOOT_WATCH_MS;
        handler.postDelayed(bootWatch, BOOT_WATCH_STEP_MS);
    }

    /** How long after the service starts (boot, an update) Google TV Home is watched for, and how often. */
    private static final long BOOT_WATCH_MS = 150_000, BOOT_WATCH_STEP_MS = 1_000;
    private long watchUntil;
    private final Runnable bootWatch = new Runnable() {
        @Override
        public void run() {
            if (instance != QuickPanelService.this) return;
            replaceAtStartup();
            if (SystemClock.elapsedRealtime() < watchUntil) handler.postDelayed(this, BOOT_WATCH_STEP_MS);
        }
    };

    /**
     * The start-up version of replaceIfInFront: Google TV Home in front is replaced whatever window of
     * it was announced last (its start-up screens are not its home activity), except the quick settings
     * panel and the Assistant's results, which the viewer asked for.
     */
    private void replaceAtStartup() {
        if (!googleTv || showingContent) return;
        if (lastStockClass != null && (lastStockClass.contains(".dashboard.") || lastStockClass.contains(".gateway."))) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        CharSequence pkg = root.getPackageName();
        root.recycle();
        if (pkg == null || !GOOGLE_TV_HOME.contentEquals(pkg)) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastReplace < DUPLICATE_MS) return;
        lastReplace = now;
        goHome();
    }

    /** After these, Google TV Home may be in front with no window change to say so. */
    private static final long FRONT_CHECK_MS = 700;
    private boolean screenOnRegistered;
    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handler.removeCallbacks(checkFront);
            handler.postDelayed(checkFront, FRONT_CHECK_MS);
        }
    };
    private final Runnable checkFront = () -> replaceIfInFront(false);
    /** Back out of the Assistant's results: they end, and Google TV Home under them goes too. */
    private final Runnable backFromContent = () -> replaceIfInFront(true);

    /**
     * Replaces Google TV Home if it holds the active window and its last window was its home activity
     * (or none was seen yet, as at boot): the quick settings panel, a dialog or a setup screen of it stay.
     */
    private void replaceIfInFront(boolean endContent) {
        if (!googleTv) return;
        if (showingContent && !endContent) return;
        if (lastStockClass != null && !isStockHome(lastStockClass)) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        CharSequence pkg = root.getPackageName();
        root.recycle();
        if (pkg == null || !GOOGLE_TV_HOME.contentEquals(pkg)) return;
        showingContent = false;
        lastContentAt = 0;
        lastReplace = SystemClock.elapsedRealtime();
        goHome();
    }

    /**
     * Google TV Home's home activity, or its boot dispatcher (`.coreservices.bootmode.DispatchActivity`):
     * on the Chromecast (Android 14, logged) that one comes up by itself about a minute after boot, on top
     * of this launcher, and stayed there (all night, in the log) because only the home activity was
     * replaced; the viewer had to press Home several times to get past it.
     */
    private boolean isStockHome(String cls) {
        if (cls.contains(".bootmode.")) return true;
        if (stockHomeActivities.contains(cls)) return true;
        if (SystemClock.elapsedRealtime() - lastRefresh < REFRESH_MS) return false;
        refreshHomeActivities();               // after an update its home activity may have moved
        return stockHomeActivities.contains(cls);
    }

    private void refreshHomeActivities() {
        lastRefresh = SystemClock.elapsedRealtime();
        stockHomeActivities.clear();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo ri : getPackageManager().queryIntentActivities(home, 0)) {
            ActivityInfo a = ri.activityInfo;
            if (a == null || !GOOGLE_TV_HOME.equals(a.packageName)) continue;
            stockHomeActivities.add(a.name);
            if (a.targetActivity != null) stockHomeActivities.add(a.targetActivity);
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        handler.removeCallbacks(homeLongPress);
        handler.removeCallbacks(checkFront);
        handler.removeCallbacks(backFromContent);
        handler.removeCallbacks(bootWatch);
        if (screenOnRegistered) {
            unregisterReceiver(screenOn);
            screenOnRegistered = false;
        }
        return super.onUnbind(intent);
    }

    /**
     * Home only; every other key goes on untouched (false) without any work here. The double press is
     * told apart here, from the key itself: counted in MainActivity it missed the first press whenever
     * the launcher was not running yet (Home from inside an app starts it, no new intent).
     */
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (!googleTv) return false;
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            // Only looked at, always handed on: Back out of the results leaves Google TV Home behind,
            // inside the same activity, with no window change to catch it by.
            if (showingContent && event.getAction() == KeyEvent.ACTION_UP) {
                handler.removeCallbacks(backFromContent);
                handler.postDelayed(backFromContent, FRONT_CHECK_MS);
            }
            return false;
        }
        if (event.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                homeHeld = false;
                handler.removeCallbacks(homeLongPress);
                handler.postDelayed(homeLongPress, ViewConfiguration.getLongPressTimeout());
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            handler.removeCallbacks(homeLongPress);
            if (!homeHeld && !event.isCanceled()) {
                long now = SystemClock.elapsedRealtime();
                showingContent = false;
                lastContentAt = 0;
                if (now - lastHomeUp <= DOUBLE_HOME_MS) {
                    lastHomeUp = 0;
                    if (!openPanel()) goHome();
                } else {
                    lastHomeUp = now;
                    goHome();
                }
            }
            homeHeld = false;
        }
        return true;
    }

    /**
     * The Assistant's search results, and every tv.google.com link, open inside Google TV Home's home
     * activity (through its `.gateway.LauncherDeepLink`). Replacing that made the results vanish the
     * moment they showed. So after the Assistant or such a link, Google TV Home is left alone while the
     * viewer is in them, and only that long: back in this launcher (contentDone), on to another app,
     * Back out of them or Home, and it is replaced again.
     */
    private static final long CONTENT_MS = 15_000;
    static final String ASSISTANT = "com.google.android.katniss";
    private long lastContentAt;
    private boolean showingContent;
    /** Google TV Home's last window (class name), or null while none was seen since the service started. */
    private String lastStockClass;

    /**
     * This launcher is in front again: Google TV Home goes back to being replaced. The Assistant's mark
     * stays: its voice plate closes back onto this launcher just before the results open.
     */
    static void contentDone() {
        QuickPanelService s = instance;
        if (s != null) s.showingContent = false;
    }

    /** Windows that open over the results without leaving them: the system's own and the keyboard's. */
    private boolean overlay(String pkg) {
        if ("android".equals(pkg) || "com.android.systemui".equals(pkg)) return true;
        String ime = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        return ime != null && ime.startsWith(pkg + "/");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!googleTv) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        String pkg = String.valueOf(event.getPackageName());
        CharSequence cls = event.getClassName();
        long now = SystemClock.elapsedRealtime();
        if (ASSISTANT.equals(pkg)) {
            lastContentAt = now;
            return;
        }
        if (!GOOGLE_TV_HOME.equals(pkg)) {
            // Another app in front: the viewer left the results, and coming back to Google TV Home from
            // it is a plain Google TV Home again (this launcher keeps the Assistant's mark: contentDone).
            // Google TV Home's last window (the panel, say) is behind us too: looked at afresh next time.
            if (overlay(pkg)) return;
            lastStockClass = null;
            showingContent = false;
            if (!getPackageName().equals(pkg)) lastContentAt = 0;
            return;
        }
        if (cls == null) return;
        lastStockClass = cls.toString();
        if (lastStockClass.contains(".gateway.")) {
            lastContentAt = now;
            return;
        }
        // Unknown window of Google TV Home: usually the panel or a dialog; leave it alone.
        if (!isStockHome(lastStockClass)) return;
        if (showingContent) return;
        if (lastContentAt != 0 && now - lastContentAt <= CONTENT_MS) {
            showingContent = true;              // results or a linked page: leave them on screen
            return;
        }
        if (now - lastReplace < DUPLICATE_MS) return;
        lastReplace = now;
        goHome();
    }

    /**
     * This launcher, as a Home intent (MainActivity treats it as a Home press, without counting it towards
     * the double press, which is counted here). No transition: when it replaces Google TV Home, the less
     * of that shows the better.
     */
    private void goHome() {
        try {
            startActivity(new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setClass(this, MainActivity.class)
                    .putExtra(EXTRA_FROM_KEY, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION),
                    ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle());
        } catch (Exception ignored) {
        }
    }

    /**
     * Whether this launcher holds the HOME role (Android 10+), else whether Home resolves to it. Used
     * only to tell the viewer the setup was lost: on Google TV holding the role does not make the Home
     * button open this launcher (the replacement above does), but losing it means the setup was undone.
     */
    static boolean isDefaultHome(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager rm = ctx.getSystemService(RoleManager.class);
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) return rm.isRoleHeld(RoleManager.ROLE_HOME);
        }
        ResolveInfo ri = ctx.getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && ri.activityInfo != null && ctx.getPackageName().equals(ri.activityInfo.packageName);
    }

    @Override
    public void onInterrupt() {
    }
}
