package com.openphone.agent.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.openphone.agent.AgentViewModel
import com.openphone.agent.InstalledModelInfo
import com.openphone.agent.accessibility.PhoneControlService
import com.openphone.agent.llm.CatalogModel
import com.openphone.agent.llm.DownloadProgress
import com.openphone.agent.llm.ModelCatalog
import com.openphone.agent.llm.ModelImport
import com.openphone.agent.llm.ModelState
import com.openphone.agent.overlay.OverlayService

@Composable
fun AgentScreen(viewModel: AgentViewModel) {
    val context = LocalContext.current
    val modelState by viewModel.modelState.collectAsState()
    val backend by viewModel.backend.collectAsState()
    val installed by viewModel.installed.collectAsState()
    val download by viewModel.download.collectAsState()
    val modelImport by viewModel.modelImport.collectAsState()

    // Both are granted in system settings, so they are re-read whenever the app comes back.
    var accessibilityOn by remember { mutableStateOf(PhoneControlService.instance != null) }
    var overlayAllowed by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    LifecycleResumeEffect(Unit) {
        accessibilityOn = PhoneControlService.instance != null
        overlayAllowed = Settings.canDrawOverlays(context)
        onPauseOrDispose { }
    }

    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importModel(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp),
    ) {
        ScreenHeader("OpenPhone", modifier = Modifier.padding(horizontal = 4.dp))
        MonoLabel("On-device phone agent", modifier = Modifier.padding(horizontal = 4.dp))

        Column(
            modifier = Modifier.padding(vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Section("Model") {
                installed?.let { model ->
                    if (modelState == ModelState.ERROR) {
                        Panel {
                            MonoLabel("Model failed to load", color = MaterialTheme.colorScheme.error)
                            viewModel.lastError()?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            PillButton("Delete model", onClick = viewModel::deleteCurrentModel, outlined = true)
                        }
                    } else {
                        ModelCard(
                            model = model,
                            state = modelState,
                            backend = backend,
                            onLoad = viewModel::warmUp,
                            onDelete = viewModel::deleteCurrentModel,
                        )
                    }
                }
                InstallPanel(
                    installedName = installed?.name,
                    download = download,
                    modelImport = modelImport,
                    onDownload = viewModel::startDownload,
                    onCancel = viewModel::cancelDownload,
                    onImport = { modelPicker.launch(arrayOf("*/*")) },
                )
            }

            Section("Permissions") {
                Panel {
                    PermissionRow(
                        title = "Accessibility service",
                        body = "Reads the screen and performs taps, swipes and typing.",
                        granted = accessibilityOn,
                        onGrant = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    )
                    PermissionRow(
                        title = "Display over other apps",
                        body = "Shows the agent panel on top of the app it is driving.",
                        granted = overlayAllowed,
                        onGrant = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        },
                    )
                }
            }

            val canLaunch = installed != null && accessibilityOn && overlayAllowed
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(
                    text = "Launch overlay",
                    onClick = { launchOverlay(context) },
                    accent = true,
                    enabled = canLaunch,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!canLaunch) {
                    Text(
                        "Install a model and grant both permissions, then launch the overlay to use the agent over other apps.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }

            Section("About") {
                Panel {
                    Text(
                        "The model runs on this phone with LiteRT-LM: nothing you ask and nothing on screen leaves the device.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    MonoLabel("Fonts: Instrument Serif, Space Grotesk, Space Mono (OFL)")
                }
            }
        }
    }
}

/** Starts the overlay and goes home, so the panel floats over whatever the user opens next. */
private fun launchOverlay(context: Context) {
    context.startForegroundService(Intent(context, OverlayService::class.java))
    context.startActivity(
        Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        },
    )
}

@Composable
private fun ModelCard(
    model: InstalledModelInfo,
    state: ModelState,
    backend: String?,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
) {
    Panel {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when (state) {
                ModelState.LOADING -> WorkingDots(dotSize = 5.dp)
                ModelState.READY -> Dot(MaterialTheme.colorScheme.tertiary)
                else -> Dot(MaterialTheme.colorScheme.onSurfaceVariant)
            }
            MonoLabel(
                when (state) {
                    ModelState.LOADING -> "Loading"
                    ModelState.READY -> "Loaded · ${backend ?: "?"}"
                    else -> "Idle · loads on first use"
                },
                color = if (state == ModelState.READY) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            ModelCatalog.byFileName(model.name)?.displayName ?: model.name.substringBeforeLast('.'),
            style = MaterialTheme.typography.titleMedium,
        )
        MonoLabel("${model.name} · ${model.sizeMB} MB")
        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state == ModelState.IDLE) PillButton("Load now", onClick = onLoad)
            PillButton("Delete", onClick = onDelete, outlined = true, enabled = state != ModelState.LOADING)
        }
    }
}

@Composable
private fun InstallPanel(
    installedName: String?,
    download: DownloadProgress,
    modelImport: ModelImport,
    onDownload: (CatalogModel) -> Unit,
    onCancel: () -> Unit,
    onImport: () -> Unit,
) {
    Panel {
        MonoLabel(if (installedName != null) "Other models" else "Install a model")
        Text(
            "Gemma 4 in LiteRT-LM format, from Hugging Face. Runs on the GPU when the phone supports it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (download) {
            is DownloadProgress.Running -> {
                MonoLabel(
                    "Downloading ${download.model.displayName} · " +
                        "${download.bytesDownloaded / 1_000_000} / ${download.totalBytes / 1_000_000} MB",
                )
                val fraction = if (download.totalBytes > 0) download.bytesDownloaded.toFloat() / download.totalBytes else 0f
                LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.tertiary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
                PillButton("Cancel", onClick = onCancel, outlined = true)
            }
            else -> {
                if (download is DownloadProgress.Failed) {
                    MonoLabel("Download ${download.reason}", color = MaterialTheme.colorScheme.error)
                }
                ModelCatalog.models
                    .filter { it.fileName != installedName }
                    .forEach { model -> CatalogRow(model, onDownload) }
            }
        }
        when (modelImport) {
            ModelImport.Running -> Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WorkingDots()
                MonoLabel("Copying model file")
            }
            is ModelImport.Rejected -> MonoLabel(
                "Not a .litertlm model: ${modelImport.fileName ?: "?"}",
                color = MaterialTheme.colorScheme.error,
            )
            is ModelImport.Failed -> MonoLabel("Import failed: ${modelImport.reason}", color = MaterialTheme.colorScheme.error)
            is ModelImport.Imported, ModelImport.Idle -> Unit
        }
        PillButton(
            "Import .litertlm file",
            onClick = onImport,
            outlined = true,
            enabled = modelImport != ModelImport.Running,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun CatalogRow(model: CatalogModel, onDownload: (CatalogModel) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(model.displayName, style = MaterialTheme.typography.titleSmall)
            MonoLabel("${model.sizeMB} MB · RAM ${model.ramHint}")
            Text(
                model.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PillButton("Get", onClick = { onDownload(model) })
    }
}

@Composable
private fun PermissionRow(title: String, body: String, granted: Boolean, onGrant: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Dot(
            if (granted) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline,
            size = 8.dp,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (granted) {
            MonoLabel("On", color = MaterialTheme.colorScheme.tertiary)
        } else {
            PillButton("Grant", onClick = onGrant)
        }
    }
}
