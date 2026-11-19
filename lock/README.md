# Access lock

When `access-lock.json` is in this folder, every Codio app (Windows, Android TV, Android phone, iPhone) asks for an access password the first time it opens. Once the right password is typed, that device stays unlocked.

- `access-lock.json` holds only a random salt and a PBKDF2-SHA256 hash of the password, never the password.
- Make or change it: `node lock/make-lock.js` (asks for the password). Changing the password locks devices again until the new one is typed.
- **Remove the lock:** delete `access-lock.json` and rebuild. With no file, the apps open straight away. The lock code can stay; it does nothing without the file.

Where each app reads it:
- Android TV and phone: `android/build.sh` copies it into the APK assets; `android/src/com/codio/tv/LockActivity.java`.
- iPhone: bundled through `ios/project.yml`; `ios/Codio/UI/AccessLock.swift`.
- Windows: `windows/assemble.js` copies it into the web build; `windows/src/routes/Codio/AccessLock.tsx`.

The source is public, so this only keeps out casual users: anyone who builds Codio from source can leave the file out.
