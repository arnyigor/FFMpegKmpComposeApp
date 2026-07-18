# FFmpeg Media Workshop

Production-ready Windows desktop app for local media processing: FFmpeg conversion, trimming, audio replacement, speech transcription and experimental smart voice replacement.

## Features

- **Stream copy** without video re-encoding for fast remux/cut operations.
- **MP4 audio replacement**: copy video stream and encode replacement audio to AAC.
- **Full conversion** to compatible MP4 with H.265/AAC settings.
- **Audio extraction** to WAV, including selected audio stream support.
- **Timeline preview and trimming** with fast/accurate trim strategies.
- **Offline Whisper transcription** with TXT/SRT/VTT/JSON outputs.
- **Smart voice replacement** *(experimental)*:
  - separates original audio into `vocals` and `no_vocals` with Demucs;
  - keeps music/background;
  - mixes a new voice track;
  - optionally keeps the original voice at reduced volume;
  - supports CPU, CUDA and auto device selection.
- **Automatic FFmpeg setup** on Windows or manual path selection.
- **Detailed progress and logs** with autoscroll.

## Windows production build

The production deliverable is a portable Windows x64 zip with bundled Java runtime:

```powershell
./gradlew :desktopApp:packagePortable
```

Output:

```text
desktopApp/build/compose/binaries/main/portable/FFmpegMediaWorkshop-1.1.0-windows-x64-portable.zip
```

The unpacked app is also generated at:

```text
desktopApp/build/compose/binaries/main/app/FFmpegMediaWorkshop/FFmpegMediaWorkshop.exe
```

## GitHub release

Windows releases are built by GitHub Actions from tags:

```powershell
git tag v1.1.0
git push origin master
git push origin v1.1.0
```

The workflow creates a GitHub Release and uploads:

```text
FFmpegMediaWorkshop-1.1.0-windows-x64-portable.zip
```

Manual release builds can also be started from the **Actions → Windows Release** workflow.

## Runtime dependencies

### FFmpeg

On first launch the app asks for FFmpeg setup:

- select an existing `ffmpeg.exe`; or
- download FFmpeg automatically on Windows.

The app expects `ffprobe.exe` and `ffplay.exe` next to `ffmpeg.exe` for media analysis and preview.

### Whisper

Portable builds do not bundle Python or Whisper models. On first transcription run the app installs into:

```text
%LOCALAPPDATA%\FFmpegMediaWorkshop\whisper
```

It downloads:

- `uv` with SHA-256 verification;
- managed Python 3.12;
- `faster-whisper`.

Models are cached locally by the Whisper runtime.

### Smart voice replacement / Demucs

Smart voice replacement installs its own runtime into:

```text
%LOCALAPPDATA%\FFmpegMediaWorkshop\smart-voice
```

It downloads:

- managed Python 3.11;
- Demucs;
- PyTorch;
- HTDemucs model from Hugging Face.

CUDA mode may download a large PyTorch CUDA package. RTX 50xx GPUs require a PyTorch build that supports `sm_120`; if CUDA is unavailable or unsupported, use **CPU** or **Auto**.

For checking HTDemucs model availability:

```powershell
python check_demucs_model.py
```

## Recommended smart voice workflow

For long videos, first test a short fragment:

1. Select source video.
2. Enable **Trim** and choose 30–60 seconds.
3. Select **Stream copy** or **Convert**.
4. Enable **Smart voice replacement** in **Additional options**.
5. Select the new voice track.
6. Use `HTDemucs` + `Auto` first.
7. If quality is acceptable, process the full video.

Model guidance:

- **HTDemucs** — recommended balance of quality and speed.
- **HTDemucs FT** — better quality on complex music, slower.
- **MDX Extra Q** — faster/lighter for tests, more artifacts possible.

## Development

### Requirements

- JDK 17+
- Windows 10/11 for production packaging
- Git

### Run from source

```powershell
./gradlew :desktopApp:run
```

### Compile check

```powershell
./gradlew :shared:compileKotlinDesktop :desktopApp:compileKotlinDesktop
```

### Package portable build

```powershell
./gradlew :desktopApp:packagePortable
```

## Project structure

```text
shared/
  src/commonMain/kotlin/com/arny/ffmpegcompose/
    components/       Decompose components and UI state
    data/             FFmpeg, Whisper and Smart Voice executors
    data/models/      App models and processing settings
    di/               Koin modules
    ui/               Compose UI

desktopApp/
  src/desktopMain/    Desktop entry point
```

## Current version

`1.1.0`
