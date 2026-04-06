package com.openphone.agent.llm

class LocalLlmBackend : LlmBackend {

    private val inference = LlmInference()

    fun loadModel(path: String, nThreads: Int, contextSize: Int): Boolean {
        return inference.loadModel(path, nThreads, contextSize)
    }

    override fun isReady(): Boolean = inference.isModelLoaded()

    override fun generate(systemPrompt: String, userMessage: String): String {
        // Gemma chat template
        val prompt = "<start_of_turn>user\n$systemPrompt\n\n$userMessage<end_of_turn>\n<start_of_turn>model\n"
        return inference.generate(prompt)
    }

    override fun stop() = inference.stopGeneration()

    override fun release() = inference.unloadModel()
}
