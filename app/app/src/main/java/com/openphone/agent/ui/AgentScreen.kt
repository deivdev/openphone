package com.openphone.agent.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openphone.agent.AgentViewModel
import com.openphone.agent.LlmMode
import com.openphone.agent.accessibility.PhoneControlService
import com.openphone.agent.overlay.OverlayService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(viewModel: AgentViewModel) {
    val context = LocalContext.current
    val logEntries = viewModel.logEntries
    val isModelLoaded by viewModel.isModelLoaded
    val isModelLoading by viewModel.isModelLoading
    val modelName by viewModel.modelName
    val llmMode by viewModel.llmMode
    val groqApiKey by viewModel.groqApiKey
    val groqModel by viewModel.groqModel

    val isAccessibilityEnabled = remember {
        derivedStateOf { PhoneControlService.instance != null }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.loadModel(context, it) }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(logEntries.size) {
        if (logEntries.isNotEmpty()) {
            listState.animateScrollToItem(logEntries.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("OpenPhone Agent") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Mode selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = llmMode == LlmMode.LOCAL,
                    onClick = { viewModel.setMode(LlmMode.LOCAL) },
                    label = { Text("Local Model") },
                    modifier = Modifier.weight(1f),
                    enabled = true
                )
                FilterChip(
                    selected = llmMode == LlmMode.GROQ,
                    onClick = { viewModel.setMode(LlmMode.GROQ) },
                    label = { Text("Groq Cloud") },
                    modifier = Modifier.weight(1f),
                    enabled = true
                )
            }

            // Mode-specific config
            when (llmMode) {
                LlmMode.LOCAL -> {
                    StatusCard(
                        label = "Local Model",
                        status = when {
                            isModelLoading -> "Loading..."
                            isModelLoaded -> modelName
                            else -> "Not loaded"
                        },
                        isOk = isModelLoaded,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = if (!isModelLoaded && !isModelLoading) {
                            { filePicker.launch(arrayOf("*/*")) }
                        } else null
                    )
                }
                LlmMode.GROQ -> {
                    var keyInput by remember { mutableStateOf(groqApiKey) }

                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = {
                            keyInput = it
                            viewModel.setGroqApiKey(it)
                        },
                        label = { Text("Groq API Key") },
                        placeholder = { Text("gsk_...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        enabled = true
                    )

                    // Model picker
                    var modelExpanded by remember { mutableStateOf(false) }
                    val models = listOf(
                        "llama-3.3-70b-versatile",
                        "llama-3.1-8b-instant",
                        "gemma2-9b-it",
                        "mixtral-8x7b-32768",
                    )

                    ExposedDropdownMenuBox(
                        expanded = modelExpanded,
                        onExpandedChange = { modelExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = groqModel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Model") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            enabled = true
                        )
                        ExposedDropdownMenu(
                            expanded = modelExpanded,
                            onDismissRequest = { modelExpanded = false }
                        ) {
                            models.forEach { m ->
                                DropdownMenuItem(
                                    text = { Text(m) },
                                    onClick = {
                                        viewModel.setGroqModel(m)
                                        modelExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    if (groqApiKey.isNotBlank()) {
                        StatusCard(
                            label = "Groq",
                            status = modelName,
                            isOk = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // Accessibility status
            StatusCard(
                label = "Accessibility",
                status = if (isAccessibilityEnabled.value) "Enabled" else "Disabled",
                isOk = isAccessibilityEnabled.value,
                modifier = Modifier.fillMaxWidth(),
                onClick = if (!isAccessibilityEnabled.value) {
                    { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                } else null
            )

            // Launch overlay button
            val canLaunch = isModelLoaded && isAccessibilityEnabled.value
            Button(
                onClick = {
                    // Check overlay permission
                    if (!Settings.canDrawOverlays(context)) {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                        return@Button
                    }

                    // Start overlay service and minimize app
                    context.startForegroundService(
                        Intent(context, OverlayService::class.java)
                    )
                    // Go to home screen — the overlay stays on top
                    val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(homeIntent)
                },
                enabled = canLaunch,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (!Settings.canDrawOverlays(context)) "Grant Overlay Permission" else "Launch Overlay")
            }

            if (!canLaunch) {
                Text(
                    "Configure the model and enable accessibility above, then launch the overlay to use the agent over other apps.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Log (from main app, for setup feedback)
            if (logEntries.isNotEmpty()) {
                Text(
                    "Log",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.shapes.medium
                        )
                        .padding(8.dp)
                ) {
                    items(logEntries) { entry ->
                        Text(
                            text = entry,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatusCard(
    label: String,
    status: String,
    isOk: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (isOk)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.errorContainer
        ),
        onClick = onClick ?: {}
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (isOk)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = if (isOk)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onErrorContainer
            )
            if (!isOk && onClick != null) {
                Text(
                    "Tap to configure",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isOk)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}
