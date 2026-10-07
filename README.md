# MY MUSIC

A music player for Android for the songs on your own phone. Your playlists
are plain folders, there's no account and no ads, and nothing streams. Add
music by sharing a YouTube or YouTube Music link to the app, or copy in MP3s
you already have.I made MY MUSIC, a free Android music player for the songs on your own phone

I wanted a player with no playlist limits, no ads and no account, that keeps my music on my phone. So I built one.

What it does:
- Your playlists are just folders on your phone, so nothing is locked inside the app
- Share a YouTube or YouTube Music playlist to MY MUSIC and it becomes a playlist in the app. Every song shows up straight away and fills in as it downloads (MP3 with cover art and tags)
- Share a single song and you pick which playlist it goes in
- If YouTube slows the downloads down, it waits and carries on by itself
- Timed lyrics: tap a line to jump to that part of the song
- A spectrum ring around the record that moves with the music, in each song's own colours
- Sort by Recently played, Most played, Recently added, Title or Artist (A to Z or Z to A)
- Home-screen widget, lock-screen controls, shuffle and repeat

It's free and open source (GPL-3.0). Android 8 or newer, 64-bit phone.

Download: https://github.com/Emordnilap67/my-music/releases/latest
Code: https://github.com/Emordnilap67/my-music

Feedback and ideas welcome.

Made by **Emordnilap67**.

## Install

1. On your phone, open the [Releases page](../../releases/latest) and tap
   `MyMusic-<version>.apk` to download it.
2. Open the download. If Android asks, let your browser or Files app install
   apps, then go back and tap **Install**.
3. Play Protect may warn that it doesn't know the app, because it isn't from
   the Play Store. Tap **More details**, then **Install anyway**.

You need Android 8 or newer. The built-in downloader needs a 64-bit ARM phone,
which covers almost every phone made since 2017.

## First run

1. Allow **Music and audio**, so MY MUSIC can see your songs, and
   **Notifications**, for the player controls and download progress.
2. Tap **Music folder** and pick or make a folder for your music, for example
   `Music/MY MUSIC`. Each folder inside it is a playlist.
3. Tap **Add songs**, or share a link to MY MUSIC from YouTube or YouTube Music.

## What it does

- **Playlists are folders.** Every folder of songs inside your music folder
  shows up as a playlist, so you can see and manage them in any file manager.
- **Add songs from YouTube.** Share a playlist from YouTube or YouTube Music
  and tap MY MUSIC:
  - it becomes a playlist with the same name, and every song is listed at once;
  - each song turns playable as soon as it's in, saved as an MP3 with its
    cover picture and tags;
  - share a single song and you pick which playlist it goes in;
  - share a playlist again later and only the new songs come in.
- **YouTube's speed limit:** YouTube only lets a few hundred songs an hour
  through. When it pauses the downloads, MY MUSIC waits and carries on by
  itself.
- **Songs already on your phone:** Add songs, then Pick songs on this phone.
- **Lyrics:** tap the speech bubble in the player. Timed lyrics follow the
  song, and you can tap a line to jump there. They come from
  [LRCLIB](https://lrclib.net).
- **Moving ring:** the bars around the record move with the music, in colours
  taken from each song's picture.
- **The song playing now glows** in the playlist, with moving bars on its
  picture.
- **Sort the way Spotify does:** Recently played, Most played, Recently
  added, Title or Artist, each with its order flipped (A to Z or Z to A,
  newest or oldest first). A song counts as played after 30 seconds.
- **Also:**
  - shuffle and repeat;
  - a home-screen widget, plus controls in the notification and on the lock
    screen;
  - share a song's file, or add it to another playlist;
  - hide or delete songs;
  - pick your own picture for a playlist.

## Permissions

| Permission | Why |
|---|---|
| Music and audio | to read the songs on your phone |
| Notifications | player controls and download progress |
| Record audio | Android's name for letting an app read the sound it is playing itself, which the moving ring needs. MY MUSIC never opens the microphone. Say no and the ring stays still. |
| Alarms | to carry on downloading by itself after YouTube's pause |
| Internet | downloads, lyrics and downloader updates |

Folder access is asked for once, through Android's own folder picker, and
only for the folder you choose.

## Building it yourself

GitHub builds the APK on every tag (see `.github/workflows/release.yml`). On
a Linux PC with the Android SDK and JDK 17:

```
bash tools/build-ci.sh
```

The APK lands in `out/`. To sign releases with your own key, add two
repository secrets: `KEYSTORE_B64` (a PKCS12 keystore in base64, key alias
`release`) and `KEYSTORE_PASS`.

## Credits and licences

MY MUSIC is free software under the **GNU GPL v3** (see `LICENSE`). It
includes or downloads:

- [youtubedl-android](https://github.com/JunkFood02/youtubedl-android)
  (GPL-3.0): Python and FFmpeg built for Android
- [yt-dlp](https://github.com/yt-dlp/yt-dlp) (Unlicense), [FFmpeg](https://ffmpeg.org)
  (GPL) and [Python](https://python.org) (PSF licence)
- the Cinzel and Outfit fonts from [Google Fonts](https://github.com/google/fonts)
  (SIL Open Font License 1.1)
- lyrics from [LRCLIB](https://lrclib.net)

Only download music you're allowed to keep.
