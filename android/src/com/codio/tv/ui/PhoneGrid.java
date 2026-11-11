package com.codio.tv.ui;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import java.util.ArrayList;
import java.util.List;

/**
 * A grid of posters (Search, Library, Movies/Series) that asks for more near the end.
 * Plain rows of posters in a scroll view, each poster handling its own tap like the Home rows,
 * so a tap can't get lost while the list refreshes (download progress, library updates).
 */
final class PhoneGrid extends ScrollView {
    final List<Store.Meta> items = new ArrayList<>();
    private final PhoneUi ui;
    private final int cols, cellW, cellH, gap;
    private final LinearLayout column;
    private final List<PhonePoster> posters = new ArrayList<>();
    Runnable onNearEnd;

    PhoneGrid(final PhoneUi ui) {
        super(ui.getContext());
        this.ui = ui;
        Context c = ui.getContext();
        int screen = c.getResources().getDisplayMetrics().widthPixels;
        cols = Math.max(3, Math.round(screen / (float) PhoneKit.dp(c, 120)));
        gap = PhoneKit.dp(c, 6);
        cellW = (screen - gap * 4 - gap * (cols - 1)) / cols;
        cellH = Math.round(cellW * 1.5f);
        setFillViewport(true);
        setClipToPadding(false);
        column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(gap * 2, gap, gap * 2, gap * 3);
        addView(column, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        nearEnd();
    }

    private void nearEnd() {
        if (onNearEnd == null || items.isEmpty()) return;
        // Within about three rows of the bottom.
        if (getScrollY() + getHeight() >= column.getHeight() - (cellH + gap) * 3) onNearEnd.run();
    }

    private PhonePoster poster(int i) {
        while (posters.size() <= i) {
            final PhonePoster p = new PhonePoster(ui.getContext(), cellW);
            p.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (p.meta != null) ui.openDetail(p.meta);
                }
            });
            posters.add(p);
        }
        return posters.get(i);
    }

    /** Shows the items again; posters already showing the same title only get their badge updated. */
    void changed() {
        int rows = (items.size() + cols - 1) / cols;
        while (column.getChildCount() > rows) column.removeViewAt(column.getChildCount() - 1);
        for (int r = 0; r < rows; r++) {
            LinearLayout row;
            if (r < column.getChildCount()) {
                row = (LinearLayout) column.getChildAt(r);
            } else {
                row = new LinearLayout(getContext());
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, cellH);
                if (r > 0) lp.topMargin = gap;
                column.addView(row, lp);
            }
            for (int k = 0; k < cols; k++) {
                int i = r * cols + k;
                PhonePoster p = poster(i);
                if (p.getParent() != row) {
                    if (p.getParent() != null) ((LinearLayout) p.getParent()).removeView(p);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, cellH, 1);
                    if (k > 0) lp.leftMargin = gap;
                    row.addView(p, Math.min(k, row.getChildCount()), lp);
                }
                if (i < items.size()) {
                    Store.Meta m = items.get(i);
                    if (p.meta == m || (p.meta != null && same(p.meta, m))) {
                        p.meta = m;
                        p.updateBadge(ui.downloads);
                    } else {
                        p.bind(m, ui.downloads);
                    }
                    p.setVisibility(VISIBLE);
                } else {
                    // Keeps the last row's cells the same width.
                    p.meta = null;
                    p.setVisibility(INVISIBLE);
                }
            }
        }
        post(new Runnable() {
            @Override
            public void run() {
                nearEnd();
            }
        });
    }

    private static boolean same(Store.Meta a, Store.Meta b) {
        return eq(a.id, b.id) && eq(a.name, b.name) && eq(a.poster, b.poster) && a.progress == b.progress;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
