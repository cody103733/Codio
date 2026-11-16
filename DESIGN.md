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

- Font: Plus Jakarta Sans on every app (`Theme.font` on the iPhone, which loads the same file the other apps use).
- Focus is shown by turning the row or button white with dark text, never by an outline alone.
- Corners: 8 to 10 on rows, keys and buttons, fully round on chips and avatars.
- Chips (Both / Movies / Shows, the Library and genre filters): red with white text when chosen, raised grey with white
  text otherwise. Never white, never outlined.
- Settings rows are cards (`card`, corner 10): the name in bold white on the left, what it's set to in `dim` on the right.
  Section headings are bold white and written as "Playback", not in capitals.

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

Tabs, in order: Home, Movies, Shows, Search, Library, Addons, Settings (phones fold some of these into Home and Settings).

Search filter: Both, Movies, Shows. Empty search shows "Recent searches" with "Clear recent searches" as the last row.

## Settings

Sections, in order, with the same labels and help text everywhere a setting exists:

1. **Playback**: Smart auto-play, Preferred quality, If that quality isn't available, Skip sources above, Prefer instant
   sources, Source list (TV, Windows), Next episode, Skip Intro button, Skip automatically, Up Next, New episode alerts,
   Mini player (phones), Skip forward and back (TV)
2. **Picture & sound**: what the device's player offers (Dolby Vision, Start-up buffer, Audio format, Audio sync on TV;
   Hardware decoding, Surround sound on Windows)
3. **Downloads** (phones)
4. **Languages & subtitles**: Audio language, Subtitles, Subtitle size
5. **Account**: sign in or out, Who's watching, When Codio opens (phones), Cast photos (TMDB key)
6. **Addons**
7. **About**: Updates, and the version as "Codio 1.2.3 for Android TV / Android / iPhone / Windows"

Choices read as plain values ("On", "Off", "Plays automatically", "4 seconds (balanced)"). The canonical wording is in
`android/src/com/codio/tv/ui/Setting.java`.

## Phones

Codio for iPhone and Codio for Android are the same screens, drawn to the same numbers (points on one are dp on the other).
`android/src/com/codio/tv/ui/Phone*.java` is the reference; `ios/Codio/UI` follows it. A change to one is made to both.

- **Tab bar**: Home, Search, Library, Settings. 58 high on `bg`, line-drawn icons (2 thick) over an 11 label; white with a
  short red line on top when chosen, `faint` otherwise.
- **Home**: the featured title's poster edge to edge (1.2 times as tall as the screen is wide), shaded dark at the top and
  to black at the bottom. Over its top: the wordmark, "Movies", "Shows", and the round avatar (34). At its bottom, centred:
  the title's logo (or its name, 28 bold), the genres on one line (13, `body`), then Play (white) and Info (glass) side by
  side. Below: Continue Watching, then a row per catalog. Rows are a 17 bold heading over posters 110 by 165, 8 apart,
  corner 6. A poster opens the title's page.
- **Search**: a `raised` field (corner 8, "Movies, shows…") with the avatar beside it, the Both / Movies / Shows chips,
  then "Recent searches" or the results three across (6 apart).
- **Library**: "Library" (24 bold) with the avatar, the chips All / Movies / Shows / Downloads, then the grid.
- **Movies / Shows** (from Home): a row of catalog chips, a row of genre chips, the grid.
- **Settings**: "Settings" (24 bold) with the avatar, then the sections below as card rows with a line of help (12, `dim`)
  inside each. Tapping a row offers its choices.

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
