# Unalome Pocket for Android

Unalome Pocket runs open language models entirely on your phone, in a chat. 100% local: no
account, no cloud, no telemetry. It is free and open source under the Apache License 2.0.

This is the native Android version of the shipped iOS app, written in Kotlin with Jetpack Compose.
The iOS app is the reference for behaviour, wording and look.

## Status: milestone 3, images, voice and personas

Replies come from a real model running on the phone's CPU through
[llama.cpp](https://github.com/ggml-org/llama.cpp) (tag `b12525`, commit `edec462`), built from
source as a git submodule.

What works:

- Models: a bundled catalog of GGUF models with pinned Hugging Face revisions, sizes and SHA-256
  checksums. Each model is labelled for this phone (recommended / runs / too large) from its memory
  and free storage. Downloads accept the licence first, resume after interruption or a closed app,
  keep running in a foreground service with a progress notification, and are checked by size and
  checksum before use. Installed models can be switched from the model pill and deleted on the
  Models screen.
- Chat: streaming replies with tok/s in the status pill, Stop, Regenerate, Copy, "Report this
  response" (opens a prefilled email; the app sends nothing), "Think before answering" for models
  whose chat template supports it, with the reasoning shown apart from the reply. Long chats drop
  their oldest turns to fit the model's context. "Edit and resend" on the last message.
- Images: models marked "Images" read photos, through llama.cpp's image reader (`mtmd`) and a
  projector file downloaded with the model (a model installed before this offers "Download image
  support"). Up to four images per message from the system photo picker (no storage permission),
  the camera, or another app's Share menu. Each image is turned upright, scaled to at most 1024 px
  and stored app-private as a JPEG without metadata; it is deleted with its message or chat. A
  text-only model explains that it cannot read images.
- Voice: dictation into the composer with Android's on-device speech recogniser only (Android 12+);
  if the phone cannot recognise the language on the device, the microphone explains why instead of
  falling back to a server. The microphone permission is asked for, with a reason, on first use.
- Personas: the iPhone app's four starters (Assistant, Writing editor, Explainer, Coder) plus your
  own, each a system prompt, a temperature and an optional model; chosen when a chat starts or from
  the model pill. Starters can be edited and reset, not deleted.
- Conversations: list, search, delete, New chat (also a launcher shortcut).
- About: privacy statement, AI notice, source link, licences.
- Debug builds also offer the scripted demo engine from milestone 1 in the model menu; release
  builds do not.

### Network

The app's only network traffic is a model download the user starts, from `huggingface.co` and its
file CDN (`*.hf.co`). Requests are anonymous: no account, token or identifying header, a generic
User-Agent. Chats never leave the phone. Models are stored in app-private storage that is excluded
from backups.

Permissions: `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` (to keep a download
alive), `POST_NOTIFICATIONS` (its progress; the download runs without it) and `RECORD_AUDIO` (dictation,
asked for on first use). Photos come through the system photo picker and the camera app through a
`FileProvider`, so no storage or camera permission is needed. The app declares `<queries>` for the
speech-recognition service and the camera intent so it can find them on Android 11+.

## Build and run

Requirements: JDK 17, the Android SDK with platform 36 and build tools 36.x, NDK
`29.0.14206865` and CMake `3.31.6` (both from `sdkmanager`). No Android Studio needed.

```sh
git submodule update --init --depth 1        # llama.cpp
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
echo "sdk.dir=$ANDROID_HOME" > local.properties   # not committed

./gradlew assembleDebug testDebugUnitTest lintDebug
./gradlew assembleRelease
```

The first build compiles llama.cpp (a few minutes). Native code is always built in release mode
(`-O3`) and linked for 16 KB pages. The app is arm64-v8a only: llama.cpp ships CPU variants for
the different ARM generations and picks the best one at run time. The APK therefore installs on
arm64 phones and on arm64 emulators (Apple silicon Macs), not on x86_64 emulators.

Run on an emulator or a phone (USB debugging on; the debug APK is signed with the debug key and
installs as is):

```sh
$ANDROID_HOME/emulator/emulator -avd <your-avd> &
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n ai.unalome.pocket/.MainActivity
```

Release signing is not in the tree; supply it outside the repository.

## Modules

The split mirrors the iOS `PocketKit` package:

| Module | What it holds |
| --- | --- |
| `:design` | Design tokens (colours, gradient, radii), Inter typography, glass surfaces, the unalome figure and mark (ported from the iOS `Brand.swift` geometry), line icons, shared components. |
| `:chatcore` | The `ChatEngine` contract (capabilities, `load`, `unload`, `reply` as a `Flow<GenerationEvent>`), `ScriptedEngine`, the `<think>` reasoning splitter, `EngineHost`, the data model and Room storage, `ConversationStore` (titles, search, append). |
| `:llamaengine` | `LlamaEngine`, the `ChatEngine` on llama.cpp: our JNI layer (`src/main/cpp`), loading with progress (read once into the CPU backend's repacked weights, not memory-mapped, so the model is not held twice), the model's own chat template with thinking, UTF-8-safe streaming, sampling, cancellation, context fitting (`ContextWindow`) and device tuning (context size, threads on the performance cores). CPU only for now; the backend is a parameter so a GPU backend can be added. |
| `:modelcatalog` | The bundled `catalog.json`, `ModelRecommender` (labels per device), `ModelDownloader` (resumable, verified), `ModelDownloadService` (foreground service), `InstalledModelStore` and `ModelLibrary`, which ties them together. |
| `:features` | Screens and view models: Chat, Conversations, Models, About; the Markdown renderer; Navigation 3 routes. Uses `:modelcatalog` and the `ChatEngine` interface; the engine itself comes from `:app` through an `EngineFactory`. |
| `:app` | `Application` (wires the store, the model library and the engine factory), `MainActivity`, manifest, launcher icon. |

Navigation uses Navigation 3: the back stack is plain data (`ConversationsRoute`, `ChatRoute`,
`ModelsRoute`, `SettingsRoute`), so a tablet list–detail layout can be added later with a `SceneStrategy`
(`material3-adaptive-navigation3`) without changing the screens.

### Markdown

Replies are rendered by a small Markdown renderer in `:features` (`markdown/`), not a library.
It covers what small models write, parses synchronously and line by line, and is built to be
stable while streaming: an unclosed code fence is already a code block, a table header is already
a table, an unclosed `**` is already bold, and earlier blocks never change as tokens arrive (a
unit test checks this for every prefix of every scripted reply). The iOS app also renders its own
blocks (on top of swift-markdown).

## Tests

JVM unit tests (`./gradlew testDebugUnitTest`):

- `chatcore`: title derivation, store rules and search, the scripted engine, the reasoning
  splitter, and the Room schema and queries (on Robolectric).
- `features`: the chat view model against `ScriptedEngine` on virtual time (send, stream, stop,
  stop before the first token, regenerate, thinking, reopening a chat) and the Markdown parser.
- `modelcatalog`: parsing and checking the bundled catalog, size formatting, plain error messages,
  the per-device labels, the installed-model store, and the downloader against MockWebServer
  (download and verify, resume with `Range`, a server that ignores the range, redirects to allowed
  hosts only, checksum mismatch, not enough space, HTTP errors, cancel and resume).
- `llamaengine`: context trimming and device tuning. The native engine itself is tested on a
  device or emulator, not on the JVM.

## Licence

Apache License 2.0, see [LICENSE](LICENSE). Third-party code and fonts are listed in
[third_party/NOTICE.md](third_party/NOTICE.md): llama.cpp and ggml (MIT), nlohmann/json (MIT),
cpp-httplib (MIT) and the Inter typeface (SIL Open Font License 1.1). The models are not part of
the app; each is downloaded under its own licence, which the app shows before the download.
