package com.openphone.agent.llm

interface LlmBackend {
    fun isReady(): Boolean
    fun generate(systemPrompt: String, userMessage: String): String
    fun stop()
    fun release()
}
