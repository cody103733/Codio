package com.codio.tv.ui;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** "Finding your stream": shown while Smart auto-play picks a source and the player starts. */
final class PhoneLoading extends FrameLayout {
    private final TextView status;

    PhoneLoading(final PhoneUi ui, Store.Meta m, String videoId, String quality) {
        super(ui.getContext());
        setBackgroundColor(Color.BLACK);
        setClickable(true);
        ImageView bg = new ImageView(getContext());
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bg.setAlpha(0.35f);
        PhoneKit.image(bg, HomeScreen.backgroundOf(m), getResources().getDisplayMetrics().widthPixels);
        addView(bg, new LayoutParams(-1, -1));
        View shade = new View(getContext());
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x66000000, 0xEE000000}));
        addView(shade, new LayoutParams(-1, -1));
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int p = PhoneKit.dp(getContext(), 24);
        col.setPadding(p, p, p, p);
        TextView name = PhoneKit.text(getContext(), m.name != null ? m.name : "", 22, Color.WHITE, true);
        name.setGravity(Gravity.CENTER);
        col.addView(name);
        if (videoId != null && !videoId.equals(m.id)) {
            for (Store.Video v : m.videos) {
                if (videoId.equals(v.id)) {
                    TextView ep = PhoneKit.text(getContext(), "S" + v.season + " E" + v.episode + (v.title != null ? " · " + v.title : ""), 14, PhoneKit.TEXT2, false);
                    ep.setGravity(Gravity.CENTER);
                    col.addView(ep);
                    break;
                }
            }
        }
        ProgressBar spin = new ProgressBar(getContext());
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(PhoneKit.dp(getContext(), 48), PhoneKit.dp(getContext(), 48));
        sl.setMargins(0, p, 0, PhoneKit.dp(getContext(), 12));
        col.addView(spin, sl);
        status = PhoneKit.text(getContext(), "Finding the best source" + (quality != null ? " in " + PhoneKit.qualityLabel(quality) : "") + "…", 14, 0xFFDDDDDD, false);
        status.setGravity(Gravity.CENTER);
        col.addView(status);
        TextView cancel = PhoneKit.button(getContext(), "Cancel", false);
        cancel.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.host.cancelPlay();
            }
        });
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
        cl.setMargins(0, p, 0, 0);
        col.addView(cancel, cl);
        addView(col, new LayoutParams(-1, -2, Gravity.CENTER));
    }

    void setStatus(String kind, String text) {
        if (text != null && !text.isEmpty()) status.setText(text);
        else if ("player".equals(kind)) status.setText("Starting the video…");
    }
}
