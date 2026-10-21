package com.openphone.agent.llm

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

sealed class DownloadProgress {
    object Idle : DownloadProgress()
    data class Running(val model: CatalogModel, val bytesDownloaded: Long, val totalBytes: Long) : DownloadProgress()
    data class Done(val model: CatalogModel) : DownloadProgress()
    data class Failed(val reason: String) : DownloadProgress()
}

/** A download in flight; it survives the app being closed, as DownloadManager runs it. */
data class PendingDownload(val model: CatalogModel, val id: Long)

class ModelDownloader(private val context: Context, private val modelManager: ModelManager) {

    private val dm by lazy {
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    }

    private val prefs = context.getSharedPreferences("model_download", Context.MODE_PRIVATE)

    private val downloadDir: File
        get() = File(context.externalCacheDir ?: context.cacheDir, "model-downloads").apply { mkdirs() }

    /** The download started earlier and not yet moved in, e.g. before the app was closed. */
    fun pending(): PendingDownload? {
        val model = prefs.getString(KEY_MODEL, null)?.let { ModelCatalog.byFileName(it) } ?: return null
        val id = prefs.getLong(KEY_ID, -1L).takeIf { it >= 0 } ?: return null
        return PendingDownload(model, id)
    }

    fun start(model: CatalogModel): PendingDownload {
        pending()?.let { cancel(it) }
        val dest = File(downloadDir, model.fileName)
        if (dest.exists()) dest.delete()
        val req = DownloadManager.Request(Uri.parse(model.url)).apply {
            setTitle("${model.displayName} · ${model.fileName}")
            setDescription("Downloading model for OpenPhone")
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            setDestinationUri(Uri.fromFile(dest))
        }
        val download = PendingDownload(model, dm.enqueue(req))
        prefs.edit().putString(KEY_MODEL, model.fileName).putLong(KEY_ID, download.id).apply()
        return download
    }

    fun cancel(download: PendingDownload) {
        dm.remove(download.id)
        forget()
    }

    /** Polls [download]; the file is moved into the model directory only once it is complete. */
    fun observe(download: PendingDownload, pollMs: Long = 500): Flow<DownloadProgress> = flow {
        val model = download.model
        val expectedBytes = model.sizeMB.toLong() * 1_000_000
        while (true) {
            val row = query(download.id) ?: run {
                forget()
                emit(DownloadProgress.Failed("download not found"))
                return@flow
            }
            when (row.status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val file = row.localFile ?: run {
                        forget()
                        emit(DownloadProgress.Failed("downloaded file missing"))
                        return@flow
                    }
                    moveIntoModelDir(file)
                    forget()
                    modelManager.refresh()
                    emit(DownloadProgress.Done(model))
                    return@flow
                }
                DownloadManager.STATUS_FAILED -> {
                    forget()
                    emit(DownloadProgress.Failed("failed (reason=${row.reason})"))
                    return@flow
                }
                else -> emit(DownloadProgress.Running(model, row.bytesSoFar, row.totalBytes.takeIf { it > 0 } ?: expectedBytes))
            }
            delay(pollMs)
        }
    }.flowOn(Dispatchers.IO)

    private fun forget() {
        prefs.edit().clear().apply()
    }

    private class Row(val status: Int, val reason: Int, val bytesSoFar: Long, val totalBytes: Long, val localFile: File?)

    private fun query(id: Long): Row? =
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) return null
            val localUri = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
            Row(
                status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                bytesSoFar = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                totalBytes = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                localFile = localUri?.let { Uri.parse(it).path }?.let { File(it) }?.takeIf { it.exists() },
            )
        }

    private fun moveIntoModelDir(src: File) {
        val dest = File(modelManager.modelDir, src.name)
        if (dest.exists()) dest.delete()
        if (!src.renameTo(dest)) {
            // cross-volume fallback: copy + delete
            src.inputStream().use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            src.delete()
        }
        // The newest model file is the active one.
        dest.setLastModified(System.currentTimeMillis())
        Log.i(TAG, "Model file moved to ${dest.absolutePath}")
    }

    companion object {
        private const val TAG = "ModelDownloader"
        private const val KEY_MODEL = "model"
        private const val KEY_ID = "id"
    }
}
