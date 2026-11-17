# Sync passcode

Codio's private settings follow you to every device signed in to the same Stremio account, locked with a passcode only
you know. Codio's code is public and has no secret of its own: without the passcode nobody can open them, Stremio
included.

Same rules on every app: Windows `windows/src/routes/Codio/vault.ts`, Android TV and phone
`android/src/com/codio/tv/ui/Vault.java` (+ `VaultCrypto.java`), iPhone `ios/Codio/Store/Vault.swift`
(+ `VaultCrypto.swift`).

## What is synced

| Item | Value | Where it lives on the device |
| --- | --- | --- |
| `tmdbKey` | the TMDB API key, `""` for none | Windows localStorage `codio.tmdbKey`, Android pref `tmdb_key`, iPhone `tmdb_key` |
| `musicAddons` | `[{url, manifest}]`, in order | `codioMusicAddons` / `music_addons_v1` |
| `listenBrainz` | `{token, user}` or `null` | `codioListenBrainz` / iPhone keychain |
| `musicBrainzServer` | MusicBrainz mirror address, `""` for the default | `codioMusicSettings.mbBase` / `mbBase` / `music_mb_base` |

Unknown items in a box are kept as they are, so a newer app can add items without older ones dropping them.

## The box

A hidden library entry on the signed-in account: `_id` `codio:vault`, type `other`, `removed` and `temp` true (Stremio's
apps never show it). Its `name` is this JSON:

```
{"v":1,"kdf":"pbkdf2-sha256","iter":600000,"salt":"<16 bytes, base64>","iv":"<12 bytes, base64>","data":"<base64>"}
```

- Key: PBKDF2-HMAC-SHA256 of the passcode (leading/trailing spaces trimmed, Unicode NFC, UTF-8) with `salt` and `iter`
  rounds, 32 bytes. At least 6 characters.
- `data`: AES-256-GCM of the UTF-8 JSON `{"v":1,"items":{"<item>":{"value":...,"at":<ms>}}}`, the 16-byte tag
  appended (as WebCrypto and Java give it). No associated data. A new random `iv` on every write.
- `sync/vector.json` is a box every app must open (`node sync/make-vector.js` makes it; iOS CI runs `sync/check.swift`).
- Each write also empties the old unlocked TMDB entry, `codio:tmdb`. Apps no longer write that entry; they still read it
  when they have no key and the passcode is off.

The device keeps the derived key (never the passcode) and the salt: Windows localStorage `codio.sync`, Android
SharedPreferences `codio_sync`, iPhone keychain (`sync-passcode-key`) + UserDefaults `sync_state_v1`.

## Merging

For each account, the device remembers what each item looked like the last time it and the account agreed (a
fingerprint: the addon links in order, the ListenBrainz token, the trimmed text otherwise). On every sync, per item:

1. Not in the box yet: this device's value goes up.
2. First sync of this device with this account: music addons are joined (the account's, then the ones only this device
   had); for the rest the account's value wins unless it is empty.
3. Changed here since the last agreement: this device's value wins (so removals and sign-outs travel too).
4. Otherwise the account's value is taken.

Then the box is written back if anything differs from it. Sync runs when the account appears, a moment after a synced
setting changes here, every 10 minutes, and from Settings ("Sync now").

## Passcode states

- **Off**: enter a passcode. If the account already has a box, it must open it ("That's not the sync passcode");
  otherwise it becomes the new passcode.
- **Change passcode**: syncs with the old key, then writes the box again under a new salt and key. Other devices find a
  salt they don't know and show "Enter the new passcode".
- **Start over** (forgot it): a new box under a new passcode from this device's values, replacing the old one.
- **Turn off on this device**: forgets the key; the settings stay on the device.

Profiles are separate Stremio accounts, so each has its own box; a device uses the same passcode for all of them.
