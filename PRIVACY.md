# Privacy

Dream Log records you in bed, turns what you say into words, and keeps both on the phone. It
never sends any of it anywhere, because it has no way to send anything anywhere.

That is the whole policy. The rest of this page is the evidence for it, because a privacy
policy that cannot be checked is just a promise.

## What it asks for

`app/src/main/AndroidManifest.xml` declares:

```
android.permission.RECORD_AUDIO
android.permission.FOREGROUND_SERVICE
android.permission.VIBRATE
android.permission.WAKE_LOCK
```

The microphone is the only one that touches anything of yours. It is used only while a
recording is running: from the press of a volume key to the next press, or to twenty seconds of
quiet, or to five minutes at the longest. Armed and waiting, the app is listening for the
volume key, not to the room.

There is **no `INTERNET` permission**. Without it Android will not let the app open a network
connection, so nothing it records or writes down can leave the phone even by accident, and no
promise from me is load-bearing. The speech recognition is whisper.cpp, running on the phone's
own processor with a model packed inside the app. It is not a service.

## What is stored, and where

Two files per dream, in the app's own private storage:

```
files/dreams/20260926-031204.wav   the recording
files/dreams/20260926-031204.txt   what the phone heard in it
```

The file name is when the recording began. That is all of it: no account, no settings, no
log of when the app was armed.

Deleting a dream deletes both files. Uninstalling the app deletes all of them.

`android:allowBackup="false"` is set, so none of it goes to a cloud backup.

## Saving the words to a file

"Save the words to a file", at the foot of the log, writes every dream's words into one text
file, in a place you choose with the phone's own save screen: Downloads, say, so you can copy it
to a computer. It holds what was heard and when, but not the recordings. Once saved, that file
is outside the app, and deleting a dream or uninstalling the app does not delete it. The app
writes it only when you press the row, and it needs no permission to do it, because you pick
the place yourself.

## No third-party services

No analytics, no advertising, no crash reporting. The dependencies are AndroidX and Jetpack
Compose, Mudita's [MMD](https://github.com/mudita/MMD) design system, and whisper.cpp, which
is compiled into the app from source in this repository.

## Verifying this yourself

You do not have to take any of it on trust:

- The source is at <https://github.com/wanderwildwood/yumecho> and each release is tagged.
- `grep uses-permission app/src/main/AndroidManifest.xml` returns the four lines above.
- Any APK can be checked with `aapt dump permissions` or by opening it as a zip. It will also
  list `com.wanderwildwood.yumecho.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, which AndroidX
  adds to every app that listens for a system broadcast. It is the app's own, and no other app
  can hold it.

## Changes

If this ever stops being true, this file changes in the same commit as the code that changed
it, and the release notes will say so plainly.
