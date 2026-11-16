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
    private final int cols, cellW, cellH, gap, rowGap, edge, inset;
    private final LinearLayout column;
    private final List<PhonePoster> posters = new ArrayList<>();
    Runnable onNearEnd;

    PhoneGrid(final PhoneUi ui) {
        super(ui.getContext());
        this.ui = ui;
        Context c = ui.getContext();
        // The iPhone's grid: cells at least 104 wide, 8 apart, 12 from the edges; posters 108 wide in the middle of them.
        int screen = c.getResources().getDisplayMetrics().widthPixels;
        edge = PhoneKit.dp(c, 12);
        gap = PhoneKit.dp(c, 8);
        rowGap = PhoneKit.dp(c, 12);
        cols = Math.max(1, (screen - edge * 2 + gap) / (PhoneKit.dp(c, 104) + gap));
        int cell = (screen - edge * 2 - gap * (cols - 1)) / cols;
        cellW = Math.min(cell, PhoneKit.dp(c, 108));
        inset = (cell - cellW) / 2;
        cellH = Math.round(cellW * 1.5f);
        setFillViewport(true);
        setClipToPadding(false);
        column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(edge, 0, edge, rowGap * 2);
        addView(column, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    private View header;

    /** Something shown above the posters, scrolling with them (Recent searches). */
    void setHeader(View v) {
        if (header != null) column.removeView(header);
        header = v;
        if (v != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            // Edge to edge, outside the grid's own margins.
            lp.leftMargin = -edge;
            lp.rightMargin = -edge;
            column.addView(v, 0, lp);
        }
    }

    private int first() {
        return header != null ? 1 : 0;
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        nearEnd();
    }

    private void nearEnd() {
        if (onNearEnd == null || items.isEmpty()) return;
        // Within about three rows of the bottom.
        if (getScrollY() + getHeight() >= column.getHeight() - (cellH + rowGap) * 3) onNearEnd.run();
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
        int rows = (items.size() + cols - 1) / cols, f = first();
        while (column.getChildCount() > rows + f) column.removeViewAt(column.getChildCount() - 1);
        for (int r = 0; r < rows; r++) {
            LinearLayout row;
            if (r + f < column.getChildCount()) {
                row = (LinearLayout) column.getChildAt(r + f);
            } else {
                row = new LinearLayout(getContext());
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, cellH);
                lp.topMargin = r > 0 || f > 0 ? rowGap : 0;
                column.addView(row, lp);
            }
            for (int k = 0; k < cols; k++) {
                int i = r * cols + k;
                PhonePoster p = poster(i);
                if (p.getParent() != row) {
                    if (p.getParent() != null) ((LinearLayout) p.getParent()).removeView(p);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cellW, cellH);
                    lp.leftMargin = k > 0 ? inset * 2 + gap : inset;
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
