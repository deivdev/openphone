package com.openphone.agent.llm

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig

/**
 * On-device backend using Google LiteRT-LM (.litertlm models, e.g. Gemma-3n).
 * Tries the GPU backend first, falls back to CPU.
 */
class LitertLmBackend : LlmBackend {

    private var engine: Engine? = null
    @Volatile
    private var activeConversation: Conversation? = null

    var backendName: String = ""
        private set

    fun loadModel(path: String, contextSize: Int): Boolean {
        release()
        for (backend in listOf(Backend.GPU(), Backend.CPU())) {
            try {
                val candidate = Engine(
                    EngineConfig(
                        modelPath = path,
                        backend = backend,
                        maxNumTokens = contextSize
                    )
                )
                candidate.initialize()
                engine = candidate
                backendName = backend.name
                return true
            } catch (t: Throwable) {
                // GPU init can fail on unsupported devices — try the next backend
            }
        }
        return false
    }

    override fun isReady(): Boolean = engine?.isInitialized() == true

    override fun generate(systemPrompt: String, userMessage: String): String {
        val engine = engine ?: return "[Error: model not loaded]"
        return try {
            engine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(systemPrompt),
                    samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0)
                )
            ).use { conversation ->
                activeConversation = conversation
                conversation.sendMessage(userMessage).toString()
            }
        } catch (t: Throwable) {
            "[Error: ${t.message}]"
        } finally {
            activeConversation = null
        }
    }

    override fun stop() {
        try {
            activeConversation?.cancelProcess()
        } catch (_: Throwable) {
        }
    }

    override fun release() {
        try {
            engine?.close()
        } catch (_: Throwable) {
        }
        engine = null
        backendName = ""
    }
}
