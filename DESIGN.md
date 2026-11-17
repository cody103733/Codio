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

Tabs, in order: Home, Movies, Shows, Music, Search, Library, Addons, Settings (phones fold some of these into Home and Settings; Music is so far on Windows only).

Music: songs, albums and artists come from MusicBrainz; the audio comes from "music addons" (Eclipse Music's addon protocol, see `music/`), kept on the device and added by pasting a link. Words: Music, Songs, Albums, Artists, Recently played, Play next, Add to queue, Up next (the queue), Music addons, "No source available". Hearts and "Not interested" on songs, albums and artists shape the rows (Daily mix, Because you played, Similar to your favorites); it stays on the device.

Search filter: Both, Movies, Shows. Empty search shows "Recent searches" with "Clear recent searches" as the last row.

## Settings

Sections, in order, with the same labels and help text everywhere a setting exists:

1. **Playback**: Smart auto-play, Preferred quality, If that quality isn't available, Skip sources above, Prefer instant
   sources, Source list (TV, Windows), Next episode, Skip Intro button, Skip automatically, Up Next, New episode alerts,
   Who's watching? and Mini player (phones), Skip forward and back (TV)
2. **Picture & sound**: what the device's player offers (Dolby Vision, Start-up buffer, Audio format, Audio sync on TV;
   Hardware decoding, Surround sound on Windows)
3. **Downloads** (phones)
4. **Languages & subtitles**: Audio language, Subtitles, Subtitle size
5. **Account**: sign in or out, Who's watching, Cast photos (TMDB key)
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

## Rules that are not about looks

- Only http(s) and debrid sources. No torrents.
- Sources stay out of sight unless "Source list" is set to Shown.
- The bitrate limit ("Skip sources above") always stays a setting.
