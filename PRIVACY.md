# Privacy

Dream Log records you in bed, turns what you say into words, and keeps both on the phone. It
never sends any of it anywhere unless you ask. There are two exceptions, both yours to make.
"Keep dreams in Notes" hands the words, not the recordings, to Notes on the same phone, which
keeps and syncs them as it does your other notes. "Nextcloud", under Settings, sends the words,
not the recordings, to your own Nextcloud server, and only after you sign in to it and turn
sending on.

The app goes online for two things only, and only if you ask: to download the listening model
for a language other than English (a download; nothing goes up), and to send the words of your
dreams to the Nextcloud you signed in to. With English and Nextcloud left off (the defaults) the
app never opens a connection at all.

That is the whole policy. The rest of this page is the evidence for it, because a privacy
policy that cannot be checked is just a promise.

## What it asks for

`app/src/main/AndroidManifest.xml` declares:

```
android.permission.RECORD_AUDIO
android.permission.INTERNET
android.permission.FOREGROUND_SERVICE
android.permission.VIBRATE
android.permission.WAKE_LOCK
```

The microphone is the only one that touches anything of yours. It is used only while a
recording is running: from the press of a volume key to the next press, or to twenty seconds of
quiet, or to five minutes at the longest. Armed and waiting, the app is listening for the
volume key, not to the room.

The speech recognition is whisper.cpp, running on the phone's own processor. It is not a
service. The English model is packed inside the app.

`INTERNET` is there for the other languages, since Dream Log 0.1.5, and, since 0.1.18, for the
Nextcloud sending described below. Choosing a language in the
settings downloads Whisper's multilingual model, about 60 MB, from Hugging Face, at an address
pinned to one fixed version of the file (`hearing/Speech.kt`). It is a plain download: the
request carries nothing of yours, and the app keeps the file only if its SHA-256 matches the one
written in the app. The download is `hearing/Speech.kt`, using `HttpURLConnection`; the Nextcloud
code is the `cloud/` folder, using OkHttp. Those are the only two places that touch the network.
Choosing English again deletes the file.

## What is stored, and where

Two files per dream, in the app's own private storage:

```
files/dreams/20260926-031204.wav   the recording
files/dreams/20260926-031204.txt   what the phone heard in it
```

The file name is when the recording began. That is all of it, and, if the setting below is on, a list of which dreams have been
handed to Notes and under what name: no account, no log of when the app was armed.

Deleting a dream deletes both files. Uninstalling the app deletes all of them.

`android:allowBackup="false"` is set, so none of it goes to a cloud backup.

## Saving the words to a file

"Save the words to a file", at the foot of the log, writes every dream's words into one text
file, in a place you choose with the phone's own save screen: Downloads, say, so you can copy it
to a computer. It holds what was heard and when, but not the recordings. Once saved, that file
is outside the app, and deleting a dream or uninstalling the app does not delete it. The app
writes it only when you press the row, and it needs no permission to do it, because you pick
the place yourself.

## Keeping dreams in Notes

"Keep dreams in Notes", the one setting, is off until you turn it on. On, each dream's words,
with its night, time and length, are handed to Notes, another app of mine on the same phone, as
a note in its `Dreams` folder. The recordings are not. From there they are Notes' to keep: if
Notes syncs to a Nextcloud or a folder, the words go there too, and deleting a dream in Dream
Log deletes its note. Turning the setting off leaves the notes already made where they are.

Dream Log hands them over through Notes' own provider on the phone, not over a network, and
Notes accepts them only from the released Dream Log. To see that it can, the manifest asks to
see Notes (`<package android:name="com.wanderwildwood.oboegaki"/>` in `<queries>`) and the apps
that act on selected text, such as the Dictionary. Neither is a permission.

## Sending the words to your Nextcloud

Off until you open Settings > Nextcloud, sign in, and turn on "Send dreams to Nextcloud".
Signing in alone sends nothing.

- **What goes:** each dream's words with its night, time and length, as a Markdown file named
  for when the recording began (`Dream Log/2026-10-05 0712.md`, the folder is yours to rename).
  Never a recording. Nothing else is sent: no device id, no log of when the app was armed.
- **Where:** only the server address you typed, over https. A plain `http://` address is
  refused, and Android blocks unencrypted traffic for this app as a whole. Nothing goes to the
  app's maker or anyone else.
- **Signing in:** Nextcloud's own login page in the browser (Login Flow v2). Your Nextcloud
  password is never typed into the app; Nextcloud hands the phone an app password of its own,
  which you can revoke in Nextcloud's security settings. A phone with no browser can be given
  an app password by hand.
- **Kept on the phone:** the server, login name and app password, in the app's private
  preferences, with the password sealed (AES-GCM) by a key in the Android Keystore that cannot
  be exported and is not backed up (`cloud/Secrets.kt`). If that key is lost the app behaves as
  signed out. `android:allowBackup="false"` keeps all of it out of cloud backups.
- **Kept on the server:** the files are ordinary text, readable by anyone who can open that
  Nextcloud account, and as protected as the rest of that account is. They are not encrypted by
  the app, so that Notes, a text editor or a new phone can read them.
- **Deleting:** with sending on, deleting a dream deletes its copy on the server. Turning
  sending off, or signing out, leaves the copies already there. Signing out also deletes the
  app password on the server.
- **Calls:** `PROPFIND`, `MKCOL`, `PUT` and `DELETE` on `remote.php/dav/files/<you>/<folder>/`,
  and Login Flow v2's `index.php/login/v2` and `ocs/v2.php/core/apppassword`.

## Reading the app's own stops

On a Kompakt, Settings can show a hint about DuraSpeed, the phone's background manager. To tell
whether the system has stopped the app since you said it was switched on, the app reads its own
exit history from Android (`ActivityManager`). That stays on the phone and is never sent.

## No third-party services

No analytics, no advertising, no crash reporting. The dependencies are AndroidX, OkHttp (for the Nextcloud calls) and Jetpack
Compose, Mudita's [MMD](https://github.com/mudita/MMD) design system, and whisper.cpp, which
is compiled into the app from source in this repository.

## Verifying this yourself

You do not have to take any of it on trust:

- The source is at <https://github.com/wanderwildwood/yumecho> and each release is tagged.
- `grep uses-permission app/src/main/AndroidManifest.xml` returns the lines above.
- Any APK can be checked with `aapt dump permissions` or by opening it as a zip. It will also
  list `com.wanderwildwood.yumecho.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, which AndroidX
  adds to every app that listens for a system broadcast. It is the app's own, and no other app
  can hold it.

## Changes

If this ever stops being true, this file changes in the same commit as the code that changed
it, and the release notes will say so plainly.
