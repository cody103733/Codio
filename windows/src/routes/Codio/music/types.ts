// Music: the shapes the Music screens share.

export type MusicTrack = {
    // The id the track has where it came from: an addon's own id, or `mbid:<recording id>` from MusicBrainz.
    id: string,
    title: string,
    artist: string,
    album?: string | null,
    durationMs?: number | null,
    artworkURL?: string | null,
    isrc?: string | null,
    // MusicBrainz recording id, when known.
    mbid?: string | null,
    // MusicBrainz id of the (first) artist, when known; discovery uses it.
    artistMbid?: string | null,
    // The addon (its base link) this track came from. Playing asks that addon first.
    addon?: string | null,
    // A direct audio link the addon gave with the track.
    streamURL?: string | null,
};

export type MusicArtist = {
    id: string,
    name: string,
    detail?: string | null,
    artworkURL?: string | null,
    addon?: string | null,
};

export type MusicAlbum = {
    id: string,
    title: string,
    artist: string,
    year?: string | null,
    kind?: string | null,
    artworkURL?: string | null,
    addon?: string | null,
};

export type MusicAddon = {
    // The addon's base link, without manifest.json. A personal token in the link is part of it.
    url: string,
    manifest: any,
};

export type SearchResults = {
    artists: MusicArtist[],
    albums: MusicAlbum[],
    tracks: MusicTrack[],
};

export const formatTime = (seconds: number) => {
    if (!isFinite(seconds) || seconds < 0) return '0:00';
    const s = Math.floor(seconds);
    return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
};
