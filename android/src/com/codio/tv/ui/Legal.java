package com.codio.tv.ui;

/** The short legal note in Settings, About (TV and phone). The full texts are in the repository (NOTICE_URL). */
final class Legal {
    private Legal() {}

    static final String NOTICE_URL = "https://github.com/cody103733/Codio/blob/main/NOTICE.md";

    static final String TEXT = "Codio is free software under the GNU GPL version 2, based on Stremio Web "
            + "(© Smart Code OOD). It is not affiliated with or endorsed by Stremio, TMDB, YouTube, Apple, "
            + "Last.fm, MusicBrainz or any addon.\n\n"
            + "Codio hosts no videos or music. Everything plays from addons you add, and you are responsible for them "
            + "and for following the law where you live. Provided as is, with no warranty.\n\n"
            + "This product uses the TMDB API but is not endorsed or certified by TMDB.\n\n"
            + "Source, licenses, notices and privacy: github.com/cody103733/Codio";

    /** The TV's help panel shows about 8 short lines, so the TV gets the short version. */
    static final String TV_TEXT = "Free software (GNU GPL v2) based on Stremio Web. Not affiliated with Stremio, TMDB or "
            + "any addon. Codio hosts no content: you are responsible for the addons you use. No warranty. "
            + "Notices and privacy: github.com/cody103733/Codio. Press OK to open.";
}
