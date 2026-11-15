package com.codio.tv.ui;

import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Searching every addon that can search, shared by the TV and phone Search screens. */
final class Search {
    /** The Movies / Shows / Both filter; remembered while the app runs, starts on Both. */
    static final String[] KIND_LABEL = {"Both", "Movies", "Shows"};
    private static final String[] KIND_TYPE = {null, "movie", "series"};
    static int kind = 0;

    interface Results {
        /** The results so far, in catalog order without repeats. */
        void results(List<Store.Meta> items);
    }

    private int request = 0;
    /** Catalogs that haven't answered the running search yet. */
    int pending = 0;

    static boolean wanted(String type) {
        String t = KIND_TYPE[kind];
        return t == null || t.equals(type);
    }

    /** Forget the search that's running (its answers are dropped). */
    void cancel() {
        request++;
        pending = 0;
    }

    /** Asks every catalog that can search; false when no addon can (for the chosen kind). */
    boolean run(Store store, String q, final Results out) {
        final int id = ++request;
        final List<Store.Catalog> cats = new ArrayList<>();
        for (Store.Catalog cat : store.searchCatalogs()) if (wanted(cat.type)) cats.add(cat);
        pending = cats.size();
        final List<List<Store.Meta>> parts = new ArrayList<>();
        for (int i = 0; i < cats.size(); i++) parts.add(null);
        for (int i = 0; i < cats.size(); i++) {
            final int slot = i;
            store.loadCatalog(cats.get(i), null, 0, q, new Store.Callback<List<Store.Meta>>() {
                @Override
                public void done(List<Store.Meta> result, Exception error) {
                    if (id != request) return;
                    pending--;
                    parts.set(slot, result != null ? result : new ArrayList<Store.Meta>());
                    List<Store.Meta> all = new ArrayList<>();
                    Set<String> seen = new HashSet<>();
                    for (List<Store.Meta> p : parts) {
                        if (p == null) continue;
                        for (Store.Meta m : p) if (wanted(m.type) && seen.add(m.type + m.id)) all.add(m);
                    }
                    out.results(all);
                }
            });
        }
        return !cats.isEmpty();
    }

    // ------------------------------------------------------------------ recent searches (kept on this device)

    private static final String RECENT = "recent_searches";
    private static final int MAX_RECENT = 8;

    static List<String> recent(SharedPreferences prefs) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString(RECENT, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Puts q at the front of the recent searches (called when a result is opened). */
    static void remember(SharedPreferences prefs, String q) {
        q = q.trim();
        if (q.length() < 2) return;
        JSONArray a = new JSONArray().put(q);
        for (String old : recent(prefs)) if (!old.equalsIgnoreCase(q) && a.length() < MAX_RECENT) a.put(old);
        prefs.edit().putString(RECENT, a.toString()).apply();
    }

    static void clearRecent(SharedPreferences prefs) {
        prefs.edit().remove(RECENT).apply();
    }
}
