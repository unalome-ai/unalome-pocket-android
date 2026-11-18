package ai.unalome.pocket.chatcore

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A fake engine that streams canned Markdown replies word by word, at about 30 tok/s.
 * Used on the emulator, in previews and in tests until a real on-device engine lands.
 *
 * The reply is picked by keyword ("table", "code", "think", "steps") or, failing that, by how many
 * user turns came before, so a conversation walks through the script and regenerating is deterministic.
 */
class ScriptedEngine(
    private val scripts: List<Script> = DEFAULT_SCRIPTS,
    private val tokenDelayMillis: Long = 33,
    maxContextTokens: Int = 4096,
    override val displayName: String = "Scripted demo",
    /** Milliseconds since some fixed point; injectable so tests do not depend on wall time. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : ChatEngine {

    /** One canned reply, with optional reasoning streamed before it in `<think>` tags. */
    data class Script(val answer: String, val reasoning: String? = null, val keywords: List<String> = emptyList())

    override val capabilities = EngineCapabilities(
        supportsVision = false,
        maxContextTokens = maxContextTokens,
        supportsThinking = true,
    )

    var isLoaded = false
        private set

    override suspend fun load(progress: (Double) -> Unit) {
        progress(1.0)
        isLoaded = true
    }

    override suspend fun unload() {
        isLoaded = false
    }

    fun scriptFor(turn: ChatTurn, history: List<ChatTurn>): Script {
        val prompt = turn.text.lowercase()
        scripts.firstOrNull { script -> script.keywords.any { prompt.contains(it) } }?.let { return it }
        val userTurnsBefore = history.count { it.role == ChatRole.USER }
        return scripts[userTurnsBefore % scripts.size]
    }

    override fun reply(turn: ChatTurn, history: List<ChatTurn>, options: GenerationOptions): Flow<GenerationEvent> = flow {
        val script = scriptFor(turn, history)
        val reasoning = script.reasoning ?: if (options.thinking) DEFAULT_REASONING else null
        val text = if (reasoning != null) "<think>\n$reasoning\n</think>\n\n${script.answer}" else script.answer
        val allTokens = tokenize(text)
        val tokens = allTokens.take(options.maxTokens.coerceAtLeast(0))
        val splitter = ReasoningSplitter()
        val startedAt = clock()
        var emitted = 0
        fun rate(): Double {
            val elapsed = (clock() - startedAt) / 1000.0
            return if (elapsed > 0) emitted / elapsed else 0.0
        }
        suspend fun send(segments: List<ReasoningSplitter.Segment>) = segments.forEach { segment ->
            when (segment) {
                is ReasoningSplitter.Segment.Reasoning -> emit(GenerationEvent.Reasoning(segment.text))
                is ReasoningSplitter.Segment.Answer -> emit(GenerationEvent.Token(segment.text))
            }
        }
        for ((index, token) in tokens.withIndex()) {
            // Paced against the start time, so time spent rendering does not slow the stream down.
            delay((startedAt + (index + 1) * tokenDelayMillis - clock()).coerceAtLeast(1))
            send(splitter.process(token))
            emitted++
            if (emitted % 8 == 0) emit(GenerationEvent.Stats(rate()))
        }
        send(splitter.finish())
        emit(GenerationEvent.Stats(rate()))
        if (tokens.size < allTokens.size) emit(GenerationEvent.LengthLimitReached)
        emit(GenerationEvent.Done)
    }

    companion object {
        /**
         * Splits text into word-sized tokens, each keeping its trailing whitespace,
         * so concatenating the tokens reproduces the text exactly.
         */
        fun tokenize(text: String): List<String> {
            val tokens = mutableListOf<String>()
            val current = StringBuilder()
            for (character in text) {
                if (!character.isWhitespace() && current.isNotEmpty() && current.last().isWhitespace()) {
                    tokens += current.toString()
                    current.clear()
                }
                current.append(character)
            }
            if (current.isNotEmpty()) tokens += current.toString()
            return tokens
        }

        const val DEFAULT_REASONING =
            "The user asks a question. I should answer briefly and clearly, and keep the example short enough to read on a small screen."

        val DEFAULT_SCRIPTS = listOf(
            Script(
                answer = """
                    Hi! I'm a **scripted demo engine**, so this reply is canned, but it streams just like a real model would.

                    Everything here runs *on this device*:
                    - no account
                    - no cloud
                    - no telemetry

                    Real models arrive in a later version; until then you can try the chat, **Stop**, **Regenerate** and **Copy**.
                """.trimIndent(),
                keywords = listOf("hello", "hi ", "privacy", "private"),
            ),
            Script(
                answer = """
                    For 5 km, cycling is usually the best balance:

                    | | Time | Cost | Exercise |
                    | --- | --- | --- | --- |
                    | Walk | 60 min | Free | High |
                    | Cycle | 18 min | Low | Medium |
                    | Bus | 25 min | Fare | Low |

                    Cycle on dry days, and keep the bus for rain or heavy bags.
                """.trimIndent(),
                reasoning = "5 km at walking pace, about 5 km/h, is an hour. Cycling at 16–18 km/h is under 20 minutes. " +
                    "A bus adds waiting and stops, so around 25 minutes. A small table compares them best.",
                keywords = listOf("think", "commute", "walk", "cycle"),
            ),
            Script(
                answer = """
                    Here is a small Kotlin example:

                    ```kotlin
                    engine.reply(turn, history = emptyList(), options)
                        .collect { event -> println(event) }
                    ```

                    ## What each part does

                    | Part | Role |
                    | --- | --- |
                    | `engine` | Runs the model on this device |
                    | `turn` | Your message |

                    > Tip: press **Stop** at any time. The text so far is kept.
                """.trimIndent(),
                keywords = listOf("code", "kotlin", "table", "example"),
            ),
            Script(
                answer = """
                    Good question. A local model answers in three steps:

                    1. Your message and the earlier turns become the prompt.
                    2. The model predicts one token at a time, on this phone's processor.
                    3. Each token appears here as soon as it is ready.

                    That is why you can press **Stop** at any moment and keep what was written so far.
                """.trimIndent(),
                keywords = listOf("how", "steps", "work"),
            ),
        )
    }
}
