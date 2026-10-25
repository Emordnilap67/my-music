# Carmen Musicum

**A free Android music player for the music you keep on your own phone.**
No account, no ads, no streaming, no limit on playlists - and nothing is
locked inside the app.

"I wanted a player with no playlist limits, no ads and no account, that keeps
my music on my phone. So I built one." - Emordnilap67

In Carmen Musicum (roughly Latin for "a song") your songs are ordinary MP3
files in ordinary folders. Each folder is a playlist. You can
open them in any file manager, copy them to a computer, or take them to a
new phone, and Carmen Musicum just plays them - beautifully.

Getting songs in is as easy as sharing: send a YouTube or YouTube Music
playlist (or a single song) to Carmen Musicum and it saves every song as an
MP3 with its cover picture, tags and lyrics. Or copy in music you already have.

*Inside the app, the home screen greets you as **Repertorium**. Formerly
known as MY MUSIC.*

## Why you might like it

- **Your music stays yours.** Playlists are folders, songs are MP3s, and the
  app never needs the internet to play them.
- **Add music by sharing.** Share a YouTube playlist and it becomes a
  playlist here, or pour it into one you already have. New songs added to
  the YouTube playlist arrive by themselves.
- **Lyrics that keep time.** Timed lyrics light up word by word, there's a
  full-screen **karaoke** mode, and badly timed lyrics are caught and
  replaced automatically.
- **A player worth looking at.** The record sits inside a ring of light that
  dances to the music - bass, voice and treble each move their own part - in
  colours taken from each song's own picture.
- **Made for big libraries.** Thousands of songs in one playlist, sorted the
  way Spotify sorts, with a shuffle that never repeats a song until it has
  played them all.
- **Free and open source** (GPL-3.0).

## Install

1. On your phone, open the [Releases page](../../releases/latest) and tap
   `CarmenMusicum-<version>.apk` to download it.
2. Open the download. If Android asks, let your browser or Files app install
   apps, then go back and tap **Install**.
3. Play Protect may warn that it doesn't know the app, because it isn't from
   the Play Store. Tap **More details**, then **Install anyway**.

You need Android 8 or newer. The built-in downloader needs a 64-bit ARM phone,
which covers almost every phone made since 2017.

## First run

1. Allow **Music and audio**, so Carmen Musicum can see your songs, and
   **Notifications**, for the player controls and download progress.
2. Tap **Music folder** and pick or make a folder for your music, for example
   `Music/Carmen Musicum`. Each folder inside it is a playlist.
3. Tap **Add songs**, or share a link to Carmen Musicum from YouTube or
   YouTube Music.

## A quick tour

**The home screen** shows your playlists as square cards, nine to a page -
swipe sideways for more. Each card shows the playlist's picture (or its first
song's), ringed in that picture's colours. The search box finds any song in
any playlist. **Hold a playlist's card** for its menu: Play, Shuffle, Share
playlist, Change picture, Check YouTube for new songs, Check for wrong audio
and Delete playlist.

**Inside a playlist**, tap a song to play it. The **...** button opens the
menu for the song that's playing: favourite, hide, wrong song, wrong lyrics,
share, add to or move to another playlist, play next, add to queue, delete.

**The player** opens when you tap the bar at the bottom (back, play, next,
in the song's own colours). The **music note** shows the lyrics, the
**microphone** opens karaoke.

## What it does

- **Playlists are folders.** Every folder of songs inside your music folder
  shows up as a playlist - empty ones too, ready for songs - so you can see
  and manage them in any file manager.
- **Add songs from YouTube.** Share a playlist from YouTube or YouTube Music
  and tap Carmen Musicum, then pick where its songs go:
  - **one of your playlists** - songs you already have there aren't
    downloaded again, and that playlist keeps following its own YouTube
    playlist;
  - **its own playlist**, named after the YouTube one; or a **new playlist**.

  Every song is listed at once and turns playable as soon as it's in, saved
  as an MP3 with its cover picture and tags, with the download's progress
  shown song by song. Share a single song and you pick which playlist it goes
  in. Share a playlist again later and only the new songs come in.
- **YouTube's speed limit:** YouTube only lets a few hundred songs an hour
  through. When it pauses the downloads, Carmen Musicum waits and carries on by
  itself.
- **New songs by themselves:** every playlist you shared from YouTube is
  checked for new songs every 6 hours (and when you tap **Update library**);
  anything you add to the YouTube playlist comes in on its own. Songs on
  your phone are never removed. The check can be turned off per playlist.
- **Songs already on your phone:** Add songs, then Pick songs on this phone.
- **Songs gone from YouTube:** when a song in a shared playlist has been
  removed from YouTube (or made private or age-restricted), Carmen Musicum
  looks for another upload of the same recording by itself - same name and
  artist, not a remix or live version, length within 4 seconds. Anything less
  certain waits under **Find another copy**, where blue means about the same
  length.
- **Lyrics:** tap the music note in the player. Timed lyrics light up word
  by word as they're sung; tap a line to jump there, and **Sooner** /
  **Later** nudge the timing for that song. They come from
  [LRCLIB](https://lrclib.net); if it has nothing, Carmen Musicum looks in the
  song file, the video's description and the video's captions. Words found
  without times get them from the video's captions. **Search by name** fixes
  a wrong match.
- **Lyrics that are checked:** LRCLIB is written by its users, and now and
  then an upload is timed to a music video's intro - every line seconds late.
  Carmen Musicum compares the lyrics it gets with the library's other uploads
  of the same song and length, and when those agree with each other and not
  with the first answer, it takes a well-timed one instead. Lyrics saved
  before are checked once, the next time their song plays.
- **Karaoke:** full screen over the song's blurred picture - the line being
  sung big, each word filling in as it's sung, the next line waiting below,
  and three dots counting down before the singing starts again.
- **Lyrics saved in the song:** lyrics Carmen Musicum finds are written into
  the MP3 itself, in the background and never into the song that's playing.
  They go wherever the file goes and never need the internet again. The
  sound, picture and other tags stay exactly as they were.
- **Sound:** every song at about the same loudness (measured once, the way
  broadcasters do it, with a limiter so nothing distorts), gapless
  crossfade that starts where a song's music really ends, and an equalizer in
  plain words - "more bass", not "60 Hz". Home screen > **Sound**.
- **Queue:** **Play next** or **Add to queue** from the song menu. The player
  always shows what's **Up next**; tap it to see or change the queue.
- **Favourites:** tap the heart in the player. The heart filter in any
  playlist lists your favourites from every playlist.
- **Sort the way Spotify does:** Recently played, Most played, Recently
  added, Title or Artist, each with its order flipped. A song counts as
  played after 30 seconds.
- **Shuffle that never repeats:** a shuffle plays every song in the playlist
  once before any song comes round again - even across restarts - and two
  copies of the same song count as one.
- **Move and tidy:** move a song to another playlist (its plays and heart go
  with it; after the first move, the same playlist is one tap away), delete a
  playlist when you're done sorting it, hide or delete songs.
- **Wrong audio check:** hold a playlist's card > **Check for wrong audio**
  finds songs that play a different song than the name says (common with
  files saved by title from other downloaders) and downloads the right ones.
  One song playing the wrong thing: song menu > **Plays the wrong song**.
- **Share a playlist** as text - its name, its YouTube link and every song -
  to Messages, email or Drive.
- **Backup and new phone:** tap **Backup** on the home screen. Carmen Musicum
  keeps a backup inside your music folder (`Carmen Musicum backup`): play
  counts, hidden songs, favourites, playlist pictures, which YouTube playlist
  goes where, lyrics fixes, your sort order and a list of every song, plus a
  copy of the app. It updates by itself when you close the app. On a new
  phone, copy the music folder over, install, tap **Music folder** and pick
  it, then **Backup > Restore**. **Get songs back** downloads any songs that
  didn't come along.
- **The player:** a black record inside a ring of light that dances to the
  song - the bass sends echoes out on every beat, a ring of glowing particle
  bars follows the music (bass at the bottom, voice at the sides, treble at
  the top), the voice ripples the inner wave and the high notes make a cloud
  of stars drift and twinkle. A spiral galaxy turns slowly behind it. Every
  colour comes from the song's own picture, and the whole look fades into
  the next song's. The ring runs on a thread of its own, so taps and
  scrolling stay instant.
- **Big playlists stay quick:** thousands of songs draw in the background
  while you use the app, and songs arriving from a download never hold up a
  tap.
- **Also:** a neon home-screen widget in each song's own colours, from a
  one-row strip up to a big card; controls in the notification and on the
  lock screen; shuffle and repeat; long "Artist - Song" names show just the
  song, with the artist below; pick your own picture for a playlist.
- **If something stalls:** should the app ever freeze for more than a couple
  of seconds, what it was doing is written to
  `Carmen Musicum backup/.freezes.txt` - attach that file to a bug report.

## Permissions

| Permission | Why |
|---|---|
| Music and audio | to read the songs on your phone |
| Notifications | player controls and download progress |
| Record audio | Android's name for letting an app read the sound it is playing itself, which the moving ring needs. Carmen Musicum never opens the microphone. Say no and the ring stays still. |
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

Carmen Musicum is free software under the **GNU GPL v3** (see `LICENSE`). It
includes or downloads:

- [youtubedl-android](https://github.com/JunkFood02/youtubedl-android)
  (GPL-3.0): Python and FFmpeg built for Android
- [yt-dlp](https://github.com/yt-dlp/yt-dlp) (Unlicense), [FFmpeg](https://ffmpeg.org)
  (GPL) and [Python](https://python.org) (PSF licence)
- the Cinzel and Outfit fonts from [Google Fonts](https://github.com/google/fonts)
  (SIL Open Font License 1.1)
- lyrics from [LRCLIB](https://lrclib.net)

Only download music you're allowed to keep.
