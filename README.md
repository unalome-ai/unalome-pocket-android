# Unalome Pocket for Android

Chat with open AI models that run entirely on your Android phone. No account, no cloud, no
telemetry, and it works in airplane mode once a model is downloaded. Free and open source under the
Apache License 2.0.

Unalome Pocket is also available for iPhone, iPad and Mac:
[unalome-ai/unalome-pocket](https://github.com/unalome-ai/unalome-pocket). More at
[unalome.ai/pocket](https://unalome.ai/pocket).

## Download

Download the signed APK from the [latest release](https://github.com/unalome-ai/unalome-pocket-android/releases/latest)
and open it on your phone. Android asks once for permission to install apps from your browser.
Google Play is coming.

Requires Android 10 or later on a 64-bit (arm64) phone. A phone with 8 GB of memory or more runs
the larger models.

Every release is signed with the same key. To check an APK before installing it:

```sh
apksigner verify --print-certs unalome-pocket-android.apk
```

The certificate SHA-256 digest must be
`85403e99c77eefc6f765db30690dcecd8ce929ec4c87b2bfabb4bdaac8f51c1a`.

## Features

- **Private chat:** replies are written on the phone, streamed as they come, with Stop, Regenerate,
  Copy, and Edit and resend. The status line shows the model and its speed in tokens per second.
- **Thinking:** models that can reason before answering show their reasoning separately from the
  reply.
- **Images:** attach up to four photos from the photo picker, the camera or another app's Share
  menu, and ask about them. Images are scaled down and stored without metadata.
- **Voice:** dictate into the composer with Android's on-device speech recognition (Android 12 or
  later). Audio never leaves the phone; there is no server fallback.
- **Personas:** Assistant, Writing editor, Explainer and Coder to start with, plus your own, each
  with its own instructions, temperature and preferred model.
- **Conversations:** saved on the phone, searchable, deletable, with a New chat shortcut on the app
  icon.
- **Models:** a curated list, each labelled for your phone as recommended, runs, or too large.
  Downloads resume after interruptions, continue in the background and are checked against their
  checksum before use.
- **Report this response:** opens a prefilled email in your mail app; nothing is sent unless you
  send it.

## Models

| Model | Download | Phone memory | Images | Thinking | Licence | Good for |
| --- | --- | --- | --- | --- | --- | --- |
| Qwen 3.5 0.8B | 0.74 GB | 3 GB | yes | yes | Apache 2.0 | Quick answers on any supported phone. The smallest and fastest. |
| Gemma 3 1B | 0.81 GB | 4 GB | no | no | Gemma Terms of Use | Short everyday questions and rewording text. |
| Qwen 3.5 2B | 1.95 GB | 6 GB | yes | yes | Apache 2.0 | Better reasoning than the small models, still quick. |
| Gemma 4 E2B | 3.40 GB | 8 GB | yes | yes | Apache 2.0 | Balanced everyday chat with good writing quality. |
| Qwen 3.5 4B | 3.41 GB | 8 GB | yes | yes | Apache 2.0 | Stronger reasoning and longer answers. |
| Gemma 4 E4B | 5.15 GB | 12 GB | yes | yes | Apache 2.0 | Higher-quality answers on phones with 12 GB of memory or more. |
| Qwen 3.5 9B | 6.60 GB | 16 GB | yes | yes | Apache 2.0 | The best answers here, for phones with 16 GB of memory. |

Models are GGUF files pinned to a Hugging Face revision and checked by SHA-256
([catalog.json](modelcatalog/src/main/assets/catalog.json)). They are not part of the app; each is
downloaded under its own licence, which the app shows before the first download. Download sizes
include the image-support file.

As a reference, on a Snapdragon 8s Gen 4 phone Qwen 3.5 2B writes about 17 tokens per second on
the CPU and uses about 2 GB of memory.

## Privacy

The app collects nothing and has no analytics, advertising or crash reporting. Chats, images and
personas are stored in the app's private storage and are excluded from Android backup.

The only network traffic is a model download that you start, from `huggingface.co` and its file
CDN (`*.hf.co`). Requests are anonymous, with a generic User-Agent and no account or token. Chatting
never uses the network.

| Permission | Why |
| --- | --- |
| `INTERNET` | Model downloads |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | Keep a download running while you use other apps |
| `POST_NOTIFICATIONS` | Show download progress (downloads work without it) |
| `RECORD_AUDIO` | Dictation, asked for on first use |

Photos come through the system photo picker and the camera app, so the app needs no storage or
camera permission. Full privacy policy: [unalome.ai/pocket/privacy](https://unalome.ai/pocket/privacy).

## How it works

Models run on the phone's CPU through [llama.cpp](https://github.com/ggml-org/llama.cpp)
(tag `b12525`), built from source as a git submodule and called through the app's own JNI layer.
It applies each model's own chat template, streams text safely across multi-byte characters,
stops within one step when you press Stop, trims the oldest turns when a chat outgrows the model's
context, and reads images through llama.cpp's `mtmd` library. llama.cpp ships CPU builds for
several ARM generations and picks the best one for the phone at run time.

The app is written in Kotlin with Jetpack Compose, Navigation 3 and Room.

| Module | What it holds |
| --- | --- |
| `:design` | Design tokens, Inter typography, glass surfaces, the unalome figure and mark, icons, shared components |
| `:chatcore` | The `ChatEngine` contract, conversations, personas and attachments, Room storage |
| `:llamaengine` | `LlamaEngine`: the JNI layer (`src/main/cpp`), loading, chat templates, sampling, cancellation, context fitting, device tuning |
| `:modelcatalog` | The bundled catalog, per-device recommendations, the resumable verified downloader and its foreground service, installed models |
| `:features` | Screens and view models (Chat, Conversations, Models, Personas, About), voice input, image handling, the streaming Markdown renderer |
| `:app` | Application wiring, `MainActivity`, manifest, launcher icon and shortcut |

Replies are rendered by a small Markdown renderer built to stay stable while text streams in: an
unfinished code block, table or bold run already renders as such, and earlier blocks never change
as new text arrives.

## Build

Requirements: JDK 17 and the Android SDK with platform 36, build tools 36, NDK `29.0.14206865` and
CMake `3.31.6` (all from `sdkmanager`). Android Studio is optional.

```sh
git clone --recurse-submodules --shallow-submodules https://github.com/unalome-ai/unalome-pocket-android
cd unalome-pocket-android
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew assembleDebug testDebugUnitTest lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The first build compiles llama.cpp and takes a few minutes. The app is arm64 only, so it runs on
arm64 phones and arm64 emulators (for example on Apple silicon Macs), not on x86_64 emulators.

Release builds are signed with a key kept outside the repository. `scripts/create-signing-key.sh`
creates one (on macOS, with its password in the Keychain), and `scripts/build-release.sh` builds
the signed APK and the Google Play bundle. Without a key, `./gradlew assembleRelease` builds an
unsigned APK.

Debug builds also offer a scripted demo engine in the model menu, for trying the interface without
a model; release builds do not include it.

## Tests

`./gradlew testDebugUnitTest` runs the JVM tests: the catalog, recommendations, the downloader
against a local server (resume, redirects, checksum and space errors, cancel), storage and database
migrations, personas, attachments, context trimming, voice availability, the chat view model on a
scripted engine, and the Markdown renderer. The native engine is tested on a phone or emulator.

## Licence

Apache License 2.0, see [LICENSE](LICENSE). Third-party code and fonts are listed in
[third_party/NOTICE.md](third_party/NOTICE.md) and in the app's About screen: llama.cpp and ggml
(MIT) with the libraries they include, nlohmann/json (MIT), cpp-httplib (MIT) and the Inter
typeface (SIL Open Font License 1.1).

Questions or problems: [support@unalome.ai](mailto:support@unalome.ai) or a GitHub issue.
