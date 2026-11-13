package com.codio.tv.ui;

import android.content.Context;
import android.graphics.Color;
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
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Search every addon that can search; results as you type. */
final class PhoneSearch extends PhoneUi.Page {
    private EditText box;
    private PhoneGrid grid;
    private TextView hint;
    private int request = 0;
    private final Handler h = new Handler(Looper.getMainLooper());
    private static String lastQuery = "";
    /** Movies / Series / Both filter under the search box. Remembered while the app runs; starts on Both. */
    private static final String[] KIND_LABEL = {"Both", "Movies", "Series"};
    private static final String[] KIND_TYPE = {null, "movie", "series"};
    private static int kind = 0;
    private LinearLayout kinds;

    private static boolean wanted(String type) {
        String t = KIND_TYPE[kind];
        return t == null || t.equals(type);
    }

    PhoneSearch(PhoneUi ui) {
        super(ui);
    }

    @Override
    View build() {
        LinearLayout col = new LinearLayout(ctx());
        col.setOrientation(LinearLayout.VERTICAL);
        box = new EditText(ctx());
        box.setHint("Movies, shows…");
        box.setHintTextColor(0xFF8C8C8C);
        box.setTextColor(Color.WHITE);
        box.setTextSize(16);
        box.setSingleLine(true);
        box.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        box.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        box.setPadding(dp(14), dp(10), dp(14), dp(10));
        box.setBackground(PhoneKit.round(0xFF262626, dp(8)));
        LinearLayout top = new LinearLayout(ctx());
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(12), dp(12), dp(12), dp(8));
        top.addView(box, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-2, -2);
        al.setMargins(dp(12), 0, 0, 0);
        top.addView(ui.profileButton(), al);
        col.addView(top, new LinearLayout.LayoutParams(-1, -2));
        kinds = new LinearLayout(ctx());
        kinds.setPadding(dp(12), 0, dp(12), dp(8));
        for (int i = 0; i < KIND_LABEL.length; i++) {
            final int k = i;
            TextView chip = PhoneKit.chip(ctx(), KIND_LABEL[i], i == kind);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (k == kind) return;
                    kind = k;
                    for (int j = 0; j < kinds.getChildCount(); j++) PhoneKit.styleChip((TextView) kinds.getChildAt(j), j == kind);
                    h.removeCallbacks(run);
                    run.run();
                }
            });
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
            cl.setMargins(0, 0, dp(8), 0);
            kinds.addView(chip, cl);
        }
        col.addView(kinds, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout area = new FrameLayout(ctx());
        grid = new PhoneGrid(ui);
        area.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        hint = PhoneKit.text(ctx(), "", 14, PhoneKit.TEXT2, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(24), dp(40), dp(24), 0);
        area.addView(hint, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
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
                h.postDelayed(run, 450);
            }
        });
        box.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int id, KeyEvent e) {
                h.removeCallbacks(run);
                run.run();
                keyboard(false);
                return true;
            }
        });
        box.setText(lastQuery);
        if (lastQuery.isEmpty()) {
            hint.setText("Search your addons for movies and shows.");
            box.requestFocus();
            box.postDelayed(new Runnable() {
                @Override
                public void run() {
                    keyboard(true);
                }
            }, 200);
        }
        return col;
    }

    private void keyboard(boolean show) {
        InputMethodManager imm = (InputMethodManager) ctx().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return;
        if (show) imm.showSoftInput(box, InputMethodManager.SHOW_IMPLICIT);
        else imm.hideSoftInputFromWindow(box.getWindowToken(), 0);
    }

    @Override
    void onHide() {
        keyboard(false);
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

    private void search(String q) {
        lastQuery = q;
        final int id = ++request;
        grid.items.clear();
        grid.changed();
        if (q.isEmpty()) {
            hint.setText("Search your addons for movies and shows.");
            return;
        }
        final List<Store.Catalog> cats = new ArrayList<>();
        for (Store.Catalog cat : ui.store.searchCatalogs()) if (wanted(cat.type)) cats.add(cat);
        if (cats.isEmpty()) {
            hint.setText(ui.online() ? (kind == 0 ? "None of your addons can search." : "None of your addons can search " + KIND_LABEL[kind].toLowerCase() + ".") : "You're offline.");
            return;
        }
        hint.setText("Searching…");
        final int[] pending = {cats.size()};
        final List<List<Store.Meta>> parts = new ArrayList<>();
        for (int i = 0; i < cats.size(); i++) parts.add(null);
        for (int i = 0; i < cats.size(); i++) {
            final int slot = i;
            ui.store.loadCatalog(cats.get(i), null, 0, q, new Store.Callback<List<Store.Meta>>() {
                @Override
                public void done(List<Store.Meta> result, Exception error) {
                    if (id != request) return;
                    pending[0]--;
                    parts.set(slot, result != null ? result : new ArrayList<Store.Meta>());
                    grid.items.clear();
                    Set<String> seen = new HashSet<>();
                    for (List<Store.Meta> p : parts) {
                        if (p == null) continue;
                        for (Store.Meta m : p) if (wanted(m.type) && seen.add(m.type + m.id)) grid.items.add(m);
                    }
                    grid.changed();
                    hint.setText(grid.items.isEmpty() ? (pending[0] > 0 ? "Searching…" : "Nothing found.") : "");
                }
            });
        }
    }
}
