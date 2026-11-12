// Copyright (C) 2017-2023 Smart code 203358507

const useModelState = require('stremio/common/useModelState');

// Codio for Windows has no streaming server: debrid / HTTP sources go straight to the
// player. Report "no server" right away so nothing waits for one or offers server features
// (casting, torrent statistics). The Android apps bring their own local server.
const NO_SERVER = typeof window !== 'undefined'
    && !!(window.chrome && window.chrome.webview)
    && !window.__codioAndroid;

const NO_SERVER_STATE = Object.freeze({
    selected: null,
    baseUrl: null,
    remoteUrl: null,
    settings: Object.freeze({ type: 'Err', content: 'Codio plays sources directly' }),
    torrent: null,
    statistics: Object.freeze({ type: 'Err', content: 'Codio plays sources directly' }),
    playbackDevices: null,
    networkInfo: null,
    deviceInfo: null,
    casting: null,
});

const useStreamingServer = () => {
    const state = useModelState({ model: 'streaming_server' });
    return NO_SERVER ? NO_SERVER_STATE : state;
};

module.exports = useStreamingServer;
