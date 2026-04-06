package com.openphone.agent.llm

class LlmInference {

    companion object {
        init {
            System.loadLibrary("llm_bridge")
        }
    }

    external fun loadModel(path: String, nThreads: Int, contextSize: Int): Boolean
    external fun generate(prompt: String): String
    external fun stopGeneration()
    external fun isModelLoaded(): Boolean
    external fun unloadModel()
}
