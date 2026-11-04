# Kid Local TV

A simple, kid-friendly video player for **Android TV**. It plays videos from shared folders on your home network (NAS, Windows PC or Mac) **without copying them to the TV**, so it works fine on TVs with little storage. You can also upload videos to the TV itself.

Once it's set up, the app opens straight into full-screen playback of a random video. The remote's arrow keys are the only controls there are.

<p align="center"><img src="docs/images/tv-shuffle.png" width="820" alt="Kid Local TV playing a video in shuffle mode"></p>

> **About the images:** The TV-screen pictures in this README are illustrations. They're drawn to match the app's real layouts, colours and text (`activity_setup.xml`, `activity_player.xml`, `strings.xml`), and the "videos" in them are placeholder art. The [web page](#2-add-videos-from-a-phone-or-computer) screenshot is a real capture of the page the app serves, connected to two test SMB shares.

---

## Features at a glance

| | |
|---|---|
| 🛠 **One-time setup** | On first launch the TV shows an address and QR code. Open it on any phone or computer on the same Wi-Fi. |
| 📡 **Network folders (SMB)** | Add `smb://nas/Videos` (or `\\nas\Videos`) and the TV **streams** from it. Nothing is copied, so the TV only stores a tiny file list. |
| 🎞 **Plays almost anything** | [LibVLC](https://www.videolan.org/vlc/libvlc.html) decodes MP4, MKV, AVI/DivX/Xvid, WMV, MPEG-2, HEVC, VP9, AV1, AC3/DTS audio and more, using hardware decoding when the TV supports the codec. |
| ▶️ **Instant playback** | Every launch after setup goes straight to full screen and starts a random video. |
| 🔀 **Shuffle mode** | ▲ or ▼ plays another random video from the *whole* library, whatever folder or file order. |
| 📺 **Series mode** | ▶ locks onto the current video's folder. ▲ / ▼ then step through its episodes in order. |
| 🔖 **Resume** | Each time ▲/▼ switches videos, the spot in the video you left is saved. That video resumes there next time. |

<p align="center"><img src="docs/images/diagram-flow.png" width="900" alt="Setup flow: first launch shows an address, add videos from a phone, later launches play immediately"></p>

---

## How it works

### 1. First launch: set up a video source

When the library is empty, the app opens the setup screen. The TV starts a small web server on port `8080` and shows its address and a QR code.

<p align="center"><img src="docs/images/tv-setup.png" width="820" alt="Setup screen with the address and QR code"></p>

The count updates live as videos are found. Once there's at least one video, **Start watching** becomes available. From then on the app skips this screen.

### 2. Add videos from a phone or computer

Open the address shown on the TV (or scan the QR code).

<p align="center"><img src="docs/images/web-import.png" width="560" alt="The web page served by the TV, with two network folders added"></p>
<p align="center"><sub>Real screenshot. The two shares listed are local test servers, which is why they show 127.0.0.1.</sub></p>

#### Network folders (recommended: uses no TV storage)

Paste a shared folder, and add a login if the share needs one. All of these forms work:

| You paste | Means |
|---|---|
| `smb://nas/Videos` | Whole share. Its top-level folders become series. |
| `smb://nas/Videos/Ocean Friends` | One folder. It becomes the series "Ocean Friends", and its sub-folders become their own series. |
| `\\nas\Videos\Ocean Friends` | Same, Windows style. `//nas/Videos` (macOS / Linux style) works too. |
| `smb://mum:secret@192.168.1.20:445/Videos` | Login and port inside the link. You can use the separate login fields instead. |

<p align="center"><img src="docs/images/diagram-network.png" width="900" alt="Network folders stream: the NAS share is read on demand through a loopback stream server into LibVLC"></p>

- **Nothing is copied.** The TV saves the link, the login and a small list of file names (a few KB). Each video is read from the share while it plays, and seeking and resume work as usual.
- **Fast start.** The app starts from the cached file list, so launch is instant. It then re-checks each share in the background (at most every 10 minutes) to pick up new episodes.
- **Share switched off?** Its videos are skipped, and anything else in the library keeps playing. If a whole series is offline, playback returns to shuffle mode.
- **Rescan / Remove** buttons are on the web page. Removing a network folder never touches the share.
- Works with SMB 2 and 3 shares: Windows, macOS file sharing, Samba, Synology, QNAP, TrueNAS and others. Guest shares need no login. NAS housekeeping folders such as `@eaDir` and `#recycle`, and hidden files, are ignored.
- The login is stored in the app's private storage on the TV, so the app can play the videos later. Re-adding a share with a wrong password keeps the previous, working login.

#### Copying to the TV (optional: uses TV storage)

- **Upload files or whole folders** from the browser. Type a *folder / series name* first, or pick a folder so each file's sub-folder becomes its series.
- **Download a direct `http://…/video.mp4` link** to the TV.
- See and delete what's stored on the TV in the last section of the page.

**Folders are series.** Files inside a folder play in *natural* name order, so `Episode 2` comes before `Episode 10`.

### 3. Every launch after: straight to playback

The app opens full screen and starts a random video in **shuffle mode**. A small card shows the current mode, the title, and where it sits in its folder, then fades after a few seconds.

<p align="center"><img src="docs/images/diagram-modes.png" width="900" alt="Shuffle mode jumps across the whole library; series mode steps through one folder"></p>

#### Shuffle mode

- ▲ and ▼ both pick **another random video from the entire library**, local and network videos alike.
- A "shuffle bag" makes sure every video plays once before any repeats, and the video that's playing never comes up again straight away.
- When a video ends, another random one starts.

#### Series mode

Press **▶** while something is playing to switch to series mode for that video's folder.

- **▲** goes to the **previous** episode and **▼** to the **next** one. Both wrap around at the ends of the folder.
- When an episode ends, the next one starts automatically.
- Press **◀** to go back to shuffle mode.

<p align="center"><img src="docs/images/tv-series.png" width="820" alt="Series mode on screen, with the 'Series mode: Ocean Friends' notice"></p>

### 4. Resume where you left off

<p align="center"><img src="docs/images/diagram-resume.png" width="900" alt="Position is saved when switching videos and restored later"></p>

- Every ▲ / ▼ switch saves the current position of the video **being left**.
- When that video plays again, in either mode, it starts from the saved spot. This works for network videos too.
- Positions within the first 5 s or the last 15 s aren't kept, so a finished video starts fresh.
- Leaving the app (Home or Back) also saves the current spot.

---

## Supported formats

Playback uses **LibVLC 3.7** (from VideoLAN, the VLC engine). For each video it tries the TV's hardware decoder (MediaCodec) first and falls back to software decoding (libavcodec) when the hardware can't handle the codec.

| Containers | `mp4 m4v mkv webm mov avi divx wmv asf flv f4v ts m2ts mts vob mpg mpeg m2v 3gp 3g2 ogv ogm rm rmvb dv mxf nut wtv dvr-ms ivf …` |
|---|---|
| Video codecs | H.264, H.265/HEVC, VP8, VP9, AV1, MPEG-1/2/4, DivX/Xvid, WMV 7/8/9, VC-1, Theora, RealVideo, ProRes, DV, MJPEG, and more |
| Audio codecs | AAC, MP3, AC3/E-AC3, DTS, FLAC, Opus, Vorbis, WMA, PCM, and more |

Software decoding of high-resolution HEVC or AV1 can be too slow on low-end TV boxes. If a file stutters, H.264 MP4 at 1080p is the safest choice. A file that can't be played is skipped with a short notice.

---

## Remote control

<p align="center"><img src="docs/images/diagram-remote.png" width="900" alt="Remote control reference"></p>

| Button | Shuffle mode | Series mode |
|---|---|---|
| ▲ Up | Random video | Previous episode in folder |
| ▼ Down | Random video | Next episode in folder |
| ▶ Right | Switch to series mode | (stays in series mode) |
| ◀ Left | (stays in shuffle mode) | Switch to shuffle mode |
| OK / Play-Pause | Pause / resume | Pause / resume |
| **Hold OK** or Menu | Open **Manage videos** | Open **Manage videos** |
| Channel ▲▼, ⏮ ⏭ | Same as ▲▼ | Same as ▲▼ |
| ⏪ ⏩ | Seek −/+ 10 s | Seek −/+ 10 s |

<p align="center"><img src="docs/images/tv-paused.png" width="640" alt="Paused playback"></p>

**Manage videos** (hold OK) reopens the setup screen and its web page, so you can add network folders or upload videos. Press *Back to watching* to carry on with the same video.

---

## Install on Android TV

### Get the APK

**Option A: GitHub Actions.** Every push runs [`.github/workflows/build.yml`](.github/workflows/build.yml), which runs the unit tests and builds the APKs. Download the `kid-local-tv-apk` artifact from the workflow run.

**Option B: build locally.** You need JDK 17 and the Android SDK, or Android Studio:

```bash
./gradlew assembleDebug
```

Because LibVLC includes native code, the build makes one APK per CPU type plus a universal one, in `app/build/outputs/apk/debug/`:

| APK | Use it for | Size (approx.) |
|---|---|---|
| `app-armeabi-v7a-debug.apk` | **Most Android TVs and sticks.** Many run 32-bit Android even on 64-bit chips. | ~30 MB |
| `app-arm64-v8a-debug.apk` | 64-bit TVs such as NVIDIA Shield, Chromecast with Google TV, newer boxes | ~30 MB |
| `app-universal-debug.apk` | Any device, if you're not sure | ~100 MB |
| `app-x86*-debug.apk` | Emulators / x86 boxes | ~30 MB |

If the device refuses an APK with "App not installed", try the other ARM build, or the universal one.

### Sideload it

1. On the TV, turn on **Developer options**: *Settings → Device Preferences → About*, then select *Build* 7 times. Then turn on **USB debugging** (or **Network debugging**).
2. From a computer on the same network:

   ```bash
   adb connect <tv-ip-address>
   ```

   ```bash
   adb install -r app-armeabi-v7a-debug.apk
   ```

3. **Kid Local TV** appears in the TV's apps row.

Alternatively, copy the APK to a USB stick or cloud drive and open it with a file manager app on the TV.

> The release build is signed with the debug key so it can be sideloaded as-is. Set up your own signing config before distributing it.

---

## Project layout

```
app/src/main/java/com/kidlocaltv/
├── core/                    # Pure Kotlin, unit tested on the JVM
│   ├── PlaybackNavigator.kt # shuffle / series logic, shuffle bag, wrap-around
│   ├── VideoLibrary.kt      # folder scan, grouping, natural sort, supported extensions
│   ├── Video.kt             # a video: local file or network (SMB) location
│   ├── NaturalOrder.kt
│   └── ResumePolicy.kt      # when to keep / clear saved positions
├── server/                  # Pure JVM (no Android APIs), tested against real SMB servers
│   ├── ImportServer.kt      # web page API: uploads, network folders, downloads, delete
│   ├── NetworkSources.kt    # saved SMB shares + cached file lists, background rescans
│   ├── SmbStreamServer.kt   # loopback HTTP server: streams SMB files with Range/seek support
│   ├── SmbSource.kt         # smbj wrapper: login, recursive listing, random-access reads
│   ├── SmbLink.kt           # parses smb://, \\server\share and //server/share links
│   └── NetworkInfo.kt       # TV's LAN address
├── data/AppPrefs.kt         # setup flag, library location, saved positions
└── ui/
    ├── PlayerActivity.kt    # launcher, LibVLC full-screen player, remote handling
    ├── SetupActivity.kt     # first run + "Manage videos"
    └── QrCode.kt
app/src/main/assets/web/index.html   # the web page
```

Run the unit tests:

```bash
./gradlew testDebugUnitTest
```

### Notes

- The web page only runs while the setup / manage screen is open. It has **no password**, so anyone on the same network can reach it during that time.
- The stream server listens on `127.0.0.1` only, so other devices on the network can't reach it.
- SMB logins are stored in the app's private storage, which other apps can't read. They're stored in plain text, though, so use a read-only account for the share if you can.
- Upload and download file names are cleaned up, and every write stays inside the TV's library folder.
- `minSdk` 21 (Android 5.0), `targetSdk` 35. The app declares the Leanback launcher but doesn't require it, so it also installs on regular Android devices.

### Open-source libraries

| Library | Used for | License |
|---|---|---|
| [LibVLC for Android](https://code.videolan.org/videolan/vlc-android) | Video playback / decoding | LGPL-2.1+ |
| [smbj](https://github.com/hierynomus/smbj) | SMB 2/3 client | Apache-2.0 |
| [Bouncy Castle](https://www.bouncycastle.org/) (via smbj) | NTLM crypto for SMB logins | MIT |
| [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) | Web page and stream servers | BSD-3-Clause |
| [ZXing](https://github.com/zxing/zxing) | QR code on the setup screen | Apache-2.0 |

LibVLC is LGPL. It's used unmodified, as the shared libraries published by VideoLAN, so it can be swapped for another build.
