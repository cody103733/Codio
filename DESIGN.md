# Codio design

One look and one vocabulary for Codio on Android TV, Android phones, iPhone and Windows. When a screen needs a colour or a
label, take it from here; when something new is needed, add it here and to all three theme files.

## Where the theme lives

| Platform | File |
| --- | --- |
| Android TV and phone | `android/src/com/codio/tv/ui/Theme.java` |
| iPhone | `ios/Codio/UI/Components.swift` (`Theme`) |
| Windows | `windows/src/routes/Codio/Codio.less` (the variables at the top) |

## Colours

| Name | Value | Used for |
| --- | --- | --- |
| red | `#E50914` | The brand colour: selected chips, progress, the line under the current tab |
| red-soft | `#FF5A5F` | Error text on dark backgrounds |
| logo gradient | `#FF4B2B` to `#C3001D` | The C of the wordmark only |
| black | `#000000` | Behind video and artwork |
| bg | `#0B0B0B` | Flat pages on TV and Windows (Search, Library, Settings), and the phones' tab bar. Phone pages are black |
| card | `#161616` | Rows, cards, poster placeholders |
| raised | `#262626` | Chips that aren't chosen, buttons and fields that sit on a card or page |
| line | `#333333` | Dividers, empty progress tracks |
| text | `#FFFFFF` | Titles and anything focused-on-dark |
| body | `#D6D6D6` | Paragraphs and help text |
| dim | `#B3B3B3` | Secondary labels, values, inactive tabs |
| faint | `#8C8C8C` | Hints and placeholders |
| focus | `#FFFFFF` | The focused or hovered row or button (text on it is on-focus `#111111`, secondary `#444444`) |

Profile colours, in order: `#E60A14`, `#2178F2`, `#FABA1A`, `#29AD61`, `#8C4DD9`, `#F27326`.

## Type and shape

- The iPhone app is the standard look. The other apps copy it, adapted to a remote (TV) or a mouse (Windows).
- Font: the system font (San Francisco on iPhone, Roboto on Android, Segoe UI on Windows).
- Focus is shown by turning the row or button white with dark text, never by an outline alone.
- Corners: 8 to 10 on rows, keys and buttons, fully round on chips and avatars.
- Chips and pills: red with white text when chosen, a thin white outline (50%) with no fill otherwise. Search and Library
  filters on phones are a segmented control with the chosen part red.
- The current page in the tab bar or top bar is red; the others are grey `#8E8E93`.
- Settings are an inset grouped list: small grey (`#8D8D93`) upper-case section headings, rows `#1C1C1E` joined into one
  rounded card per section with thin `#38383A` lines between them, the name in white on the left and what it's set to in
  red on the right.

## The wordmark

The red C with the play button **is** the C of "codio": the mark is followed by "odio". It is never shown as an icon next to
the full word.

## Words

| Say | Not |
| --- | --- |
| Shows | Series, TV shows |
| Library, "+ Library", "✓ In Library", "Added to Library" | My List, Watchlist |
| Sources | Streams, links |
| Smart auto-play | Autoplay |
| Skip Intro, Skip Recap, Skip Credits | |
| Up Next, "Up Next in 8", Play now, Watch credits | Next Episode button, Coming up |
| New episode (the red mark on a poster) | New, Unwatched |
| Who's watching | Switch user |

Tabs, in order: Home, Movies, Shows, Music, Search, Library, Addons, Settings (phones fold some of these into Home and Settings; Music is on Windows, Android TV and the Android phone so far; the Android phone's bar is Home, Search, Music, Library, Settings).

Music: songs, albums and artists come from MusicBrainz; the audio comes from "music addons" (Eclipse Music's addon protocol, see `music/`), kept on the device and added by pasting a link. Words: Music, Songs, Albums, Artists, Recently played, Play next, Add to queue, Up next (the queue), Music addons, "No source available". Hearts and "Not interested" on songs, albums and artists shape the rows (Daily mix, Because you played, Similar to your favorites); it stays on the device. On the Android phone the Java for all of this is in `android/src/com/codio/tv/music/` (shared with TV) and the screens are `PhoneMusic`, `PhoneAlbum`, `PhoneArtist`, `PhoneMusicAddons`, `PhoneNowPlaying` and the `MiniBar` above the tab bar; playback runs in a foreground service so it continues with the screen off. On Android TV the screens are `MusicScreen` (home rows), `MusicSearchScreen`, `MusicAlbumScreen`, `MusicArtistScreen`, `MusicNowScreen` (what is playing, with the queue) and `MusicSettingsScreen` (addons, ListenBrainz account, your taste), built on `MusicPage`, `MusicStrip` and `TvMusicKit`; OK plays or opens, holding OK shows the options (Play next, Add to queue, Heart, Not interested), Play/Pause, Fast forward and Rewind work on every Music screen, and a bar along the bottom shows what is playing. Music pauses when a video starts.

Search filter: Both, Movies, Shows. Empty search shows "Recent searches" with "Clear recent searches" as the last row.

## Settings

Sections, in order, with the same labels and help text everywhere a setting exists:

1. **Playback**: Smart auto-play, Preferred quality, If that quality isn't available, Skip sources above, Prefer instant
   sources, Source list (TV, Windows), Next episode, Skip Intro button, Skip automatically, Up Next, New episode alerts,
   Who's watching? and Mini player (phones), Skip forward and back (TV)
2. **Picture & sound**: what the device's player offers (Dolby Vision, Start-up buffer, Audio format, Audio sync on TV;
   Hardware decoding, Surround sound on Windows; Ambient screen and Ambient screen after on TV and Windows)
3. **Downloads** (phones)
4. **Languages & subtitles**: Audio language, Subtitles, Subtitle language (TV, phones), Subtitle size, Subtitle colour
   (White, Yellow, Cyan, Green), Subtitle background (None, Shaded box, Black box). All of these belong to the profile:
   language, size, colour and background live in the profile's synced settings (`subtitlesLanguage`, `subtitlesSize`,
   `subtitlesTextColor`, `subtitlesBackgroundColor`, Stremio `#RRGGBBAA` colours); the TV and phones keep Subtitles
   (Off / Automatic) on the device for each profile. The players apply them to every video.
5. **Account**: sign in or out, Who's watching, Cast photos (TMDB key), Sync passcode (rules in `SYNC.md`)
6. **Addons**
7. **About**: Updates, and the version as "Codio 1.2.3 for Android TV / Android / iPhone / Windows"

Choices read as plain values ("On", "Off", "Plays automatically", "4 seconds (balanced)"). The canonical wording is in
`android/src/com/codio/tv/ui/Setting.java`.

## Phones

Codio for iPhone and Codio for Android are the same screens, drawn to the same numbers (points on one are dp on the other).
`ios/Codio/UI` is the reference; `android/src/com/codio/tv/ui/Phone*.java` follows it. A change to one is made to both.

- **Tab bar**: Home, Search, Library, Settings on black, filled icons over a 10 label; red when chosen, `#8E8E93`
  otherwise.
- **Home**: the featured poster blurred behind the top of the page. The wordmark and the avatar (32), the outlined pills
  "Shows" and "Movies", then a rounded featured card (2:3, corner 10) with the logo, "TOP 10 · #1 in Movies Today", the
  genres joined with " • ", Play (white) and "+ Library". Below: Continue Watching (110 by 160 cards with a play circle,
  progress and an ⓘ / S1:E2 bar), "Top 10 Movies Today" with big outlined numbers, then a row per catalog (posters 112 by
  168).
- **Search, Library, Settings**: a large title (34 bold) with the avatar (28) above it on the right.
- **Search**: the field ("Movies and shows"), Both / Movies / Shows, then "Recent searches" and popular titles, or the
  results (cells at least 104 wide, 8 apart).
- **Library**: All / Movies / Shows / Downloads, then the grid.
- **Movies / Shows** (from Home): outlined menu pills (catalog, genre) with a ⌄, then the grid.
- **Settings**: the grouped list above, help text under each section, red buttons, and the version at the bottom.

## Up Next

Near the end of an episode a card (bottom right) shows the next one: its picture, "Up Next in 8", "S1 E5 · Title", and the
buttons Play now and Watch credits (on TV: OK plays, Back keeps watching). The rules, the same in every app
(`android/src/com/codio/tv/ui/UpNext.java` is the reference):

- Credits timed by IntroDB: the card comes up as the credits start and counts down 10 seconds, then the next episode plays.
- Credits not timed: the card comes up as long before the end as the Up Next setting says (35 seconds unless changed) and
  counts down to the end.
- "Next episode" set to Off: the card still offers the next episode, but nothing starts by itself.
- "Skip automatically" with credits: the next episode starts as the credits begin, so there is no card to wait for.
- The countdown follows the video's position: it stops while paused and goes away after seeking back.

## New episode alerts

A show you're watching (watched in the last six months, or in the Library) gets a red "New episode" mark on its poster and
moves to the front of Continue Watching when an episode came out after you last watched it. The mark goes when you open the
show or watch something of it, and after 30 days. Phones also send a notification, checked a few times a day; tapping it
opens the show. The episode dates come from the catalog addons (Cinemeta's "last videos" list).

## Trailers

A Trailer button sits on every movie and show page that has one (TV and Windows: after Library; phones: next to Library
and Sources). The trailer is a YouTube video, chosen the same way in every app:

- With a TMDB key: TMDB's videos for the title (English or no language), YouTube only, a Trailer before a Teaser, an
  official one before others, otherwise TMDB's order.
- Without a key, or when TMDB has none: the trailer the catalog addon gave (`trailerStreams` / `trailers`, Cinemeta has one
  for most titles). No trailer found: no button.
- Playing it: Android TV opens the YouTube TV app (Google TV's, then Fire TV's); Android phones open the YouTube app or the
  browser; iPhone opens the YouTube app, else an in-app web page. When no app can open it, Android plays YouTube's own player
  full screen inside Codio (OK pauses, Back closes). Windows always plays YouTube's player over the page (Back or Esc closes).

## Ambient screen

When Codio sits untouched (Android TV and Windows), a slideshow takes over instead of a still page or a dark screen:

- Pictures: the wide backdrop of titles in Continue Watching and the Library (Metahub's picture for IMDb ids, as these
  have none of their own), shuffled; topped up from the first Home row when there are fewer than 6. Each stays 12 seconds
  and cross-fades over 1.5 seconds. The title's logo (or its name) and year sit bottom left, the time top right; both
  shift a little with each picture so nothing stays in one place on the panel.
- Starts after Ambient screen after (1, 2, 5, 10, 15 or 30 minutes; TV 2, Windows 5 by default) with nothing pressed.
  Never over a video, the source search, a keyboard, Now Playing, a trailer or a dialog.
- Any button (Windows: any key, click or mouse move) brings back the screen underneath, and that press does nothing else.
- After an hour it turns black and, on the TV, lets the TV's own screen saver and sleep take over again.
- Light by design: two pictures in memory, frames only while a picture fades and once a minute for the clock.

## Voice search (Android TV)

- A round mic key sits at the end of the Search line, one step up from the keyboard. Press OK to speak.
- The remote's search/mic button opens Search and starts listening, when the TV passes that button to Codio (not over the video).
- The TV's own "Speak now" prompt is used when it has one (Google TV). Otherwise Codio shows its own Listening card and asks once for the microphone.
- A TV with no speech recognizer (most Fire TVs) shows no mic key; the remote button says "Voice search isn't available on this TV".
- What was heard becomes the search, and the focus moves to the first result.
- "Search for ... in Codio" from the TV's assistant opens Search with that text.

## Lyrics

Music shows the words of the song that is playing, from LRCLIB (lrclib.net: free, no key, no account).

- Lookup: `/api/get` with artist, title, album and length (seconds) first; if that finds nothing, `/api/search` with
  artist and title, keeping only an answer that is the same recording (same rules as matching a song to an addon:
  title and artist agree, length within 3 s). Timed lyrics win over plain ones.
- Timed (LRC) lyrics follow the song: the line being sung is white, sung lines darker grey, coming lines grey; the line
  counts as current 0.25 s early. The page keeps it a third of the way down unless you scrolled by hand in the last 4 s.
  Tap or click a line to jump there. Plain lyrics show as text with "These lyrics aren't timed to the song."
- Where: phones have a Lyrics card on Now Playing (the line now and the next one), tap for the full page; TV has a
  "Lyrics: On/Off" button on Now Playing that puts the words where the queue is; Windows has a Lyrics button in the mini
  player that opens a panel above it.
- Always credit "Lyrics from LRCLIB". Answers are cached a month; a downloaded song keeps its lyrics next to it.
- Code: `android/.../music/Lyrics.java`, `ios/Codio/Music/Lyrics.swift`, `windows/.../music/lyrics.ts` (+ `LyricsPanel.tsx`,
  desktop proxy `/api/music/lrclib`).

## Music downloads

The phone apps can keep songs to play offline (Android phone and iPhone; not TV or Windows).

- Download a song from its ⋯ menu, or a whole album with the album page's Download button. One song downloads at a time:
  the audio (found the same way playing finds it, so it needs a music addon), the cover and the lyrics.
- Downloads follow the same "Download over" setting as videos (Wi-Fi only by default: they wait for Wi-Fi).
- A downloaded song always plays from the file, wherever it was opened from (a song matching by key, or the same
  recording). The source line says "Downloaded".
- Music shows a "Downloaded" row at the top when there are any; See all opens the list (Play, Shuffle, what is on the way,
  remove, Remove all). Song rows show a small ↓ mark: red when ready, grey while it downloads.
- Code: `android/.../music/MusicDownloads.java` (files/music-downloads.json, app Music folder) + `ui/PhoneMusicDownloads.java`;
  `ios/Codio/Music/MusicDownloads.swift` (Application Support/MusicDownloads, file names stored, not paths) + `MusicExtras.swift`.

## Your stats

A recap page for the profile: "Your 2026" and "All time" (a segmented control at the top). It is opened from Settings,
the first section ("You": "Your stats", with the hours this year as its value). Everything is worked out on the device;
nothing new is sent anywhere.

- **Watching** comes from the profile's Stremio library (already synced): each title's `state.overallTimeWatched` (ms).
  All time adds them all up. For a year, a title counts when it was last watched that year; the first time a device sees
  the library in a new year it saves every title's time as that year's starting point (per profile), and from then on a
  title's year time is its time now minus that starting point. A year with no starting point (the year stats came, or a
  new device) counts the whole time of titles last watched that year. Only movies and shows, temp and removed titles
  included (they were still watched).
- **Listening** is a play log kept on the device for each profile, per year: a song counts when it starts playing (any
  source, downloads too), its length adds to the minutes. Songs are matched by artist + title (lower case, trimmed),
  artists by name. Up to 2000 songs a year are kept (the least played go first).
- **The page**, in order: four tiles (Hours watched, Titles watched with "N movies · N shows" under it, Minutes listened,
  Songs played), then Top shows and Top movies (5 each, poster, name, hours), then Top songs (5: cover, title, artist,
  plays) and Top artists (5, plays). A section with nothing in it says so in one grey line ("Nothing watched yet this
  year."). Hours have one decimal under 10. Tapping a show or movie opens it; a song plays it.
- Code: `android/.../ui/Stats.java` (shared rules) + `PhoneStats.java` + TV `StatsScreen.java`; `ios/Codio/Store/Stats.swift`
  + `UI/StatsView.swift`; `windows/.../Codio/stats.ts` + `StatsPage.tsx`.

## Rules that are not about looks

- Only http(s) and debrid sources. No torrents.
- Sources stay out of sight unless "Source list" is set to Shown.
- The bitrate limit ("Skip sources above") always stays a setting.
