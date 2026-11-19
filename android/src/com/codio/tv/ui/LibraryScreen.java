package com.codio.tv.ui;

import android.graphics.Canvas;
import android.view.KeyEvent;

/** Library: what the viewer saved or watched, by type. */
final class LibraryScreen extends Screen {
    private static final int NAV = 0, TYPES = 1, GRID = 2;
    private static final String[] TYPE_IDS = {null, "movie", "series"};
    private static final String[] TYPE_LABELS = {"All", "Movies", "Shows", "Calendar"};
    /** The last chip opens the Calendar instead of filtering. */
    private static final int CALENDAR = 3;
    private static final float CHIPS_Y = 160, GRID_Y = 260;

    private final NavBar nav;
    private final Grid grid = new Grid(7);
    private int zone = GRID, type = 0, typeFocus = 0;
    private final Smooth scroll = new Smooth(0.1f, 0.4f);

    LibraryScreen(TvView tv) {
        super(tv);
        nav = new NavBar(tv, NavBar.LIBRARY);
        fill();
        grid.focusIn();
    }

    private void fill() {
        String keep = grid.focused() != null ? grid.focused().id : null;
        grid.items.clear();
        grid.items.addAll(tv.store.libraryItems(TYPE_IDS[type]));
        grid.focus = 0;
        if (keep != null) for (int i = 0; i < grid.items.size(); i++) if (grid.items.get(i).id.equals(keep)) grid.focus = i;
        if (grid.items.isEmpty() && zone == GRID) zone = TYPES;
    }

    @Override
    void onShow() {
        nav.focus = nav.current;
        ReleaseCalendar.listen(calendarChanged);
        ReleaseCalendar.refresh(tv.store, false);
        fill();
    }

    @Override
    void onHide() {
        ReleaseCalendar.unlisten(calendarChanged);
    }

    private final Runnable calendarChanged = new Runnable() {
        @Override
        public void run() {
            tv.kick();
        }
    };

    @Override
    void dataChanged(boolean addonsChanged) {
        ReleaseCalendar.refresh(tv.store, false);
        fill();
    }

    @Override
    void draw(Canvas c, float dt) {
        c.drawColor(Theme.BG);
        scroll.to(zone == GRID && grid.row() > 0 ? grid.row() * Grid.STEP_Y - 150 : 0);
        float sy = step(scroll, dt);
        float x = TvView.MARGIN;
        for (int i = 0; i < TYPE_LABELS.length; i++) {
            if (CHIPS_Y - sy > -80) tv.drawChip(c, x, CHIPS_Y - sy, TYPE_LABELS[i], zone == TYPES && i == typeFocus, i == type);
            x += tv.chipWidth(TYPE_LABELS[i]) + 14;
        }
        if (CHIPS_Y - sy > -80) c.drawText(tv.fit(ReleaseCalendar.nextLine(), tv.tSmall, TvView.W - TvView.MARGIN - x - 10), x + 10, CHIPS_Y - sy + 37, tv.tSmall);
        grid.draw(this, c, TvView.MARGIN, GRID_Y - sy, zone == GRID, dt);
        if (grid.items.isEmpty()) {
            c.drawText(tv.store.profileLoaded ? "Nothing here yet. Use “+ Library” on a movie or show to save it."
                    : "Loading your library…", TvView.MARGIN, 420, tv.tBody);
        }
        nav.draw(c, zone == NAV, sy > 0);
    }

    @Override
    boolean key(int k, KeyEvent e) {
        switch (zone) {
            case NAV:
                if (k == TvView.DOWN) {
                    zone = TYPES;
                    typeFocus = type;
                    return true;
                }
                return nav.key(k);
            case TYPES:
                if (k == TvView.LEFT && typeFocus > 0) typeFocus--;
                else if (k == TvView.RIGHT && typeFocus < TYPE_LABELS.length - 1) typeFocus++;
                else if (k == TvView.UP) {
                    zone = NAV;
                    nav.focus = nav.current;
                } else if (k == TvView.DOWN && !grid.items.isEmpty()) {
                    zone = GRID;
                    grid.focusIn();
                }
                if (k == TvView.OK && typeFocus == CALENDAR) {
                    tv.push(new CalendarScreen(tv));
                    return true;
                }
                if ((k == TvView.LEFT || k == TvView.RIGHT || k == TvView.OK) && typeFocus != type && typeFocus != CALENDAR) {
                    type = typeFocus;
                    grid.focus = 0;
                    fill();
                    scroll.snap(0);
                }
                return true;
            case GRID:
            default:
                if (grid.open(tv, k)) return true;
                if (grid.move(k)) return true;
                if (k == TvView.UP) {
                    zone = TYPES;
                    typeFocus = type;
                }
                return true;
        }
    }

    @Override
    boolean back() {
        if (zone == GRID && grid.row() > 0) {
            grid.prev = -1;
            grid.focus = grid.focus % grid.cols;
            return true;
        }
        if (zone != NAV) {
            zone = NAV;
            nav.focus = nav.current;
            return true;
        }
        return false;
    }
}
