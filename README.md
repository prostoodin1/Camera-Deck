# Camera Deck · Client

Android cameras controlled from a Windows operator desk. **Client 0.11.1 is an experimental release candidate**, Android 9+ / Windows 10–11 x64. Original recording to Windows requires Android 10+ and retains a phone backup. Physical validation of the new client features remains necessary.

Install CameraDeck-Setup.exe on Windows and CameraDeck-Android.apk on Android once. Subsequent signed patches replace the same installation, preserving profiles and media. Windows installs into the user profile with one Start menu shortcut. Android retains the package and certificate of Slate 0.10.0; compatibility with older 0.9 signing identities is not established. The first migration from Slate 0.10 to Client is manual because the signed manifest channel changes. See [update lifecycle, system confirmation and rollback limits](docs/CLIENT-UPDATES.ru.md) and [release notes](CLIENT_RELEASE_NOTES.md).

Client adds sensor EV/AE lock/metering, supported WB/antibanding/hardware filters, contrast/gamma, logarithmic ISO, smooth multiple-face markers and selectable eye targets. Gallery publication defaults to DCIM/Camera Deck with a private backup. Experimental person-mask blur produces separate JPEG/MP4 copies after capture; originals remain unchanged. Blur is off by default and requires device validation.

0.10 adds USB cable connection without Wi-Fi (Android USB debugging and computer authorization required), phone audio-input selection, independent Windows audio-output/volume selection, keep-screen-on during foreground recording, and compact black/yellow controls. See [setup, validation and limitations](docs/SLATE-USB-AUDIO.ru.md). [Windows test-stand screenshots](docs/screenshots/slate-usb-audio/) use explicitly labelled simulators.

## Start

Install CameraDeck-Android.apk over Slate 0.10.0 with the matching signing certificate; do not uninstall first. Run CameraDeck-Setup.exe on Windows. No Python installation is required. Older portable releases remain historical downloads.

Enable the camera, grant permissions and connect through USB or local Wi-Fi. Compare the entire SHA-256 fingerprint before PIN pairing. Trust the computer for later reconnection; revoke trust when removing access. The client uses package .aurora and signed-update product camera-deck-client.

## Workspaces

- **Монитор**: compact camera cards, measured display FPS, per-camera capture, fullscreen and persistent viewer rotation/lock. Layouts in 1–3 columns and groups; hidden views pause preview, preserving phone recording.
- **Камера**: selected-camera inspector, actual exposure values, photo/video settings, focus, zoom/composition, audio and original PC recording. Geometry, selected identity and groups persist.
- **Продакшн**: group start/stop with per-camera preflight and confirmed results, elapsed state/storage and operation timeline. Failures remain independent; start is never blindly retried. Clock estimation reports round-trip uncertainty and does not synchronize exposures or promise frame alignment.
- **Материалы**: authenticated metadata/thumbnails, search/filtering, paging on updated phones, original download with progress/retry, downloaded-video playback and photo opening. Partial phone files are labeled separately, not offered as completed downloads. Interrupted PC fragments can be remuxed to a new file without replacing the source.

Preview tools: grid, 90% safe area, histogram, waveform, zebra at luma >=235/255 and approximate false color. Bounded 160×90 analysis runs outside the GUI at 4 Hz. These describe decoded **8-bit preview**, not RAW, calibrated IRE or HDR, and never alter the original.

Android has a black/graphite visual system, limited yellow accents, drawn round shutter, recording timer and clean view, portrait/landscape layouts, pinch zoom/touch AF, grid/safe area and reported thermal restrictions. A microphone toggle, mic activity and confirmed recording audio track are separate states.

## Preview and original recording

Idle phone preview uses Camera2/TextureView. Compatible local preview converts latest NV21 on a dedicated worker without JPEG round trips/UI decoding. It retains one latest input/one UI handoff. Local conversion is bounded to 640×480 by integer sampling; original/Windows AVC resolution is unaffected. Fractional cadence prevents a 30 FPS source collapsing to 15 FPS at a 24 FPS target.

Windows prefers H.264 over pinned TLS: Balanced targets up to 720p60, Detail up to 1080p60, Eco targets 30 AVC FPS. Actual camera/codec support governs selection. B-frames are disabled where supported; IDRs include parameter sets; history is bounded to 16 access units/4 MB. Slow viewers restart on an IDR and bitrate adapts up to 6/12 Mbps. A 16 ms GUI timer is not advertised as camera FPS. JPEG fallback targets 24 FPS/12 Eco.

Recording waits for the previous session to close and releases the separate preview encoder. It uses the original encoder plus compatible YUV, excluding native/still/second-encoder outputs. Recorder-only fallback can preserve selected resolution/FPS at the cost of preview/face analysis. Auto zoom/face focus continue in background recording when the YUV output remains available. Faces/eyes use bundled on-device ML Kit; markers do not prove vendor eye-AF or eye sharpness. MP4 orientation is fixed at start; viewer rotation is separate.

Choose Video, a supported recording mode, then **Камера → Запись оригинала на ПК**. Original encoded video/AAC packets and timestamps travel over TLS; Windows remuxes without re-encoding. Fragmented MP4 with delayed initialization preserves initial A/V offsets. Completion requires explicit terminal confirmation and muxer closure.

**Phone backup is mandatory in Slate and still consumes phone/selected-phone-storage space. PC-only recording is not implemented.** Network/PC disk failures do not automatically stop the backup. Windows retains `.partial.mp4` plus a `.recording.json` journal. Recovery depends on readable completed fragments; it is not guaranteed before initialization or after arbitrary disk damage. Closing during capture warns that the PC copy will be interrupted.

Since 0.9.1, interrupted PC capture switches to recovery rather than restarting the take. With both updated apps, a unique recording session identifies the matching phone backup. After the operator stops recording, Windows downloads that completed MP4 to the chosen filename; incomplete PC fragments are retained separately. Up to five minutes offline or five minutes of failed transfer retries are tolerated. Closing the PC app stops this recovery worker, not phone capture. Failed phone storage/codec finalization, missing storage, unavailable backup identity or insufficient PC space are reported explicitly; automatic recovery is not a guarantee against device/storage failure. Reconnection to a restarted service requires remembered pairing or a new manual pairing.

Live audio monitoring is independent of VIEW and retries every two seconds while enabled and visible. Minimizing/hiding the view pauses monitoring only. Transient microphone exceptions generate marked silence while the microphone is retried; an overfull audio input queue discards stale blocks with a warning instead of aborting video. Lost sound cannot be reconstructed: counters/warnings remain visible. Fatal codec or storage errors can still stop capture. Neither uninterrupted audio nor A/V sync has been established on physical phones.

The relay is bounded to 64 MB/8 MB per message and never waits on a slow network from the phone encoder. Windows checks disk capacity before/during recording. Android 10+ is required for disabling B-frames on the original relay; reordered encoder output explicitly terminates the PC copy. Android 9 keeps local phone recording.

## Capabilities, sound and storage

Normal video modes come from Camera2 sizes/minimum durations/AE ranges and AVC/HEVC encoder support. Common reported HD/Full HD/QHD/4K/8K choices appear first; other reported sizes remain selectable. 4K60 requires actual device exposure and usability. High-speed/maximum-sensor modes are informational; special sessions, vendor HDR/Log/10-bit and stock-camera computational algorithms are not implemented.

JPEG comes from the camera; PNG/WebP convert processed JPEG and cannot restore lost information. DNG uses actual RAW_SENSOR and matching metadata if supported. ISO/exposure/manual focus are capability gated. Modes/lenses/storage cannot change during recording; photos during video recording are unavailable.

MP4 uses AVC/HEVC and optional AAC-LC 48 kHz mono/128 kbps. Independent live monitoring uses PCM 48 kHz mono/16 bit and is **not** lip-synchronized with preview. Phone storage or a persistent SAF folder (including available USB providers) can be selected. MP4 requires a seekable descriptor. Photos stage then export; failed operations retain partial/original files. Free capacity can be unknown; remaining minutes are not invented.

## Protocol and protection

Discovery: UDP 39811, `CAMERA_DECK_DISCOVER_V1`, JSON replies. API/media: HTTP over TCP/TLS 1.2 on 39812. Full certificate pin precedes PIN/token transmission, followed by Bearer authorization. PIN expires in five minutes, rotates after pairing, max five attempts/minute. Sessions last up to eight hours/max four. Max eight trusted clients, two preview consumers/two microphone listeners. Windows secrets use account-bound DPAPI; the phone retains hashes. No cloud account is needed for capture; public GitHub is used for updates.

Existing API endpoints/protocol1 remain compatible. Additions: `/recording`, `/clock`, `/media_info`, `/thumbnail`, opt-in paged/partial media and extra status fields. Legacy `/media` keeps the completed-file list. Old phones retain textual catalog fallback; original PC recording requires the advertised capability. No WebRTC, RTSP/RTP, SRT, NDI, QUIC, USB video transport or internet relay; zero latency/loss is not promised.

The original relay frames each message as uint32 big-endian length (including kind), then kind 0 hello/1 JSON formats/2 sample/3 JSON terminal. A sample contains track byte (video0/AAC1), int64 PTS microseconds, uint32 codec flags and encoded bytes. Configuration contains MIME, size/rate and base64 codec-specific bytes.

Foreground service/notification and demand-based timed wake lock support recording/transfers. Idle views stop repeating requests. No boot startup or automatic battery exemption. Thermal/background behavior is firmware-dependent.

## Updates, builds, validation

Both programs verify signed GitHub manifests (RSA/SHA-256), product/version and artifact hashes/sizes. APK package/certificate are checked. Android requires a one-time source permission and may request installation confirmation. Windows waits for capture and idle input, retains the previous EXE and restores it if the new interface fails its startup acknowledgement. Background Android checks run through JobScheduler; Windows can deliver signed APKs through USB/Wi-Fi. See the client update lifecycle for limits.

Desktop: Python3.13, `pip install -r desktop/requirements.txt pytest pyinstaller`, `python scripts/collect_licenses.py`, `python scripts/build_windows.py`. Test: `python -m pytest tests -q`. Both executables accept `--self-test ABSOLUTE_OUTPUT_DIRECTORY`.

Android: JDK17, Gradle8.7, SDK35/build-tools35. scripts/build_client_android.py builds the permanent non-debuggable client using its adopted signing identity. client-release.yml validates both platforms and publishes signed client prereleases. The local protected identity and encrypted CI secrets must be retained; never replace existing APK/update keys.

The [Slate report](docs/SLATE-REPORT.ru.md) describes a historical build and does not validate new client features on physical devices. License GPL-3.0-only; [third-party notices](THIRD_PARTY_NOTICES.md).
