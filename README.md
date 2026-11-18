# Unalome Pocket for Android

Unalome Pocket runs open language models entirely on your phone, in a chat. 100% local: no
account, no cloud, no telemetry. It is free and open source under the Apache License 2.0.

This is the native Android version of the shipped iOS app, written in Kotlin with Jetpack Compose.
The iOS app is the reference for behaviour, wording and look.

## Status: foundation milestone

The app works end to end on a **scripted engine**: canned Markdown replies streamed at about
30 tokens per second, so the chat, storage and design can be built and tested before a real
on-device inference engine is chosen. There is no model download and no network access; the
manifest has no `INTERNET` permission.

What works:

- Chat: streaming replies, Stop (keeps the text so far), Regenerate, Copy, "Report this response"
  (opens a prefilled email; nothing is sent by the app), "Think before answering" with the
  reasoning shown apart from the reply, status pill, AI disclaimer. Mic and attach are visible but
  stubbed.
- Conversations: list, search (titles and message text), delete (swipe or long press), New chat.
  Titles come from the first user message.
- About: privacy statement, AI notice, source link, licences (Apache 2.0, Inter under the OFL).
- Markdown in replies: bold, italic, inline code, links, headings, lists (nested), fenced code,
  GFM tables, quotes, rules.

## Build and run

Requirements: JDK 17, the Android SDK with platform 36 and build tools 36.x. No Android Studio
needed.

```sh
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
echo "sdk.dir=$ANDROID_HOME" > local.properties   # not committed

./gradlew assembleDebug testDebugUnitTest lintDebug
```

Run on an emulator or a device:

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
| `:features` | Screens and view models: Chat, Conversations, About; the Markdown renderer; Navigation 3 routes. Depends on the `ChatEngine` interface only. |
| `:app` | `Application` (wires the store and engine), `MainActivity`, manifest, launcher icon. |

Navigation uses Navigation 3: the back stack is plain data (`ConversationsRoute`, `ChatRoute`,
`SettingsRoute`), so a tablet list–detail layout can be added later with a `SceneStrategy`
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

## Licence

Apache License 2.0, see [LICENSE](LICENSE). The bundled Inter typeface is under the SIL Open Font
License 1.1, see [third_party/inter/OFL.txt](third_party/inter/OFL.txt).
