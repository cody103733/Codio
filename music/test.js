// Checks the reference addon and, if esbuild can be found, the matching rules in the app's own code.
// Run with `node music/test.js` from the repo root.

'use strict';

const http = require('http');
const path = require('path');
const assert = require('assert');

const get = (port, p) => new Promise((resolve, reject) => {
    http.get({ host: '127.0.0.1', port, path: p }, (res) => {
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () => {
            const body = Buffer.concat(chunks);
            let json = null;
            try { json = JSON.parse(body.toString()); } catch (_e) { /* audio */ }
            resolve({ status: res.statusCode, headers: res.headers, body, json });
        });
    }).on('error', reject);
});

(async () => {
    const server = require('./reference-addon/server.js');
    await new Promise((r) => server.listen(0, '127.0.0.1', r));
    const port = server.address().port;

    const manifest = (await get(port, '/manifest.json')).json;
    assert.strictEqual(manifest.id, 'app.codio.reference-addon');
    assert(manifest.resources.includes('resolve'));

    const search = (await get(port, '/search?q=tone')).json;
    assert.strictEqual(search.tracks.length, 2);

    assert.strictEqual((await get(port, '/resolve-isrc?isrc=ZZCOD2600004')).json.trackId, 't4');
    assert.strictEqual((await get(port, '/resolve-isrc?isrc=NOPE')).status, 404);
    assert.strictEqual((await get(port, '/resolve?title=Quiet%20Hours&artist=The%20Sine%20Waves')).json.item.id, 't4');
    assert.strictEqual((await get(port, '/resolve?title=Nothing&artist=Nobody')).json.item, null);

    const stream = (await get(port, '/stream/t1')).json;
    const audio = await get(port, stream.url.replace(`http://127.0.0.1:${port}`, ''));
    assert.strictEqual(audio.status, 200);
    assert.strictEqual(audio.body.slice(0, 4).toString(), 'RIFF');

    const first = await get(port, '/stream/t3');
    assert.strictEqual(first.status, 503);
    assert.strictEqual(first.headers['retry-after'], '2');

    assert.strictEqual((await get(port, '/album/a1')).json.tracks.length, 3);
    assert.strictEqual((await get(port, '/catalog/songs')).json.items.length, 4);
    assert.strictEqual((await get(port, '/catalog/songs?skip=100')).json.items.length, 0);
    console.log('reference addon: ok');
    server.close();

    // The matching rules, compiled with TypeScript when it is around (the Windows web build has it).
    let ts;
    try { ts = require(require.resolve('typescript', { paths: [path.join(__dirname, '../windows/.app'), path.join(__dirname, '../web'), process.cwd()] })); } catch (_e) { /* not here */ }
    if (!ts) {
        console.log('matching rules: skipped (typescript not found)');
        return;
    }
    const source = require('fs').readFileSync(path.join(__dirname, '../windows/src/routes/Codio/music/match.ts'), 'utf8');
    const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2019 } }).outputText;
    const m = { exports: {} };
    // match.ts only needs the addon calls at run time; the check itself is pure.
    new Function('module', 'exports', 'require', js)(m, m.exports, () => ({}));
    const fixtures = require('./fixtures/matching.json');
    fixtures.cases.forEach((c) => {
        assert.strictEqual(m.exports.isSameRecording({ id: 'w', ...c.wanted }, { id: 'c', ...c.candidate }), c.same, c.name);
    });
    console.log(`matching rules: ${fixtures.cases.length} cases ok`);
})().catch((e) => { console.error(e); process.exit(1); });
