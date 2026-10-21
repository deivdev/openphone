package com.openphone.agent.llm

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

sealed class ModelImport {
    object Idle : ModelImport()
    object Running : ModelImport()
    data class Imported(val fileName: String) : ModelImport()
    /** Not a LiteRT-LM model file (.litertlm / .task). */
    data class Rejected(val fileName: String?) : ModelImport()
    data class Failed(val reason: String) : ModelImport()
}

enum class ModelState {
    /** No model file on disk. */
    NOT_INSTALLED,

    /** Installed but not in memory; it is loaded on first use. */
    IDLE,
    LOADING,
    READY,
    ERROR,
}

/** The model cannot run right now; not the fault of the request that needed it. */
open class ModelUnavailableException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

class ModelNotInstalledException : ModelUnavailableException("No model installed")

class ModelLoadException(cause: Throwable) : ModelUnavailableException("Model failed to load: ${cause.message}", cause)

/**
 * Owns the single [LitertLmEngine], shared by the app screen and the overlay. The model is loaded
 * on demand, used by one caller at a time and released after [IDLE_UNLOAD_MS] without use, so a
 * multi-GB model does not sit in RAM.
 */
class ModelManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(ModelState.NOT_INSTALLED)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    /** "GPU" or "CPU" while a model is loaded, else null. */
    private val _backend = MutableStateFlow<String?>(null)
    val backend: StateFlow<String?> = _backend.asStateFlow()

    @Volatile
    var lastError: String? = null
        private set

    private val mutex = Mutex()

    @Volatile
    private var engine: LitertLmEngine? = null
    private var engineFile: File? = null
    private var unloadJob: Job? = null

    val modelDir: File
        get() = File(context.filesDir, "models").also { it.mkdirs() }

    init {
        adoptLegacyModels()
        refresh()
    }

    /** The newest model file in [modelDir]: the active one. */
    fun installed(): File? =
        modelDir.listFiles { f -> ModelCatalog.isModelFile(f.name) }.orEmpty().maxByOrNull { it.lastModified() }

    /** Re-reads [modelDir] after files were added or removed. */
    fun refresh() {
        if (engine != null) return
        _state.value = if (installed() != null) ModelState.IDLE else ModelState.NOT_INSTALLED
    }

    /** One completion for the agent. Callers are serialized; the model is loaded first if needed. */
    suspend fun generate(systemPrompt: String, userMessage: String): String = mutex.withLock {
        unloadJob?.cancel()
        try {
            val loaded = ensureLoaded()
            withContext(Dispatchers.IO) { loaded.generate(systemPrompt, userMessage) }
        } finally {
            scheduleUnload()
        }
    }

    /** Loads the model ahead of the first request, so the agent's first step is not the slow one. */
    suspend fun warmUp() {
        mutex.withLock {
            unloadJob?.cancel()
            try {
                ensureLoaded()
            } finally {
                scheduleUnload()
            }
        }
    }

    /** Stops a running [generate]; safe from any thread. */
    fun cancel() {
        engine?.cancel()
    }

    /** Frees the model now, e.g. when the overlay closes. */
    suspend fun release() {
        mutex.withLock {
            unloadJob?.cancel()
            closeEngine()
        }
    }

    /** Copies a picked .litertlm in, under a temporary name until it is complete. */
    suspend fun importModel(uri: Uri): ModelImport = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        if (name == null || !ModelCatalog.isModelFile(name)) return@withContext ModelImport.Rejected(name)
        val dest = File(modelDir, name)
        val partial = File(modelDir, "$name.part")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                partial.outputStream().use { output -> input.copyTo(output, bufferSize = 8 * 1024 * 1024) }
            } ?: error("Cannot open $uri")
            mutex.withLock { closeEngine() }
            if (dest.exists()) dest.delete()
            if (!partial.renameTo(dest)) error("Cannot move $name in place")
            dest.setLastModified(System.currentTimeMillis())
            Log.i(TAG, "Model imported: ${dest.name} (${dest.length() / 1_000_000} MB)")
            refresh()
            ModelImport.Imported(name)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to import model", t)
            partial.delete()
            ModelImport.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    /** Deletes the active model; the next newest one, if any, takes its place. */
    suspend fun deleteCurrentModel(): Boolean = withContext(Dispatchers.IO) {
        val model = installed() ?: return@withContext false
        mutex.withLock { closeEngine() }
        val deleted = model.delete()
        refresh()
        deleted
    }

    private suspend fun ensureLoaded(): LitertLmEngine {
        val model = installed() ?: throw ModelNotInstalledException()
        engine?.let { loaded ->
            if (engineFile == model) return loaded
            closeEngine()
        }
        _state.value = ModelState.LOADING
        return try {
            // Native loading cannot be interrupted, and withContext drops its result when the caller
            // is cancelled meanwhile. The engine is kept inside the block so it cannot leak
            // gigabytes of weights; the idle timer unloads it.
            withContext(Dispatchers.IO) {
                LitertLmEngine.load(
                    modelPath = model.absolutePath,
                    contextSize = CONTEXT_TOKENS,
                    cacheDir = context.cacheDir.absolutePath,
                ).also { loaded ->
                    engine = loaded
                    engineFile = model
                    lastError = null
                    _backend.value = loaded.backendName
                    _state.value = ModelState.READY
                    Log.i(TAG, "Loaded ${model.name} on ${loaded.backendName}")
                }
            }
        } catch (e: CancellationException) {
            refresh()
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load ${model.name}", t)
            lastError = t.message
            _state.value = ModelState.ERROR
            throw ModelLoadException(t)
        }
    }

    private fun scheduleUnload() {
        unloadJob = scope.launch {
            delay(IDLE_UNLOAD_MS)
            mutex.withLock { closeEngine() }
            Log.i(TAG, "Model unloaded after idle timeout")
        }
    }

    /** Caller must hold [mutex]. */
    private fun closeEngine() {
        engine?.close()
        engine = null
        engineFile = null
        _backend.value = null
        refresh()
    }

    /** Earlier versions copied picked models straight into filesDir. */
    private fun adoptLegacyModels() {
        context.filesDir.listFiles { f -> f.isFile && ModelCatalog.isModelFile(f.name) }.orEmpty().forEach { f ->
            val dest = File(modelDir, f.name)
            if (!dest.exists() && f.renameTo(dest)) Log.i(TAG, "Moved legacy model ${f.name} into ${modelDir.name}/")
        }
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }

    companion object {
        private const val TAG = "ModelManager"
        private const val CONTEXT_TOKENS = 4096

        // Longer than Dear's minute: reloading costs seconds and the agent works in bursts.
        private const val IDLE_UNLOAD_MS = 3 * 60_000L
    }
}
