package com.codio.tv.ui;

import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

/** Every source for one movie/episode, to play a particular one. */
final class PhoneSourcesPage extends PhoneUi.Page {
    private final Store.Meta meta;
    private final String videoId;
    private LinearLayout list;
    private ProgressBar spin;
    private TextView note;
    private String shownSig = null;
    private final Handler h = new Handler(Looper.getMainLooper());

    PhoneSourcesPage(PhoneUi ui, Store.Meta meta, String videoId) {
        super(ui);
        this.meta = meta;
        this.videoId = videoId != null ? videoId : meta.id;
    }

    @Override
    View build() {
        LinearLayout col = new LinearLayout(ctx());
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(ctx());
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, dp(8), dp(12), dp(4));
        TextView back = PhoneKit.text(ctx(), "‹", 30, Color.WHITE, true);
        back.setPadding(dp(14), 0, dp(10), dp(4));
        back.setClickable(true);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.back();
            }
        });
        head.addView(back);
        TextView t = PhoneKit.text(ctx(), "Choose a source", 20, Color.WHITE, true);
        head.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        spin = new ProgressBar(ctx());
        head.addView(spin, new LinearLayout.LayoutParams(dp(28), dp(28)));
        col.addView(head);
        note = PhoneKit.text(ctx(), "Loading sources…", 13, PhoneKit.TEXT2, false);
        note.setPadding(dp(16), 0, dp(16), dp(8));
        col.addView(note);
        ScrollView sv = new ScrollView(ctx());
        list = new LinearLayout(ctx());
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        return col;
    }

    @Override
    void onShow() {
        ui.host.prefetch(meta, videoId);
        h.post(tick);
    }

    @Override
    void onHide() {
        h.removeCallbacks(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            fill();
            h.postDelayed(this, 600);
        }
    };

    private void fill() {
        Store.Sources s = ui.store.sourcesFor(meta.type, meta.id, videoId);
        if (s == null) return;
        spin.setVisibility(s.loading ? View.VISIBLE : View.GONE);
        String sig = s.loading + "|" + s.list.size();
        if (sig.equals(shownSig)) return;
        shownSig = sig;
        note.setText(s.list.isEmpty() ? (s.loading ? "Loading sources…" : "No sources found.") : s.list.size() + " sources" + (s.loading ? " (more loading)" : ""));
        list.removeAllViews();
        for (final Store.Source src : s.list) {
            LinearLayout r = new LinearLayout(ctx());
            r.setOrientation(LinearLayout.VERTICAL);
            r.setPadding(dp(16), dp(10), dp(16), dp(10));
            r.setBackground(PhoneKit.pressable(null, 0));
            r.setClickable(true);
            r.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ui.host.playSource(meta, videoId, src);
                }
            });
            String q = PhoneSources.quality(src);
            String head = (src.rank == 0 ? "★ " : "") + (q != null ? PhoneKit.qualityLabel(q) + "  ·  " : "") + (src.addon != null ? src.addon : "");
            r.addView(PhoneKit.text(ctx(), head, 14, Color.WHITE, true));
            String body = ((src.name != null ? src.name + "\n" : "") + (src.description != null ? src.description : "")).trim();
            TextView d = PhoneKit.text(ctx(), body, 12, PhoneKit.TEXT2, false);
            d.setMaxLines(5);
            r.addView(d);
            list.addView(r);
        }
    }
}
