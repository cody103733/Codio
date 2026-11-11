package com.codio.tv.ui;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** A movie or show: Play, Download (with a quality choice), My List, and for shows the seasons and episodes. */
final class PhoneDetail extends PhoneUi.Page {
    final Store.Meta meta;
    private boolean loaded = false;
    private LinearLayout col, episodesBox, seasonStrip;
    private ImageView backdrop, logo;
    private TextView title, metaLine, desc, cast, playBtn, dlBtn, listBtn, sourcesBtn, status;
    private ProgressBar spin;
    private final List<Integer> seasons = new ArrayList<>();
    private int season = -1;
    private final List<EpisodeRow> episodeRows = new ArrayList<>();

    PhoneDetail(PhoneUi ui, Store.Meta meta) {
        super(ui);
        this.meta = meta;
    }

    @Override
    View build() {
        ScrollView sv = new ScrollView(ctx());
        col = new LinearLayout(ctx());
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 0, 0, dp(32));
        sv.addView(col);

        FrameLayout top = new FrameLayout(ctx());
        backdrop = new ImageView(ctx());
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        top.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(ctx());
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x66000000, 0x00000000, 0xFF000000}));
        top.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        TextView back = PhoneKit.text(ctx(), "‹", 30, Color.WHITE, true);
        back.setGravity(Gravity.CENTER);
        back.setPadding(0, 0, 0, dp(4));
        back.setBackground(PhoneKit.pressable(PhoneKit.round(0x88000000, dp(20)), dp(20)));
        back.setClickable(true);
        back.setContentDescription("Back");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.back();
            }
        });
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(dp(40), dp(40));
        bl.setMargins(dp(10), dp(10), 0, 0);
        top.addView(back, bl);
        logo = new ImageView(ctx());
        logo.setScaleType(ImageView.ScaleType.FIT_START);
        FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(dp(220), dp(70), Gravity.BOTTOM | Gravity.START);
        ll.setMargins(dp(16), 0, 0, dp(4));
        top.addView(logo, ll);
        int w = ctx().getResources().getDisplayMetrics().widthPixels;
        col.addView(top, new LinearLayout.LayoutParams(-1, Math.round(Math.min(w, dp(700)) * 9f / 16f)));

        LinearLayout body = new LinearLayout(ctx());
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(4), dp(16), 0);
        col.addView(body);
        title = PhoneKit.text(ctx(), meta.name, 24, Color.WHITE, true);
        body.addView(title);
        metaLine = PhoneKit.text(ctx(), "", 13, PhoneKit.TEXT2, false);
        metaLine.setPadding(0, dp(4), 0, dp(12));
        body.addView(metaLine);

        playBtn = PhoneKit.button(ctx(), "▶  Play", true);
        playBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                play();
            }
        });
        playBtn.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                chooseSource();
                return true;
            }
        });
        body.addView(playBtn, full());
        dlBtn = PhoneKit.button(ctx(), "⬇  Download", false);
        dlBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                downloadMain();
            }
        });
        body.addView(dlBtn, full());
        status = PhoneKit.text(ctx(), "", 12, PhoneKit.TEXT2, false);
        status.setGravity(Gravity.CENTER);
        status.setVisibility(View.GONE);
        body.addView(status, full());

        LinearLayout small = new LinearLayout(ctx());
        small.setPadding(0, dp(6), 0, dp(6));
        listBtn = smallAction("+  My List");
        listBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleLibrary();
            }
        });
        sourcesBtn = smallAction("☰  Sources");
        sourcesBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseSource();
            }
        });
        small.addView(listBtn, new LinearLayout.LayoutParams(0, -2, 1));
        small.addView(sourcesBtn, new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(small, full());

        desc = PhoneKit.text(ctx(), "", 14, 0xFFDDDDDD, false);
        desc.setLineSpacing(0, 1.15f);
        body.addView(desc);
        cast = PhoneKit.text(ctx(), "", 12, PhoneKit.TEXT2, false);
        cast.setPadding(0, dp(8), 0, 0);
        body.addView(cast);

        spin = new ProgressBar(ctx());
        col.addView(spin, new LinearLayout.LayoutParams(-1, dp(48)));

        HorizontalScrollView hs = new HorizontalScrollView(ctx());
        hs.setHorizontalScrollBarEnabled(false);
        seasonStrip = new LinearLayout(ctx());
        seasonStrip.setPadding(dp(12), dp(16), dp(12), dp(4));
        hs.addView(seasonStrip);
        col.addView(hs);
        episodesBox = new LinearLayout(ctx());
        episodesBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(episodesBox);

        fillInfo();
        load();
        return sv;
    }

    private LinearLayout.LayoutParams full() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(4), 0, dp(4));
        return lp;
    }

    private TextView smallAction(String s) {
        TextView t = PhoneKit.text(ctx(), s, 14, Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(10), 0, dp(10));
        t.setBackground(PhoneKit.pressable(null, dp(8)));
        t.setClickable(true);
        return t;
    }

    private void load() {
        ui.store.loadMeta(meta, new Store.Callback<Store.Meta>() {
            @Override
            public void done(Store.Meta m, Exception error) {
                if (m == null && ui.downloads.hasSavedMeta(meta.id)) {
                    // Offline: the details saved with the download.
                    Store.Meta saved = ui.downloads.savedMeta(meta.id, null);
                    meta.merge(saved);
                    if (saved.poster != null) meta.poster = saved.poster;
                }
                loaded = true;
                spin.setVisibility(View.GONE);
                fillInfo();
                buildSeasons();
                prefetch();
            }
        });
    }

    /** Load the sources for what Play would start now, in the background, so Play starts quickly (like the TV app). */
    private void prefetch() {
        if (!ui.online() || ui.downloads.lookupActive() || ui.host.webBusy()) return;
        String vid;
        if (isSeries()) {
            Store.Video v = resumeVideo();
            if (v == null) return;
            vid = v.id;
        } else {
            vid = meta.id;
        }
        PhoneDownloads.Entry e = ui.downloads.entry(meta.type, meta.id, vid);
        if (e != null && PhoneDownloads.DONE.equals(e.state)) return;
        ui.host.prefetch(meta, vid);
    }

    @Override
    void onShow() {
        if (loaded) prefetch();
    }

    private boolean isSeries() {
        return !"movie".equals(meta.type) && !meta.videos.isEmpty();
    }

    private void fillInfo() {
        title.setText(meta.name);
        String bg = HomeScreen.backgroundOf(meta);
        if (bg != null && !bg.equals(backdrop.getTag())) PhoneKit.image(backdrop, bg, ctx().getResources().getDisplayMetrics().widthPixels);
        String lg = HomeScreen.logoOf(meta);
        if (lg != null && !lg.equals(logo.getTag())) PhoneKit.image(logo, lg, dp(220));
        String ml = meta.metaLine(), g = meta.genreLine();
        metaLine.setText(ml + (!ml.isEmpty() && !g.isEmpty() ? "  ·  " : "") + g);
        desc.setText(meta.description != null ? meta.description : "");
        StringBuilder c = new StringBuilder();
        for (String s : meta.cast) c.append(c.length() > 0 ? ", " : "Starring: ").append(s);
        cast.setText(c.toString());
        cast.setVisibility(c.length() > 0 ? View.VISIBLE : View.GONE);
        refresh();
    }

    @Override
    void refresh() {
        if (playBtn == null) return;
        playBtn.setText("▶  " + playLabel());
        listBtn.setText(ui.store.inLibrary(meta.id) ? "✓  My List" : "+  My List");
        updateDownloadButton();
        for (EpisodeRow r : episodeRows) r.update();
    }

    // ------------------------------------------------------------------ play

    private Store.Video resumeVideo() {
        if (meta.videos.isEmpty()) return null;
        Store.LibraryEntry e = ui.store.libraryEntry(meta.id);
        String vid = e != null ? e.videoId : meta.lastVideoId;
        if (vid != null) {
            for (int i = 0; i < meta.videos.size(); i++) {
                Store.Video v = meta.videos.get(i);
                if (!vid.equals(v.id)) continue;
                if (e != null && e.duration > 0 && e.offset / e.duration > 0.92 && i + 1 < meta.videos.size()) return meta.videos.get(i + 1);
                return v;
            }
        }
        for (Store.Video v : meta.videos) if (v.season > 0) return v;
        return meta.videos.get(0);
    }

    private String playLabel() {
        Store.LibraryEntry e = ui.store.libraryEntry(meta.id);
        if (isSeries()) {
            Store.Video v = resumeVideo();
            if (v == null) return "Play";
            boolean started = e != null && v.id.equals(e.videoId) && e.offset > 0;
            return (started ? "Resume" : "Play") + "  S" + v.season + " E" + v.episode;
        }
        PhoneDownloads.Entry d = ui.downloads.entry(meta.type, meta.id, meta.id);
        if (d != null && PhoneDownloads.DONE.equals(d.state) && d.position > 60) return "Resume";
        if (e != null && e.offset > 60000 && e.duration > 0 && e.offset / e.duration < 0.92) return "Resume";
        return "Play";
    }

    private void play() {
        if (!loaded && !"movie".equals(meta.type)) return;
        if (isSeries()) {
            Store.Video v = resumeVideo();
            if (v != null) ui.play(meta, v.id);
        } else {
            ui.play(meta, meta.id);
        }
    }

    private void chooseSource() {
        if (isSeries()) {
            Store.Video v = resumeVideo();
            if (v != null) ui.openSources(meta, v.id);
        } else {
            ui.openSources(meta, meta.id);
        }
    }

    private void toggleLibrary() {
        boolean in = ui.store.inLibrary(meta.id);
        if (in) ui.ctx("RemoveFromLibrary", meta.id);
        else ui.ctx("AddToLibrary", Store.preview(meta));
        ui.store.markInLibrary(meta, !in);
        ui.toast(in ? "Removed from My List" : "Added to My List");
        refresh();
    }

    // ------------------------------------------------------------------ downloads

    private void updateDownloadButton() {
        status.setVisibility(View.GONE);
        if (isSeries()) {
            int s = season >= 0 && season < seasons.size() ? seasons.get(season) : -1;
            if (s < 0) {
                dlBtn.setVisibility(View.GONE);
                return;
            }
            dlBtn.setVisibility(View.VISIBLE);
            List<Store.Video> eps = episodesOf(s);
            int done = 0, active = 0;
            for (Store.Video v : eps) {
                PhoneDownloads.Entry e = ui.downloads.entry(meta.type, meta.id, v.id);
                if (e == null) continue;
                if (PhoneDownloads.DONE.equals(e.state)) done++;
                else if (!PhoneDownloads.FAILED.equals(e.state)) active++;
            }
            String name = s == 0 ? "Specials" : "Season " + s;
            if (done == eps.size() && done > 0) dlBtn.setText("✓  " + name + " downloaded");
            else if (active > 0) dlBtn.setText("⬇  Downloading " + name + " (" + done + "/" + eps.size() + ")");
            else dlBtn.setText("⬇  Download " + name);
            return;
        }
        PhoneDownloads.Entry e = ui.downloads.entry(meta.type, meta.id, meta.id);
        dlBtn.setVisibility(View.VISIBLE);
        if (e == null) {
            dlBtn.setText("⬇  Download");
            return;
        }
        status.setVisibility(View.VISIBLE);
        status.setText(describe(e));
        if (PhoneDownloads.DONE.equals(e.state)) dlBtn.setText("✓  Downloaded");
        else if (PhoneDownloads.FAILED.equals(e.state)) dlBtn.setText("⚠  Download failed");
        else dlBtn.setText("⬇  Downloading " + Math.round(e.fraction() * 100) + "%");
    }

    static String describe(PhoneDownloads.Entry e) {
        String q = e.quality != null ? PhoneKit.qualityLabel(e.quality) : e.wantQuality != null ? PhoneKit.qualityLabel(e.wantQuality) : "";
        switch (e.state) {
            case PhoneDownloads.DONE:
                return "On this phone" + (q.isEmpty() ? "" : " · " + q) + (e.bytes > 0 ? " · " + PhoneKit.size(e.bytes) : "");
            case PhoneDownloads.FAILED:
                return e.error != null ? e.error : "Download failed";
            case PhoneDownloads.WAITING:
                return "Waiting" + (q.isEmpty() ? "" : " · " + q);
            case PhoneDownloads.FINDING:
                return "Finding a source…";
            default:
                String s = e.status != null ? e.status + " · " : "";
                return s + (q.isEmpty() ? "" : q + " · ") + PhoneKit.size(e.done) + (e.bytes > 0 ? " of " + PhoneKit.size(e.bytes) : "");
        }
    }

    private void downloadMain() {
        if (!loaded) return;
        if (isSeries()) {
            if (season < 0 || season >= seasons.size()) return;
            downloadSeason(seasons.get(season));
            return;
        }
        PhoneDownloads.Entry e = ui.downloads.entry(meta.type, meta.id, meta.id);
        if (e != null) manage(e, null);
        else downloadOne(null);
    }

    /** Find sources, then ask which quality. */
    void downloadOne(final Store.Video v) {
        if (!ui.online()) {
            ui.toast("You're offline");
            return;
        }
        @SuppressWarnings("deprecation") final ProgressDialog pd = new ProgressDialog(ctx(), android.R.style.Theme_DeviceDefault_Dialog_Alert);
        pd.setMessage("Finding sources…");
        pd.setCancelable(true);
        final boolean[] cancelled = {false};
        pd.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface d) {
                cancelled[0] = true;
            }
        });
        pd.show();
        ui.downloads.findSources(meta, v != null ? v.id : meta.id, new PhoneDownloads.SourcesCallback() {
            @Override
            public void done(Store.Sources s) {
                if (cancelled[0]) return;
                try {
                    pd.dismiss();
                } catch (Exception ignored) {
                }
                final List<PhoneSources.Option> opts = s != null ? PhoneSources.options(s) : new ArrayList<PhoneSources.Option>();
                if (opts.isEmpty()) {
                    ui.dialog().setTitle("Can't download this")
                            .setMessage(s == null ? "The sources didn't load. Check your internet connection and try again."
                                    : "None of your addons has a downloadable source for this (torrent-only sources can't be downloaded).")
                            .setPositiveButton("OK", null).show();
                    return;
                }
                String[] labels = new String[opts.size()];
                for (int i = 0; i < opts.size(); i++) {
                    PhoneSources.Option o = opts.get(i);
                    labels[i] = PhoneKit.qualityLabel(o.quality) + (o.bytes > 0 ? "   ·   " + PhoneKit.size(o.bytes) : "");
                }
                String what = v != null ? "S" + v.season + " E" + v.episode + (v.title != null && !v.title.isEmpty() ? " · " + v.title : "") : meta.name;
                ui.dialog().setTitle("Download in which quality?")
                        .setItems(labels, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                confirmSpace(v, opts.get(which));
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .setCustomTitle(dialogTitle("Download in which quality?", what + "\n" + PhoneKit.size(ui.downloads.freeBytes()) + " free on this phone"))
                        .show();
            }
        });
    }

    private View dialogTitle(String t, String sub) {
        LinearLayout l = new LinearLayout(ctx());
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(24), dp(20), dp(24), dp(8));
        l.addView(PhoneKit.text(ctx(), t, 19, Color.WHITE, true));
        TextView s = PhoneKit.text(ctx(), sub, 13, PhoneKit.TEXT2, false);
        s.setPadding(0, dp(6), 0, 0);
        l.addView(s);
        return l;
    }

    private void confirmSpace(final Store.Video v, final PhoneSources.Option o) {
        long free = ui.downloads.freeBytes();
        if (o.bytes > 0 && free > 0 && o.bytes > free - 200_000_000L) {
            ui.dialog().setTitle("Not enough space")
                    .setMessage("This file is " + PhoneKit.size(o.bytes) + " but the phone only has " + PhoneKit.size(free) + " free. Pick a lower quality or free up space.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        ui.downloads.start(meta, v, o);
        ui.toast(ui.downloads.wifiOnly() && !onWifi() ? "Download will start on Wi-Fi" : "Downloading in " + PhoneKit.qualityLabel(o.quality));
        refresh();
    }

    private boolean onWifi() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) ctx().getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            return cm != null && !cm.isActiveNetworkMetered();
        } catch (Exception e) {
            return true;
        }
    }

    private void downloadSeason(final int s) {
        final List<Store.Video> eps = episodesOf(s);
        final List<Store.Video> todo = new ArrayList<>();
        for (Store.Video v : eps) {
            PhoneDownloads.Entry e = ui.downloads.entry(meta.type, meta.id, v.id);
            if (e == null || PhoneDownloads.FAILED.equals(e.state)) todo.add(v);
        }
        final String name = s == 0 ? "Specials" : "Season " + s;
        if (todo.isEmpty()) {
            ui.dialog().setTitle(name)
                    .setMessage("Every episode of " + name + " is already on this phone or on its way.")
                    .setPositiveButton("OK", null)
                    .setNegativeButton("Delete " + name, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            for (Store.Video v : eps) {
                                PhoneDownloads.Entry e = ui.downloads.entry(meta.type, meta.id, v.id);
                                if (e != null) ui.downloads.remove(e);
                            }
                            refresh();
                        }
                    }).show();
            return;
        }
        if (!ui.online()) {
            ui.toast("You're offline");
            return;
        }
        final String[] q = {"2160p", "1440p", "1080p", "720p", "480p"};
        String[] labels = {"4K (2160p)", "1440p", "1080p", "720p", "480p (smallest)"};
        ui.dialog().setCustomTitle(dialogTitle("Download " + name + " in which quality?",
                        todo.size() + " episode" + (todo.size() == 1 ? "" : "s") + ". If an episode isn't available in that quality, Codio takes the closest lower one.\n"
                                + PhoneKit.size(ui.downloads.freeBytes()) + " free on this phone"))
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        int n = ui.downloads.startMany(meta, todo, q[which]);
                        ui.toast("Downloading " + n + " episode" + (n == 1 ? "" : "s"));
                        refresh();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** A download that exists: play, retry, cancel or delete. */
    void manage(final PhoneDownloads.Entry e, final Store.Video v) {
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        if (PhoneDownloads.DONE.equals(e.state)) {
            labels.add("Play from this phone");
            actions.add(new Runnable() {
                @Override
                public void run() {
                    ui.play(meta, e.videoId);
                }
            });
            labels.add("Delete download");
            actions.add(new Runnable() {
                @Override
                public void run() {
                    ui.downloads.remove(e);
                    ui.toast("Download deleted");
                    refresh();
                }
            });
        } else if (PhoneDownloads.FAILED.equals(e.state)) {
            labels.add("Try again (pick quality)");
            actions.add(new Runnable() {
                @Override
                public void run() {
                    ui.downloads.remove(e);
                    downloadOne(v);
                }
            });
            labels.add("Remove");
            actions.add(new Runnable() {
                @Override
                public void run() {
                    ui.downloads.remove(e);
                    refresh();
                }
            });
        } else {
            labels.add("Cancel download");
            actions.add(new Runnable() {
                @Override
                public void run() {
                    ui.downloads.remove(e);
                    ui.toast("Download cancelled");
                    refresh();
                }
            });
        }
        ui.dialog().setCustomTitle(dialogTitle(v != null ? "S" + v.season + " E" + v.episode + (v.title != null ? " · " + v.title : "") : meta.name, describe(e)))
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        actions.get(which).run();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    // ------------------------------------------------------------------ seasons & episodes

    private void buildSeasons() {
        seasons.clear();
        seasonStrip.removeAllViews();
        episodesBox.removeAllViews();
        episodeRows.clear();
        if (!isSeries()) {
            sourcesBtn.setVisibility(View.VISIBLE);
            refresh();
            return;
        }
        for (Store.Video v : meta.videos) if (!seasons.contains(v.season)) seasons.add(v.season);
        if (seasons.size() > 1 && seasons.get(0) == 0) seasons.add(seasons.remove(0));
        Store.Video resume = resumeVideo();
        int start = resume != null ? Math.max(0, seasons.indexOf(resume.season)) : 0;
        for (int i = 0; i < seasons.size(); i++) {
            final int idx = i;
            int s = seasons.get(i);
            TextView chip = PhoneKit.chip(ctx(), s == 0 ? "Specials" : "Season " + s, false);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectSeason(idx);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(dp(4), 0, dp(4), 0);
            seasonStrip.addView(chip, lp);
        }
        selectSeason(start);
    }

    private List<Store.Video> episodesOf(int s) {
        List<Store.Video> out = new ArrayList<>();
        for (Store.Video v : meta.videos) if (v.season == s) out.add(v);
        return out;
    }

    private void selectSeason(int idx) {
        season = idx;
        for (int i = 0; i < seasonStrip.getChildCount(); i++) PhoneKit.styleChip((TextView) seasonStrip.getChildAt(i), i == idx);
        episodesBox.removeAllViews();
        episodeRows.clear();
        for (Store.Video v : episodesOf(seasons.get(idx))) {
            EpisodeRow r = new EpisodeRow(v);
            episodeRows.add(r);
            episodesBox.addView(r.row);
        }
        refresh();
    }

    private final class EpisodeRow {
        final Store.Video v;
        final LinearLayout row;
        final TextView state;
        final PhonePoster.Badge badge;
        final TextView sub;

        EpisodeRow(final Store.Video v) {
            this.v = v;
            row = new LinearLayout(ctx());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(8), dp(4), dp(8));
            row.setBackground(PhoneKit.pressable(null, 0));
            row.setClickable(true);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View x) {
                    ui.play(meta, v.id);
                }
            });
            ImageView thumb = new ImageView(ctx());
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setBackground(PhoneKit.round(PhoneKit.CARD, dp(4)));
            thumb.setClipToOutline(true);
            PhoneDownloads.Entry e0 = ui.downloads.entry(meta.type, meta.id, v.id);
            String t = v.thumbnail;
            if (e0 != null && e0.thumbFile != null && new java.io.File(e0.thumbFile).exists() && !ui.online()) t = e0.thumbFile;
            PhoneKit.image(thumb, t, dp(128));
            row.addView(thumb, new LinearLayout.LayoutParams(dp(128), dp(72)));
            LinearLayout txt = new LinearLayout(ctx());
            txt.setOrientation(LinearLayout.VERTICAL);
            txt.setPadding(dp(12), 0, dp(4), 0);
            TextView name = PhoneKit.text(ctx(), v.episode + ". " + (v.title != null && !v.title.isEmpty() ? v.title : "Episode " + v.episode), 14, Color.WHITE, true);
            name.setMaxLines(2);
            txt.addView(name);
            sub = PhoneKit.text(ctx(), "", 12, PhoneKit.TEXT2, false);
            sub.setMaxLines(2);
            txt.addView(sub);
            row.addView(txt, new LinearLayout.LayoutParams(0, -2, 1));
            FrameLayout btn = new FrameLayout(ctx());
            btn.setBackground(PhoneKit.pressable(null, dp(22)));
            btn.setClickable(true);
            btn.setContentDescription("Download episode " + v.episode);
            state = PhoneKit.text(ctx(), "⬇", 20, Color.WHITE, false);
            state.setGravity(Gravity.CENTER);
            btn.addView(state, new FrameLayout.LayoutParams(-1, -1));
            badge = new PhonePoster.Badge(ctx());
            FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER);
            btn.addView(badge, bl);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View x) {
                    PhoneDownloads.Entry e = ui.downloads.entry(meta.type, meta.id, v.id);
                    if (e != null) manage(e, v);
                    else downloadOne(v);
                }
            });
            row.addView(btn, new LinearLayout.LayoutParams(dp(48), dp(48)));
            update();
        }

        void update() {
            PhoneDownloads.Entry e = ui.downloads.entry(meta.type, meta.id, v.id);
            if (e == null) {
                state.setVisibility(View.VISIBLE);
                badge.set(null, 0);
                String when = v.released != null && v.released.length() >= 10 ? v.released.substring(0, 10) : "";
                sub.setText(v.overview != null ? v.overview : when);
                return;
            }
            state.setVisibility(View.GONE);
            badge.set(e.state.equals(PhoneDownloads.DONE) ? PhoneDownloads.DONE : e.state.equals(PhoneDownloads.FAILED) ? PhoneDownloads.FAILED : PhoneDownloads.DOWNLOADING, e.fraction());
            sub.setText(describe(e));
        }
    }
}
