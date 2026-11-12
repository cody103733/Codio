// Copyright (C) 2017-2025 Smart code 203358507

import React from 'react';
import routes from 'stremio/routes';
import { PLAYER_PATH, META_DETAILS_PATH, LEGACY_META_DETAILS_PATH } from './getBackFallback';

export default [
    {
        path: '/intro',
        view: 1,
        element: <routes.Codio.Intro />,
    },
    {
        path: '/discover/:transportUrl?/:type?/:catalogId?',
        view: 1,
        element: <routes.Codio.Discover />,
    },
    {
        path: '/movies',
        view: 1,
        element: <routes.Codio.Movies />,
    },
    {
        path: '/series',
        view: 1,
        element: <routes.Codio.Series />,
    },
    {
        path: '/preferences',
        view: 1,
        element: <routes.Codio.Settings />,
    },
    {
        path: '/library/:type?',
        view: 1,
        element: <routes.Codio.Library />,
    },
    {
        path: '/calendar/:year?/:month?',
        view: 1,
        element: <routes.Codio.Library />,
    },
    {
        path: '/continuewatching/:type?',
        view: 1,
        element: <routes.Codio.Library />,
    },
    {
        path: '/search',
        view: 1,
        element: <routes.Codio.Search />,
    },
    {
        path: META_DETAILS_PATH,
        view: 2,
        element: <routes.Codio.Detail />,
    },
    {
        path: LEGACY_META_DETAILS_PATH,
        view: 2,
        element: <routes.Codio.Detail />,
    },
    {
        path: '/addons/:type?/:transportUrl?/:catalogId?',
        view: 1,
        element: <routes.Codio.Addons />,
    },
    {
        path: '/settings',
        view: 1,
        element: <routes.Codio.Settings />,
    },
    {
        path: PLAYER_PATH,
        view: 4,
        element: <routes.Player />,
    },
    {
        path: '/',
        view: 0,
        element: <routes.Codio.Home />,
    },
    {
        path: '*',
        view: 1,
        element: <routes.NotFound />,
    },
];
