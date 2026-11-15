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
| bg | `#0B0B0B` | Flat pages (Search, Library, Settings). The iPhone uses true black instead |
| card | `#161616` | Rows, cards, poster placeholders |
| raised | `#262626` | Chips, buttons and fields that sit on a card or page |
| line | `#333333` | Dividers, empty progress tracks |
| text | `#FFFFFF` | Titles and anything focused-on-dark |
| body | `#D6D6D6` | Paragraphs and help text |
| dim | `#B3B3B3` | Secondary labels, values, inactive tabs |
| faint | `#8C8C8C` | Hints and placeholders |
| focus | `#FFFFFF` | The focused or hovered row or button (text on it is on-focus `#111111`, secondary `#444444`) |

Profile colours, in order: `#E60A14`, `#2178F2`, `#FABA1A`, `#29AD61`, `#8C4DD9`, `#F27326`.

## Type and shape

- Font: Plus Jakarta Sans (the iPhone uses the system font).
- Focus is shown by turning the row or button white with dark text, never by an outline alone.
- Corners: 8 to 10 on rows, keys and buttons, fully round on chips and avatars.

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
| Skip Intro, Skip Recap, Skip Credits, Next Episode | |
| Who's watching | Switch user |

Tabs, in order: Home, Movies, Shows, Search, Library, Addons, Settings (phones fold some of these into Home and Settings).

Search filter: Both, Movies, Shows. Empty search shows "Recent searches" with "Clear recent searches" as the last row.

## Settings

Sections, in order, with the same labels and help text everywhere a setting exists:

1. **Playback**: Smart auto-play, Preferred quality, If that quality isn't available, Skip sources above, Prefer instant
   sources, Source list, Next episode, Skip Intro button, Skip automatically, Next Episode button, Skip forward and back
2. **Picture & sound**: what the device's player offers (Dolby Vision, Start-up buffer, Audio format, Audio sync on TV;
   Hardware decoding, Surround sound on Windows)
3. **Downloads** (phones)
4. **Languages & subtitles**: Audio language, Subtitles, Subtitle size
5. **Account**: sign in or out, Who's watching
6. **Addons**
7. **About**: Updates, and the version as "Codio 1.2.3 for Android TV / Android / iPhone / Windows"

Choices read as plain values ("On", "Off", "Plays automatically", "4 seconds (balanced)"). The canonical wording is in
`android/src/com/codio/tv/ui/Setting.java`.

## Rules that are not about looks

- Only http(s) and debrid sources. No torrents.
- Sources stay out of sight unless "Source list" is set to Shown.
- The bitrate limit ("Skip sources above") always stays a setting.
