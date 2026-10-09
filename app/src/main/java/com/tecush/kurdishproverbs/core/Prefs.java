package com.tecush.kurdishproverbs.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * All user settings in one place (stored in kp_settings.xml).
 * Registration data is NOT here — see LicenseManager (kp_license.xml).
 */
public final class Prefs {

    private static Prefs instance;

    public static synchronized Prefs get(Context ctx) {
        if (instance == null) instance = new Prefs(ctx.getApplicationContext());
        return instance;
    }

    private final SharedPreferences sp;

    private Prefs(Context ctx) {
        sp = ctx.getSharedPreferences("kp_settings", Context.MODE_PRIVATE);
    }

    // ── Appearance ───────────────────────────────────────────────────────
    public int getThemeMode()            { return sp.getInt("theme_mode", 0); }
    public void setThemeMode(int m)      { sp.edit().putInt("theme_mode", m).apply(); }

    /** Text size multiplier, 0.8 … 1.6 */
    public float getTextScale()          { return sp.getFloat("text_scale", 1.0f); }
    public void setTextScale(float s)    { sp.edit().putFloat("text_scale", s).apply(); }

    public boolean showDialectLabels()   { return sp.getBoolean("show_dialect", true); }
    public void setShowDialectLabels(boolean b) { sp.edit().putBoolean("show_dialect", b).apply(); }

    public boolean colorByDialect()      { return sp.getBoolean("color_dialect", false); }
    public void setColorByDialect(boolean b) { sp.edit().putBoolean("color_dialect", b).apply(); }

    public boolean showDailyProverb()    { return sp.getBoolean("show_daily", true); }
    public void setShowDailyProverb(boolean b) { sp.edit().putBoolean("show_daily", b).apply(); }

    // ── Main list ────────────────────────────────────────────────────────
    /** 0 = alphabetical, 1 = largest first */
    public int getSortMode()             { return sp.getInt("sort_mode", 0); }
    public void setSortMode(int m)       { sp.edit().putInt("sort_mode", m).apply(); }

    /** 0 = all dialects */
    public int getDialectFilter()        { return sp.getInt("dialect_filter", 0); }
    public void setDialectFilter(int id) { sp.edit().putInt("dialect_filter", id).apply(); }

    // ── Updates ──────────────────────────────────────────────────────────
    public boolean autoUpdateCheck()     { return sp.getBoolean("auto_update", true); }
    public void setAutoUpdateCheck(boolean b) { sp.edit().putBoolean("auto_update", b).apply(); }

    public long getLastUpdateCheck()     { return sp.getLong("last_update_check", 0); }
    public void setLastUpdateCheck(long t) { sp.edit().putLong("last_update_check", t).apply(); }

    /** App version that did the last update check (a new app version checks immediately). */
    public int getLastCheckedVersionCode()      { return sp.getInt("last_checked_vc", 0); }
    public void setLastCheckedVersionCode(int v) { sp.edit().putInt("last_checked_vc", v).apply(); }

    public long getLastCheckIn()         { return sp.getLong("last_checkin", 0); }
    public void setLastCheckIn(long t)   { sp.edit().putLong("last_checkin", t).apply(); }

    // ── Favourites (proverb ids, newest first) ───────────────────────────
    public List<Integer> getFavorites() {
        List<Integer> out = new ArrayList<>();
        String raw = sp.getString("favorites", "");
        if (raw == null || raw.isEmpty()) return out;
        for (String part : raw.split(",")) {
            try { out.add(Integer.parseInt(part.trim())); } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    public boolean isFavorite(int proverbId) { return getFavorites().contains(proverbId); }

    /** @return true if the proverb is a favourite after the call */
    public boolean toggleFavorite(int proverbId) {
        LinkedHashSet<Integer> set = new LinkedHashSet<>();
        boolean nowFav;
        List<Integer> cur = getFavorites();
        if (cur.contains(proverbId)) {
            cur.remove(Integer.valueOf(proverbId));
            set.addAll(cur);
            nowFav = false;
        } else {
            set.add(proverbId);
            set.addAll(cur);
            nowFav = true;
        }
        StringBuilder sb = new StringBuilder();
        for (Integer i : set) { if (sb.length() > 0) sb.append(','); sb.append(i); }
        sp.edit().putString("favorites", sb.toString()).apply();
        return nowFav;
    }
}
