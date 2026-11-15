// Copyright (C) 2017-2023 Smart code 203358507

// Codio for Windows uses its own screens (./Codio) and the player; the other web screens are not built in.
const NotFound = require('./NotFound');
const Player = require('./Player');
const { default: Codio } = require('./Codio');

module.exports = {
    NotFound,
    Player,
    Codio
};
