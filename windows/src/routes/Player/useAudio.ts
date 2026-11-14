// Copyright (C) 2017-2026 Smart code 203358507

import { useCallback, useEffect, useRef } from 'react';
import { languages } from 'stremio/common';

type Args = {
    player: Player,
    video: {
        state: {
            stream: unknown | null,
            audioTracks: AudioTrack[],
            selectedAudioTrackId: string | null,
        },
        setAudioTrack: (id: string) => void,
    },
    settings: Settings,
    streamStateChanged: (state: Partial<StreamState>) => void,
};

// Tags that say nothing about the language.
const UNKNOWN = ['und', 'unk', 'unknown', 'mul', 'zxx', 'mis', 'qaa', 'none'];

const byName = (word: string) => languages.all.find((language) =>
    [language.name, language.local].some((name) => typeof name === 'string' && name.toLowerCase().split(/[,;]\s*/).includes(word))
);

// The language code for a tag like "en", "eng", "en-US" or "English" (mpv passes whatever the file says).
const normalizeLanguage = (language?: string | null) => {
    if (!language) {
        return undefined;
    }

    const value = language.trim().toLowerCase();
    if (value.length === 0 || UNKNOWN.includes(value)) {
        return undefined;
    }

    const base = /^[a-z]{2,3}[-_]/.test(value) ? value.split(/[-_]/)[0] : value;
    return (languages.find(value) ?? languages.find(base) ?? byName(value))?.code;
};

// The language a track's name mentions, e.g. "English - DTS-HD MA 5.1" or just "ENG". Inside a longer name only
// language names count: short codes are too easy to misread there.
const languageFromLabel = (label?: string | null) => {
    if (!label) {
        return undefined;
    }

    const words = label.toLowerCase().split(/[^\p{L}]+/u).filter((word) => word.length >= 3);
    for (const word of words) {
        const language = (words.length === 1 ? languages.find(word) : undefined) ?? byName(word);
        if (language) {
            return language.code;
        }
    }

    return undefined;
};

const trackLanguage = (track: AudioTrack) => normalizeLanguage(track.lang) ?? languageFromLabel(track.label);

// Commentary and audio-description tracks are only picked when nothing else is in the language.
const isExtra = (track: AudioTrack) => /comment|descriptive|audio description|visually impaired/i.test(track.label ?? '');

const findPreferredTrack = (
    tracks: AudioTrack[],
    selectedId: string | null,
    savedTrack: AudioTrackState | undefined,
    globalLanguage: string | null,
) => {
    const preferredLanguage = normalizeLanguage(savedTrack?.language);
    const saved = tracks.find((track) => track.id === savedTrack?.id);

    if (saved && (!preferredLanguage || trackLanguage(saved) === preferredLanguage)) {
        return saved;
    }

    const selected = tracks.find((track) => track.id === selectedId);
    const languageOrder = [preferredLanguage, normalizeLanguage(globalLanguage)];
    for (const language of languageOrder) {
        if (!language) {
            continue;
        }

        // The file's order doesn't matter: the main track in the language wins over the first track.
        const matching = tracks.filter((track) => trackLanguage(track) === language);
        const main = matching.filter((track) => !isExtra(track));
        const candidates = main.length > 0 ? main : matching;
        const track = selected && candidates.includes(selected) ? selected : candidates[0];
        if (track) {
            return track;
        }
    }

    return undefined;
};

const useAudio = ({ player, video, settings, streamStateChanged }: Args) => {
    const videoRef = useRef(video);
    const trackSelectionLocked = useRef(false);
    const appliedTrack = useRef<string | null>(null);

    videoRef.current = video;

    const selectTrack = useCallback((id: string) => {
        const currentVideo = videoRef.current;
        const track = currentVideo.state.audioTracks.find((track) => track.id === id);
        if (!track) {
            return;
        }

        const language = trackLanguage(track);
        trackSelectionLocked.current = true;
        appliedTrack.current = id;
        currentVideo.setAudioTrack(id);
        streamStateChanged({ audioTrack: { id, ...(language ? { language } : {}) } });
    }, [streamStateChanged]);

    useEffect(() => {
        trackSelectionLocked.current = false;
        appliedTrack.current = null;
    }, [video.state.stream]);

    useEffect(() => {
        if (trackSelectionLocked.current || video.state.stream === null) {
            return;
        }

        const track = findPreferredTrack(
            video.state.audioTracks,
            video.state.selectedAudioTrackId,
            player.streamState?.audioTrack,
            settings.audioLanguage,
        );
        if (!track) {
            appliedTrack.current = null;
            return;
        }

        if (appliedTrack.current === track.id) {
            return;
        }

        videoRef.current.setAudioTrack(track.id);
        appliedTrack.current = track.id;
    }, [
        player.streamState,
        settings.audioLanguage,
        video.state.audioTracks,
        video.state.selectedAudioTrackId,
        video.state.stream,
    ]);

    return selectTrack;
};

export default useAudio;
