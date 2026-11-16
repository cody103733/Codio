# Music in Codio

Codio's Music tab gets artists, albums, track lists and cover art from [MusicBrainz](https://musicbrainz.org) and the Cover Art Archive.
The audio comes from **music addons**: small HTTP servers that follow Eclipse Music's addon protocol, documented at
<https://eclipsemusic.app/docs>. You add one by pasting its link in Music > Addons. Codio ships no addon and no audio.

Playing a song asks each addon in your order: the addon the song came from, then `/resolve-isrc`, then `/resolve`, then a strict
search. A doubtful match counts as a miss. A 503 with `Retry-After` is waited out.

- `reference-addon/server.js`: a test addon with made-up tone "songs" (no real audio). `node music/reference-addon/server.js`
  then add `http://127.0.0.1:7878` in Music > Addons.
- `fixtures/matching.json`: cases for the strict same-recording check.
- `test.js`: `node music/test.js` checks the test addon and, when TypeScript is installed (after a Windows web build), the matching cases.

Code: `windows/src/routes/Codio/music/` (screens, player, MusicBrainz and addon clients) and `windows/desktop/codio-music.js`
(the local server part: MusicBrainz with Codio's User-Agent, one request a second, a disk cache).
