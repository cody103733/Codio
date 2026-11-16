package com.codio.tv.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Search, laid out like the iPhone's: the big title, the search field, Both / Movies / Shows, then Recent searches
 * and popular titles while nothing is typed, or the results.
 */
final class PhoneSearch extends PhoneUi.Page {
    private EditText box;
    private PhoneGrid grid;
    private TextView hint;
    private ProgressBar spin;
    private final Search search = new Search();
    private final Handler h = new Handler(Looper.getMainLooper());
    private static String lastQuery = "";
    private PhoneKit.Segmented kinds;
    private final List<Store.Meta> browse = new ArrayList<>();
    private int browseFor = -1;

    PhoneSearch(PhoneUi ui) {
        super(ui);
    }

    @Override
    View build() {
        LinearLayout col = new LinearLayout(ctx());
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(ui.largeTitle("Search", false));

        LinearLayout field = new LinearLayout(ctx());
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setPadding(dp(8), 0, dp(8), 0);
        field.setBackground(PhoneKit.round(PhoneKit.GROUPED, dp(10)));
        field.addView(new Magnifier(ctx()), new LinearLayout.LayoutParams(dp(22), dp(22)));
        box = new EditText(ctx());
        box.setHint("Movies and shows");
        box.setHintTextColor(PhoneKit.GRAY);
        box.setTextColor(Color.WHITE);
        box.setTextSize(17);
        box.setTypeface(PhoneKit.face(PhoneKit.REGULAR));
        box.setSingleLine(true);
        box.setBackground(null);
        box.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        box.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        box.setPadding(dp(6), 0, dp(4), 0);
        field.addView(box, new LinearLayout.LayoutParams(0, -1, 1));
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-1, dp(36));
        fl.setMargins(dp(16), 0, dp(16), dp(10));
        col.addView(field, fl);

        kinds = new PhoneKit.Segmented(ctx(), Search.KIND_LABEL, Search.kind);
        kinds.setListener(new PhoneKit.Segmented.Listener() {
            @Override
            public void picked(int index) {
                Search.kind = index;
                h.removeCallbacks(run);
                run.run();
            }
        });
        LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(-1, -2);
        kl.setMargins(dp(16), dp(4), dp(16), dp(14));
        col.addView(kinds, kl);

        FrameLayout area = new FrameLayout(ctx());
        grid = new PhoneGrid(ui);
        area.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        hint = PhoneKit.text(ctx(), "", 17, Theme.TEXT_DIM, PhoneKit.REGULAR);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(24), dp(40), dp(24), 0);
        area.addView(hint, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        spin = new ProgressBar(ctx());
        spin.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Theme.RED));
        FrameLayout.LayoutParams spl = new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        spl.topMargin = dp(40);
        area.addView(spin, spl);
        spin.setVisibility(View.GONE);
        col.addView(area, new LinearLayout.LayoutParams(-1, 0, 1));
        box.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                h.removeCallbacks(run);
                h.postDelayed(run, 400);
            }
        });
        box.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int id, KeyEvent e) {
                h.removeCallbacks(run);
                run.run();
                keyboard(false);
                if (!grid.items.isEmpty()) Search.remember(ui.host.prefs(), lastQuery);
                return true;
            }
        });
        box.setText(lastQuery);
        if (lastQuery.isEmpty()) showEmpty();
        return col;
    }

    private void keyboard(boolean show) {
        InputMethodManager imm = (InputMethodManager) ctx().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return;
        if (show) imm.showSoftInput(box, InputMethodManager.SHOW_IMPLICIT);
        else imm.hideSoftInputFromWindow(box.getWindowToken(), 0);
    }

    /** Leaving a search that found something (usually by opening a result) keeps it under Recent searches. */
    @Override
    void onHide() {
        keyboard(false);
        if (!lastQuery.isEmpty() && !grid.items.isEmpty()) Search.remember(ui.host.prefs(), lastQuery);
    }

    /** Nothing typed: Recent searches, then popular titles of the chosen kind. */
    private void showEmpty() {
        hint.setText("");
        spin.setVisibility(View.GONE);
        List<String> recent = Search.recent(ui.host.prefs());
        if (recent.isEmpty()) {
            grid.setHeader(null);
        } else {
            LinearLayout box2 = new LinearLayout(ctx());
            box2.setOrientation(LinearLayout.VERTICAL);
            box2.setPadding(0, 0, 0, dp(6));
            TextView head = PhoneKit.text(ctx(), "Recent searches", 17, Color.WHITE, PhoneKit.SEMIBOLD);
            head.setPadding(dp(16), 0, dp(16), dp(6));
            box2.addView(head);
            for (final String q : recent) box2.addView(recentRow(q, Color.WHITE, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    box.setText(q);
                    box.setSelection(q.length());
                    h.removeCallbacks(run);
                    run.run();
                    keyboard(false);
                }
            }));
            box2.addView(recentRow("Clear recent searches", Theme.TEXT_DIM, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Search.clearRecent(ui.host.prefs());
                    showEmpty();
                }
            }));
            grid.setHeader(box2);
        }
        grid.items.clear();
        grid.items.addAll(browse);
        grid.changed();
        loadBrowse();
    }

    /** The first catalog of each wanted kind, movies and shows taking turns. */
    private void loadBrowse() {
        final int k = Search.kind;
        if (browseFor == k * 100000 + ui.store.generation() && !browse.isEmpty()) return;
        browseFor = k * 100000 + ui.store.generation();
        String[] types = k == 0 ? new String[]{"movie", "series"} : new String[]{k == 1 ? "movie" : "series"};
        final List<List<Store.Meta>> parts = new ArrayList<>();
        browse.clear();
        for (String t : types) {
            List<Store.Catalog> cats = ui.store.discoverCatalogs(t);
            if (cats.isEmpty()) continue;
            final List<Store.Meta> part = new ArrayList<>();
            parts.add(part);
            ui.store.loadCatalog(cats.get(0), null, 0, null, new Store.Callback<List<Store.Meta>>() {
                @Override
                public void done(List<Store.Meta> result, Exception error) {
                    if (Search.kind != k || result == null) return;
                    part.addAll(result);
                    browse.clear();
                    int most = 0;
                    for (List<Store.Meta> p : parts) most = Math.max(most, p.size());
                    for (int i = 0; i < most; i++) for (List<Store.Meta> p : parts) if (i < p.size()) browse.add(p.get(i));
                    if (lastQuery.isEmpty()) {
                        grid.items.clear();
                        grid.items.addAll(browse);
                        grid.changed();
                    }
                }
            });
        }
    }

    private TextView recentRow(String text, int color, View.OnClickListener onClick) {
        TextView t = PhoneKit.text(ctx(), text, 17, color, PhoneKit.REGULAR);
        t.setSingleLine(true);
        t.setPadding(dp(16), dp(10), dp(16), dp(10));
        t.setBackground(PhoneKit.pressable(null, 0));
        t.setOnClickListener(onClick);
        return t;
    }

    @Override
    void refresh() {
        if (grid != null) grid.changed();
    }

    private final Runnable run = new Runnable() {
        @Override
        public void run() {
            search(box.getText().toString().trim());
        }
    };

    private void search(final String q) {
        lastQuery = q;
        search.cancel();
        grid.items.clear();
        grid.changed();
        if (q.isEmpty()) {
            showEmpty();
            return;
        }
        grid.setHeader(null);
        boolean asked = search.run(ui.store, q, new Search.Results() {
            @Override
            public void results(List<Store.Meta> items) {
                grid.items.clear();
                grid.items.addAll(items);
                grid.changed();
                boolean waiting = items.isEmpty() && search.pending > 0;
                spin.setVisibility(waiting ? View.VISIBLE : View.GONE);
                hint.setText(items.isEmpty() && !waiting ? "Nothing found for “" + q + "”" : "");
            }
        });
        spin.setVisibility(asked ? View.VISIBLE : View.GONE);
        hint.setText(asked ? "" : !ui.online() ? "You're offline."
                : Search.kind == 0 ? "None of your addons can search." : "None of your addons can search " + Search.KIND_LABEL[Search.kind].toLowerCase() + ".");
    }

    /** The grey magnifier at the start of the search field. */
    private static final class Magnifier extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Magnifier(Context c) {
            super(c);
            p.setColor(PhoneKit.GRAY);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        protected void onDraw(Canvas c) {
            float d = getResources().getDisplayMetrics().density, cx = getWidth() / 2f, cy = getHeight() / 2f, s = 7 * d;
            p.setStrokeWidth(1.8f * d);
            c.drawCircle(cx - s * 0.18f, cy - s * 0.18f, s * 0.68f, p);
            p.setStrokeWidth(2.2f * d);
            c.drawLine(cx + s * 0.32f, cy + s * 0.32f, cx + s * 0.9f, cy + s * 0.9f, p);
        }
    }
}
