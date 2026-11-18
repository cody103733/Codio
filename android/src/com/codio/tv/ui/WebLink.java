package com.codio.tv.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/** Opens a web page (where to get an API key) in the browser. False if nothing on the device can open it. */
final class WebLink {
    private WebLink() {}

    static final String TMDB_KEY = "https://www.themoviedb.org/settings/api";
    static final String LASTFM_KEY = "https://www.last.fm/api/account/create";
    static final String LISTENBRAINZ_TOKEN = "https://listenbrainz.org/settings/";

    static boolean open(Context c, String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** On a TV: open it, or say where to go on a phone or computer. */
    static void openOnTv(TvView tv, String url) {
        if (!open(tv.getContext(), url)) tv.toast("No web browser on this TV. Open " + url.replaceFirst("^https://(www\\.)?", "") + " on your phone or computer");
    }
}
