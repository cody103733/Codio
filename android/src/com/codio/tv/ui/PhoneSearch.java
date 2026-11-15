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

import java.util.List;

/** Search every addon that can search; results as you type. */
final class PhoneSearch extends PhoneUi.Page {
    private EditText box;
    private PhoneGrid grid;
    private TextView hint;
    private final Search search = new Search();
    private LinearLayout recentBox;
    private final Handler h = new Handler(Looper.getMainLooper());
    private static String lastQuery = "";
    /** The Movies / Shows / Both chips under the search box. */
    private LinearLayout kinds;

    PhoneSearch(PhoneUi ui) {
        super(ui);
    }

    @Override
    View build() {
        LinearLayout col = new LinearLayout(ctx());
        col.setOrientation(LinearLayout.VERTICAL);
        box = new EditText(ctx());
        box.setHint("Movies, shows…");
        box.setHintTextColor(Theme.TEXT_FAINT);
        box.setTextColor(Color.WHITE);
        box.setTextSize(16);
        box.setSingleLine(true);
        box.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        box.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        box.setPadding(dp(14), dp(10), dp(14), dp(10));
        box.setBackground(PhoneKit.round(Theme.RAISED, dp(8)));
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
        for (int i = 0; i < Search.KIND_LABEL.length; i++) {
            final int k = i;
            TextView chip = PhoneKit.chip(ctx(), Search.KIND_LABEL[i], i == Search.kind);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (k == Search.kind) return;
                    Search.kind = k;
                    for (int j = 0; j < kinds.getChildCount(); j++) PhoneKit.styleChip((TextView) kinds.getChildAt(j), j == Search.kind);
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
        hint = PhoneKit.text(ctx(), "", 14, Theme.TEXT_DIM, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(24), dp(40), dp(24), 0);
        area.addView(hint, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        recentBox = new LinearLayout(ctx());
        recentBox.setOrientation(LinearLayout.VERTICAL);
        area.addView(recentBox, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
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
            showRecent();
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

    /** Leaving a search that found something (usually by opening a result) keeps it under Recent searches. */
    @Override
    void onHide() {
        keyboard(false);
        if (!grid.items.isEmpty()) Search.remember(ui.host.prefs(), lastQuery);
    }

    /** Nothing typed: the recent searches (tap one to run it again), or a hint when there are none. */
    private void showRecent() {
        recentBox.removeAllViews();
        List<String> recent = Search.recent(ui.host.prefs());
        hint.setText(recent.isEmpty() ? "Search your addons for movies and shows." : "");
        if (recent.isEmpty()) return;
        recentBox.addView(PhoneUi.sectionTitle(ctx(), "Recent searches"));
        for (final String q : recent) recentBox.addView(recentRow(q, Theme.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                box.setText(q);
                box.setSelection(q.length());
                h.removeCallbacks(run);
                run.run();
                keyboard(false);
            }
        }));
        recentBox.addView(recentRow("Clear recent searches", Theme.TEXT_DIM, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Search.clearRecent(ui.host.prefs());
                showRecent();
            }
        }));
    }

    private TextView recentRow(String text, int color, View.OnClickListener onClick) {
        TextView t = PhoneKit.text(ctx(), text, 15, color, false);
        t.setPadding(dp(16), dp(12), dp(16), dp(12));
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

    private void search(String q) {
        lastQuery = q;
        search.cancel();
        grid.items.clear();
        grid.changed();
        recentBox.removeAllViews();
        if (q.isEmpty()) {
            showRecent();
            return;
        }
        boolean asked = search.run(ui.store, q, new Search.Results() {
            @Override
            public void results(List<Store.Meta> items) {
                grid.items.clear();
                grid.items.addAll(items);
                grid.changed();
                hint.setText(items.isEmpty() ? (search.pending > 0 ? "Searching…" : "Nothing found.") : "");
            }
        });
        hint.setText(asked ? "Searching…" : !ui.online() ? "You're offline."
                : Search.kind == 0 ? "None of your addons can search." : "None of your addons can search " + Search.KIND_LABEL[Search.kind].toLowerCase() + ".");
    }
}
