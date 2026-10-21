package com.openphone.agent

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openphone.agent.llm.CatalogModel
import com.openphone.agent.llm.DownloadProgress
import com.openphone.agent.llm.ModelImport
import com.openphone.agent.llm.ModelState
import com.openphone.agent.llm.PendingDownload
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class InstalledModelInfo(val name: String, val sizeMB: Long)

/** Setup screen: installing the model and checking the permissions the overlay needs. */
class AgentViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as OpenPhoneApp
    private val modelManager = app.modelManager
    private val downloader = app.modelDownloader

    val modelState: StateFlow<ModelState> = modelManager.state
    val backend: StateFlow<String?> = modelManager.backend

    private val _installed = MutableStateFlow(modelManager.installed()?.toInfo())
    val installed: StateFlow<InstalledModelInfo?> = _installed.asStateFlow()

    private val _download = MutableStateFlow<DownloadProgress>(DownloadProgress.Idle)
    val download: StateFlow<DownloadProgress> = _download.asStateFlow()

    private val _modelImport = MutableStateFlow<ModelImport>(ModelImport.Idle)
    val modelImport: StateFlow<ModelImport> = _modelImport.asStateFlow()

    private var pendingDownload: PendingDownload? = null
    private var downloadJob: Job? = null

    init {
        // A download started in an earlier session keeps running in DownloadManager.
        downloader.pending()?.let { observe(it) }
    }

    fun lastError(): String? = modelManager.lastError

    fun startDownload(model: CatalogModel) {
        observe(downloader.start(model))
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        pendingDownload?.let { downloader.cancel(it) }
        pendingDownload = null
        _download.value = DownloadProgress.Idle
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            _modelImport.value = ModelImport.Running
            _modelImport.value = modelManager.importModel(uri)
            refreshInstalled()
        }
    }

    fun deleteCurrentModel() {
        viewModelScope.launch {
            modelManager.deleteCurrentModel()
            refreshInstalled()
        }
    }

    /** Loads the model now, to see that it works and which backend it got. */
    fun warmUp() {
        viewModelScope.launch {
            runCatching { modelManager.warmUp() }
                .onFailure { Log.w(LOG_TAG, "Warm-up failed: ${it.message}") }
        }
    }

    private fun observe(download: PendingDownload) {
        downloadJob?.cancel()
        pendingDownload = download
        downloadJob = viewModelScope.launch {
            downloader.observe(download).collect { progress ->
                _download.value = progress
                if (progress is DownloadProgress.Done || progress is DownloadProgress.Failed) {
                    pendingDownload = null
                    refreshInstalled()
                }
            }
        }
    }

    private fun refreshInstalled() {
        _installed.value = modelManager.installed()?.toInfo()
    }

    private fun File.toInfo() = InstalledModelInfo(name, length() / 1_000_000)

    companion object {
        const val LOG_TAG = "OpenPhoneAgent"
    }
}
