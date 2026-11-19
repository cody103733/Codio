// Codio for Windows shares its engine and player code with ../web. This folder holds only what Windows changes or
// adds; this script lays ../web and then this folder over each other in .app, which is what gets built.
//
//   node windows/assemble.js        then, in windows/.app:  pnpm install --frozen-lockfile  &&  pnpm build
//
// To change a file that is not in this folder yet, copy it here from ../web (same path) and edit the copy.
const fs = require('fs');
const path = require('path');

const here = __dirname;
const app = path.join(here, '.app');
const WEB_SKIP = ['node_modules', 'build', 'codio-build', '.dockerignore', 'Dockerfile', 'CODE_OF_CONDUCT.md', 'CONTRIBUTING.md', path.join('assets', 'images', 'stremio_symbol.png'),
    path.join('assets', 'images', 'splash')]; // phone start-up pictures, not used on Windows
const OWN_SKIP = ['.app', 'node_modules', 'build', 'dist', 'desktop', 'assemble.js'];

// Entry by entry, because this folder cannot be copied into its own .app in one go.
const copy = (from, skip) => {
    for (const name of fs.readdirSync(from)) {
        if (skip.includes(name)) continue;
        fs.cpSync(path.join(from, name), path.join(app, name), {
            recursive: true,
            filter: (source) => !skip.includes(path.relative(from, source)),
        });
    }
};

// Start clean but keep installed packages, so a second run is quick.
fs.mkdirSync(app, { recursive: true });
for (const name of fs.readdirSync(app)) {
    if (name !== 'node_modules') fs.rmSync(path.join(app, name), { recursive: true, force: true });
}
copy(path.join(here, '..', 'web'), WEB_SKIP);
copy(here, OWN_SKIP);
// First-launch access password (lock/README.md): the lock file when there is one, otherwise null (no lock).
const lockFile = path.join(here, '..', 'lock', 'access-lock.json');
fs.writeFileSync(path.join(app, 'src', 'routes', 'Codio', 'access-lock.json'), fs.existsSync(lockFile) ? fs.readFileSync(lockFile) : 'null\n');
console.log('Codio for Windows assembled in ' + app);
