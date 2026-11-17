// Makes vector.json: a box every Codio app must open (see SYNC.md). Run: node sync/make-vector.js
const { webcrypto: crypto } = require('crypto');
const fs = require('fs');
const path = require('path');
(async () => {
    const passcode = '  Codio sync ✓ 1  ';
    const salt = Uint8Array.from({ length: 16 }, (_, i) => i + 1);
    const iv = Uint8Array.from({ length: 12 }, (_, i) => 0xa0 + i);
    const iter = 600000;
    const items = { tmdbKey: { value: '0123456789abcdef0123456789abcdef', at: 1763380000000 }, listenBrainz: { value: { token: 'tok-123456', user: 'cody' }, at: 1763380000001 } };
    const base = await crypto.subtle.importKey('raw', new TextEncoder().encode(passcode.trim().normalize('NFC')), 'PBKDF2', false, ['deriveBits']);
    const key = new Uint8Array(await crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt, iterations: iter }, base, 256));
    const aes = await crypto.subtle.importKey('raw', key, 'AES-GCM', false, ['encrypt']);
    const plain = JSON.stringify({ v: 1, items });
    const data = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, aes, new TextEncoder().encode(plain)));
    const b64 = (b) => Buffer.from(b).toString('base64');
    const vector = { passcode, keyHex: Buffer.from(key).toString('hex'), plain, box: { v: 1, kdf: 'pbkdf2-sha256', iter, salt: b64(salt), iv: b64(iv), data: b64(data) } };
    fs.writeFileSync(path.join(__dirname, 'vector.json'), JSON.stringify(vector, null, 2) + '\n');
    console.log(vector.keyHex);
})();
