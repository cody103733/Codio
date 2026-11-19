// Makes lock/access-lock.json from a password, so Codio asks for it on first launch.
// The password itself is never stored: only a random salt and a PBKDF2-SHA256 hash of it.
//   node lock/make-lock.js            (asks for the password, nothing is echoed or saved)
//   CODIO_PASSWORD=... node lock/make-lock.js
// To remove the lock: delete lock/access-lock.json and rebuild the apps.
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const ITER = 300000;

const write = (password) => {
    const p = password.trim().normalize('NFC');
    if (p.length < 6) { console.error('Use at least 6 characters.'); process.exit(1); }
    const salt = crypto.randomBytes(16);
    const hash = crypto.pbkdf2Sync(Buffer.from(p, 'utf8'), salt, ITER, 32, 'sha256');
    const out = { v: 1, kdf: 'pbkdf2-sha256', iter: ITER, salt: salt.toString('base64'), hash: hash.toString('base64') };
    fs.writeFileSync(path.join(__dirname, 'access-lock.json'), JSON.stringify(out, null, 2) + '\n');
    console.log('Wrote lock/access-lock.json');
};

if (process.env.CODIO_PASSWORD) write(process.env.CODIO_PASSWORD);
else {
    process.stdout.write('Password: ');
    const stdin = process.stdin;
    if (stdin.isTTY) stdin.setRawMode(true);
    let buf = '';
    stdin.on('data', (d) => {
        for (const ch of d.toString('utf8')) {
            if (ch === '\r' || ch === '\n') { process.stdout.write('\n'); if (stdin.isTTY) stdin.setRawMode(false); stdin.pause(); write(buf); return; }
            if (ch === '\u0003') process.exit(1);
            if (ch === '\u007f') buf = buf.slice(0, -1); else buf += ch;
        }
    });
}
