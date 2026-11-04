# Kid Local TV

A simple, kid-friendly video player for **Android TV**. You load it with your own videos over the home network. Once it's set up, it opens straight into full-screen playback of a random video. The remote's arrow keys are the only controls there are.

<p align="center"><img src="docs/images/tv-shuffle.png" width="820" alt="Kid Local TV playing a video in shuffle mode"></p>

> **About the images:** The TV-screen pictures in this README are illustrations. They're drawn to match the app's real layouts, colours and text (`activity_setup.xml`, `activity_player.xml`, `strings.xml`), and the "videos" in them are placeholder art. The [web import page](#2-send-videos-from-a-phone-or-computer) screenshot is a real capture of the page the app serves.

---

## Features at a glance

| | |
|---|---|
| 🛠 **One-time setup** | On first launch the TV shows an address and QR code. Open it on any phone or computer on the same Wi-Fi to send videos. |
| ▶️ **Instant playback** | Every launch after that goes straight to full screen and starts a random video. |
| 🔀 **Shuffle mode** | ▲ or ▼ plays another random video from the *whole* library, whatever folder or file order. |
| 📺 **Series mode** | ▶ locks onto the current video's folder. ▲ / ▼ then step through its episodes in order. |
| 🔖 **Resume** | Each time ▲/▼ switches videos, the spot in the video you left is saved. That video resumes there next time. |
| 🌐 **Network import** | Upload files or whole folders from a browser, or have the TV download a video from a link on your NAS. |

<p align="center"><img src="docs/images/diagram-flow.png" width="900" alt="Setup flow: first launch shows an address, send videos from a phone, later launches play immediately"></p>

---

## How it works

### 1. First launch: set up a video source

When the library is empty, the app opens the setup screen. The TV starts a small web server on port `8080` and shows its address and a QR code. Uploaded videos are stored on the TV, in the app's own storage, so no storage permission is needed.

<p align="center"><img src="docs/images/tv-setup.png" width="820" alt="Setup screen with the import address and QR code"></p>

The count updates live as videos arrive. Once at least one video is on the TV, **Start watching** becomes available. From then on the app skips this screen.

### 2. Send videos from a phone or computer

Open the address shown on the TV (or scan the QR code). The page lets you:

- **Upload files.** Drag and drop, or pick them. Type a *folder / series name* first, for example `Ocean Friends`.
- **Upload a whole folder.** Each file's sub-folder becomes its series name.
- **Import from a link.** Paste a direct `http://…/video.mp4` link from a NAS or media server and the TV downloads it.
- **See and delete** what's already on the TV.

<p align="center"><img src="docs/images/web-import.png" width="560" alt="The web import page served by the TV"></p>
<p align="center"><sub>Real screenshot of the import page. The three upload rows at the top were staged to show what an upload in progress looks like.</sub></p>

**Folders are series.** Files inside a folder play in *natural* name order, so `Episode 2` comes before `Episode 10`. Videos uploaded without a folder go into *Unsorted*.

Supported files: `mp4 m4v mkv webm mov avi ts m2ts 3gp flv mpg mpeg`. Playback uses Media3 ExoPlayer, so what actually plays depends on the TV's hardware codecs. H.264/AAC MP4 is the safest choice.

### 3. Every launch after: straight to playback

The app opens full screen and starts a random video in **shuffle mode**. A small card shows the current mode, the title, and where it sits in its folder, then fades after a few seconds.

<p align="center"><img src="docs/images/diagram-modes.png" width="900" alt="Shuffle mode jumps across the whole library; series mode steps through one folder"></p>

#### Shuffle mode

- ▲ and ▼ both pick **another random video from the entire library**.
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

- Every ▲ / ▼ switch saves the current position of the video **being left**, keyed by its path in the library.
- When that video plays again, in either mode, it starts from the saved spot.
- Positions within the first 5 s or the last 15 s aren't kept, so a finished video starts fresh.
- Leaving the app (Home or Back) also saves the current spot.

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

**Manage videos** (hold OK) reopens the import screen so you can add or remove videos. Press *Back to watching* to carry on with the same video.

---

## Install on Android TV

### Get the APK

**Option A: GitHub Actions.** Every push runs [`.github/workflows/build.yml`](.github/workflows/build.yml), which runs the unit tests and builds the APKs. Download the `kid-local-tv-apk` artifact from the workflow run.

**Option B: build locally.** You need JDK 17 and the Android SDK, or Android Studio:

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

### Sideload it

1. On the TV, turn on **Developer options**: *Settings → Device Preferences → About*, then select *Build* 7 times. Then turn on **USB debugging** (or **Network debugging**).
2. From a computer on the same network:

   ```bash
   adb connect <tv-ip-address>
   ```

   ```bash
   adb install -r app-debug.apk
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
│   ├── VideoLibrary.kt      # folder scan, grouping, natural sort
│   ├── NaturalOrder.kt
│   └── ResumePolicy.kt      # when to keep / clear saved positions
├── server/
│   ├── ImportServer.kt      # NanoHTTPD: upload, link import, library, delete
│   └── NetworkInfo.kt       # TV's LAN address
├── data/AppPrefs.kt         # setup flag, library location, saved positions
└── ui/
    ├── PlayerActivity.kt    # launcher, full-screen player, remote handling
    ├── SetupActivity.kt     # first run + "Manage videos"
    └── QrCode.kt
app/src/main/assets/web/index.html   # the import web page
```

Run the unit tests:

```bash
./gradlew testDebugUnitTest
```

### Notes

- The import server only runs while the setup / manage screen is open. It has **no password**, so anyone on the same network can reach it during that time.
- Upload and link-import file names are cleaned up, and every write stays inside the library folder.
- `minSdk` 21 (Android 5.0), `targetSdk` 35. The app declares the Leanback launcher but doesn't require it, so it also installs on regular Android devices.
