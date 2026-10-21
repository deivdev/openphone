package com.openphone.agent.llm

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig

/**
 * A loaded LiteRT-LM model (.litertlm, e.g. Gemma 4). Not thread-safe: [ModelManager] owns the
 * single instance and serializes [generate]; only [cancel] may be called from another thread.
 */
class LitertLmEngine private constructor(
    private val engine: Engine,
    val backendName: String,
) : AutoCloseable {

    @Volatile
    private var activeConversation: Conversation? = null

    /** Blocking. Greedy sampling: the agent wants the same action for the same screen. */
    fun generate(systemPrompt: String, userMessage: String): String =
        engine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(systemPrompt),
                samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
            ),
        ).use { conversation ->
            activeConversation = conversation
            try {
                conversation.sendMessage(userMessage).contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString("") { it.text }
            } finally {
                activeConversation = null
            }
        }

    /** Stops a running [generate] from another thread. */
    fun cancel() {
        runCatching { activeConversation?.cancelProcess() }
    }

    override fun close() {
        runCatching { engine.close() }
    }

    companion object {
        /** Tries the GPU first and falls back to the CPU, as GPU init fails on some devices. */
        fun load(modelPath: String, contextSize: Int, cacheDir: String): LitertLmEngine {
            var lastError: Throwable? = null
            for (backend in listOf(Backend.GPU(), Backend.CPU())) {
                try {
                    val engine = Engine(
                        EngineConfig(
                            modelPath = modelPath,
                            backend = backend,
                            maxNumTokens = contextSize,
                            cacheDir = cacheDir,
                        ),
                    )
                    engine.initialize()
                    return LitertLmEngine(engine, backend.name)
                } catch (t: Throwable) {
                    lastError = t
                }
            }
            throw lastError ?: IllegalStateException("No LiteRT-LM backend available")
        }
    }
}
