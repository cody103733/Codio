# Privacy

Codio has no servers of its own and no account system of its own. It collects no analytics, crash reports or advertising data for its author. Settings, downloads, the music library and the cache stay on the device.

What leaves the device goes straight from the app to the service that a feature needs:

| Service | What it gets | When |
| --- | --- | --- |
| Stremio (`api.strem.io` and Stremio's default addons) | Your Stremio sign-in, library, watch progress and installed addons, plus the usage events that Stremio's own engine sends, as in Stremio's apps | When you sign in, and while you use the app signed in. See Stremio's privacy policy. |
| Addons you add (video and music) | What you search for, the title or song you want to play, and anything you put in the addon's own link | When you browse, search or press Play |
| TMDB (`api.themoviedb.org`) | Your own TMDB API key and the title being looked at | Cast photos, recommendations, trailers |
| YouTube | The trailer's video id, through the YouTube app or YouTube's embedded player (privacy-enhanced `youtube-nocookie.com` on Windows) | When you press Trailer |
| IntroDB (`api.introdb.app`) | The episode's IMDb id, season and episode | Skip Intro times |
| MusicBrainz, Cover Art Archive | Artist, album and song names and ids | The Music tab |
| Apple (iTunes Search and Apple's public chart feeds), Deezer, Last.fm (with your own key) | Artist and song names, the chart country | Music charts, artist photos, Popular rows |
| LRCLIB (`lrclib.net`) | Song title, artist, album and length | Lyrics |
| ListenBrainz (only if you connect it) | Your token, the songs you play, and requests for your recommendations | Scrobbling and Daily mix |
| GitHub (`github.com`, `raw.githubusercontent.com`) | A normal download request | Checking for and downloading Codio updates |

Each service handles that data under its own privacy policy. Like any internet request, each one also sees your IP address.

**Sync passcode.** If you turn it on, your TMDB key, music addons, ListenBrainz account and music settings are encrypted on the device with your passcode and stored in a hidden entry of your Stremio library, so your other devices can read them. Without the passcode nobody, Stremio included, can open it. Details in [SYNC.md](SYNC.md).

Removing the app and its data removes what it kept on that device. What a service already received is controlled by that service.
