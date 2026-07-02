package com.openphone.agent

import android.content.Context
import android.net.Uri
import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openphone.agent.agent.AgentLoop
import com.openphone.agent.llm.GroqBackend
import com.openphone.agent.llm.LitertLmBackend
import com.openphone.agent.llm.LlmBackend
import com.openphone.agent.llm.LocalLlmBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

enum class LlmMode { LOCAL, GROQ }

class AgentViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("openphone", Context.MODE_PRIVATE)

    private var llamaCppBackend = LocalLlmBackend()
    private var litertBackend = LitertLmBackend()
    private var activeLocalBackend: LlmBackend = llamaCppBackend
    private var groqBackend: GroqBackend? = null
    private var currentBackend: LlmBackend = activeLocalBackend

    private val agentLoop = AgentLoop(currentBackend) { step ->
        addLog("[${step.step}/${step.maxSteps}] ${step.status}")
    }

    val logEntries = mutableStateListOf<String>()
    val isModelLoaded = mutableStateOf(false)
    val isModelLoading = mutableStateOf(false)
    val isRunning = mutableStateOf(false)
    val modelName = mutableStateOf("")
    val llmMode = mutableStateOf(LlmMode.valueOf(prefs.getString("llm_mode", "LOCAL")!!))
    val groqApiKey = mutableStateOf(prefs.getString("groq_api_key", "") ?: "")
    val groqModel = mutableStateOf(prefs.getString("groq_model", "llama-3.3-70b-versatile") ?: "llama-3.3-70b-versatile")

    init {
        when (llmMode.value) {
            LlmMode.LOCAL -> {
                val savedPath = prefs.getString("model_path", null)
                val savedName = prefs.getString("model_name", null)
                if (savedPath != null && savedName != null && File(savedPath).exists()) {
                    loadModelFromPath(savedPath, savedName)
                }
            }
            LlmMode.GROQ -> {
                if (groqApiKey.value.isNotBlank()) {
                    applyGroqConfig()
                }
            }
        }
    }

    private fun addLog(msg: String) {
        android.util.Log.i("OpenPhoneAgent", msg)
        logEntries.add(msg)
    }

    // --- Mode switching ---

    fun setMode(mode: LlmMode) {
        llmMode.value = mode
        prefs.edit().putString("llm_mode", mode.name).apply()

        when (mode) {
            LlmMode.LOCAL -> {
                currentBackend = activeLocalBackend
                agentLoop.setBackend(currentBackend)
                isModelLoaded.value = activeLocalBackend.isReady()
                modelName.value = prefs.getString("model_name", "") ?: ""
                if (!activeLocalBackend.isReady()) {
                    val savedPath = prefs.getString("model_path", null)
                    val savedName = prefs.getString("model_name", null)
                    if (savedPath != null && savedName != null && File(savedPath).exists()) {
                        loadModelFromPath(savedPath, savedName)
                    }
                }
            }
            LlmMode.GROQ -> {
                applyGroqConfig()
            }
        }
    }

    fun setGroqApiKey(key: String) {
        groqApiKey.value = key
        prefs.edit().putString("groq_api_key", key).apply()
        if (llmMode.value == LlmMode.GROQ) {
            applyGroqConfig()
        }
    }

    fun setGroqModel(model: String) {
        groqModel.value = model
        prefs.edit().putString("groq_model", model).apply()
        if (llmMode.value == LlmMode.GROQ) {
            applyGroqConfig()
        }
    }

    private fun applyGroqConfig() {
        val key = groqApiKey.value
        if (key.isBlank()) {
            isModelLoaded.value = false
            modelName.value = ""
            return
        }
        groqBackend = GroqBackend(key, groqModel.value)
        currentBackend = groqBackend!!
        agentLoop.setBackend(currentBackend)
        isModelLoaded.value = true
        modelName.value = "Groq: ${groqModel.value}"
    }

    // --- Local model loading ---

    private fun isLitertModel(fileName: String): Boolean =
        fileName.endsWith(".litertlm", ignoreCase = true) ||
            fileName.endsWith(".task", ignoreCase = true)

    /** Loads the model with the engine matching its extension. Returns an engine label, or null on failure. */
    private fun loadLocalModel(path: String): String? {
        return if (isLitertModel(path)) {
            if (litertBackend.loadModel(path, contextSize = 4096)) {
                activeLocalBackend = litertBackend
                "LiteRT-LM/${litertBackend.backendName}"
            } else null
        } else {
            if (llamaCppBackend.loadModel(path, nThreads = 4, contextSize = 4096)) {
                activeLocalBackend = llamaCppBackend
                "llama.cpp/CPU"
            } else null
        }
    }

    private fun loadModelFromPath(path: String, name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            isModelLoading.value = true
            addLog("Loading saved model: $name")

            try {
                val engine = loadLocalModel(path)
                if (engine != null) {
                    isModelLoaded.value = true
                    modelName.value = name
                    currentBackend = activeLocalBackend
                    agentLoop.setBackend(currentBackend)
                    addLog("Model loaded: $name ($engine)")
                } else {
                    addLog("Error: Failed to load saved model")
                    prefs.edit().remove("model_path").remove("model_name").apply()
                }
            } catch (e: Exception) {
                addLog("Error: ${e.message}")
                prefs.edit().remove("model_path").remove("model_name").apply()
            } finally {
                isModelLoading.value = false
            }
        }
    }

    fun loadModel(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            isModelLoading.value = true
            addLog("Loading model...")

            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    addLog("Error: Could not open model file")
                    isModelLoading.value = false
                    return@launch
                }

                val fileName = getFileName(context, uri)
                val modelFile = File(context.filesDir, fileName)

                // Re-copy if missing or size differs (e.g. stale copy of a partial download)
                val sourceSize = context.contentResolver
                    .openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
                if (!modelFile.exists() || modelFile.length() != sourceSize) {
                    addLog("Copying model to app storage, please wait...")
                    inputStream.use { input ->
                        modelFile.outputStream().use { output ->
                            input.copyTo(output, bufferSize = 8 * 1024 * 1024)
                        }
                    }
                    addLog("Copy complete")
                } else {
                    inputStream.close()
                    addLog("Model already in app storage")
                }

                addLog("Initializing LLM (this may take a moment)...")
                val engine = loadLocalModel(modelFile.absolutePath)

                if (engine != null) {
                    isModelLoaded.value = true
                    modelName.value = fileName
                    currentBackend = activeLocalBackend
                    agentLoop.setBackend(currentBackend)
                    addLog("Model loaded: $fileName ($engine)")

                    prefs.edit()
                        .putString("model_path", modelFile.absolutePath)
                        .putString("model_name", fileName)
                        .apply()
                } else {
                    addLog("Error: Failed to load model")
                }
            } catch (e: Exception) {
                addLog("Error: ${e.message}")
            } finally {
                isModelLoading.value = false
            }
        }
    }

    // --- Agent ---

    fun runAgent(goal: String) {
        viewModelScope.launch {
            isRunning.value = true
            addLog("--- Goal: $goal (${llmMode.value}) ---")

            try {
                agentLoop.run(goal)
            } catch (e: Exception) {
                addLog("Error: ${e.message}")
            } finally {
                isRunning.value = false
            }
        }
    }

    fun stopAgent() {
        agentLoop.stop()
        addLog("Stopped by user")
    }

    override fun onCleared() {
        super.onCleared()
        llamaCppBackend.release()
        litertBackend.release()
        groqBackend?.release()
    }

    private fun getFileName(context: Context, uri: Uri): String {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    return it.getString(nameIndex)
                }
            }
        }
        return "model.gguf"
    }
}
