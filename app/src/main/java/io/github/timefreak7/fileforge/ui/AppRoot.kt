package io.github.timefreak7.fileforge.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.timefreak7.fileforge.core.Format
import io.github.timefreak7.fileforge.core.Limits
import io.github.timefreak7.fileforge.core.Preset
import io.github.timefreak7.fileforge.core.Targets
import io.github.timefreak7.fileforge.image.ImageEngineClient
import io.github.timefreak7.fileforge.jobs.AppSettings
import io.github.timefreak7.fileforge.jobs.IncomingFile
import io.github.timefreak7.fileforge.jobs.JobQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot

private enum class Tab(val label: String) { Home("Home"), Jobs("Jobs"), Settings("Settings") }

private data class DonutState(
    val files: List<IncomingFile>,
    val center: Offset?,
    val dragPosition: Offset?,
    val hovered: Format? = null,
)

private data class OptionsState(val files: List<IncomingFile>, val target: Format)

/** Documents folder as the picker's starting point, so "Documents/Converted" is one tap away. */
private val DocumentsFolder: Uri =
    DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents")

@Composable
fun AppRoot(incoming: List<IncomingFile>?, onIncomingHandled: () -> Unit, debugTools: Boolean) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val density = LocalDensity.current
    val useList = rememberUseListInsteadOfDonut()

    var outputTree by remember { mutableStateOf(settings.outputTree) }
    var tab by remember { mutableStateOf(Tab.Home) }
    var donut by remember { mutableStateOf<DonutState?>(null) }
    var options by remember { mutableStateOf<OptionsState?>(null) }
    var selfTest by remember { mutableStateOf(false) }
    var pdfPages by remember { mutableStateOf<List<IncomingFile>?>(null) }
    val jobs by remember { JobQueue.observe(context) }.collectAsStateWithLifecycle(emptyList())

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            settings.outputTree = uri
            outputTree = uri
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    fun openDonut(files: List<IncomingFile>) {
        if (files.isEmpty()) return
        donut = DonutState(files.take(Limits.MAX_FILES_PER_JOB), center = null, dragPosition = null)
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch {
            openDonut(withContext(Dispatchers.IO) { uris.map { JobQueue.describe(context, it) } })
        }
    }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch { pdfPages = withContext(Dispatchers.IO) { uris.map { JobQueue.describe(context, it) } } }
    }

    // Shared or opened-with files go straight to the donut once an output folder exists.
    LaunchedEffect(incoming, outputTree) {
        if (incoming != null && outputTree != null) {
            openDonut(incoming)
            onIncomingHandled()
        }
    }

    suspend fun showQueued(what: String) {
        val result = snackbar.showSnackbar(what, actionLabel = "View")
        if (result == SnackbarResult.ActionPerformed) tab = Tab.Jobs
    }

    fun convert(files: List<IncomingFile>, target: Format, preset: Preset) {
        donut = null
        options = null
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        scope.launch {
            JobQueue.enqueue(context, files, target, preset)
            val what = if (files.size == 1) "1 file" else "${files.size} files"
            showQueued("${actionText(target, Targets.action(files.map { it.format }, target))}: $what")
        }
    }

    if (outputTree == null) {
        OutputFolderScreen(onChoose = { folderPicker.launch(DocumentsFolder) })
        return
    }
    pdfPages?.let { start ->
        PdfPagesScreen(start, onQueued = { scope.launch { showQueued(it) } }, onClose = { pdfPages = null })
        return
    }
    if (selfTest) {
        BackHandler { selfTest = false }
        SelfTestScreen(onClose = { selfTest = false })
        return
    }

    val order = settings.donutOrder
    BackHandler(enabled = donut != null) { donut = null }
    BackHandler(enabled = donut == null && tab != Tab.Home) { tab = Tab.Home }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            label = { Text(t.label) },
                            icon = {
                                Icon(
                                    when (t) {
                                        Tab.Home -> Icons.Filled.Home
                                        Tab.Jobs -> Icons.AutoMirrored.Filled.List
                                        Tab.Settings -> Icons.Filled.Settings
                                    },
                                    contentDescription = null,
                                )
                            },
                        )
                    }
                }
            },
        ) { padding ->
            when (tab) {
                Tab.Home -> HomeScreen(
                    recents = recentOutputs(jobs),
                    contentPadding = padding,
                    onPickFiles = { filePicker.launch(arrayOf("image/*", "application/pdf")) },
                    onEditPdf = { pdfPicker.launch(arrayOf("application/pdf")) },
                    drag = DonutDrag(
                        onStart = { file, p ->
                            donut = if (useList) DonutState(listOf(file), null, null) else DonutState(listOf(file), p, p)
                        },
                        onMove = { p -> donut = donut?.takeIf { it.dragPosition != null }?.copy(dragPosition = p) ?: donut },
                        onEnd = {
                            val d = donut
                            if (d?.dragPosition != null) {
                                val hovered = d.hovered
                                val centre = d.center ?: d.dragPosition
                                val lifted = d.dragPosition
                                val inHole = hypot(lifted.x - centre.x, lifted.y - centre.y) < with(density) { 52.dp.toPx() }
                                when {
                                    hovered != null -> convert(d.files, hovered, settings.defaultPreset(hovered))
                                    inHole -> donut = d.copy(dragPosition = null) // stays open: tap a format
                                    else -> donut = null // lifted outside the ring = cancel
                                }
                            }
                        },
                        onCancel = { if (donut?.dragPosition != null) donut = null },
                    ),
                )
                Tab.Jobs -> JobsScreen(jobs, padding)
                Tab.Settings -> SettingsScreen(
                    settings = settings,
                    contentPadding = padding,
                    onChooseFolder = { folderPicker.launch(outputTree ?: DocumentsFolder) },
                    onSelfTest = if (debugTools) ({ selfTest = true }) else null,
                )
            }
        }

        donut?.let { d ->
            val formats = d.files.map { it.format }
            val enabled = Targets.forInputs(formats, order, ImageEngineClient.heicEncodeAvailable).toSet()
            val label = { f: Format -> Targets.action(formats, f) }
            if (useList) {
                TargetListDialog(
                    targets = order.filter { it in enabled },
                    fileCount = d.files.size,
                    label = label,
                    onConvert = { convert(d.files, it, settings.defaultPreset(it)) },
                    onOptions = { options = OptionsState(d.files, it); donut = null },
                    onDismiss = { donut = null },
                )
            } else {
                DonutOverlay(
                    slots = order,
                    enabled = enabled,
                    center = d.center,
                    dragPosition = d.dragPosition,
                    onHover = { h -> donut = donut?.copy(hovered = h) },
                    onConvert = { convert(d.files, it, settings.defaultPreset(it)) },
                    onOptions = { options = OptionsState(d.files, it); donut = null },
                    onDismiss = { donut = null },
                    label = label,
                )
            }
        }
        options?.let { o ->
            OptionsSheet(
                target = o.target,
                inputs = o.files.map { it.format },
                defaults = settings.defaultPreset(o.target),
                onConvert = { preset -> convert(o.files, o.target, preset) },
                onDismiss = { options = null },
            )
        }
    }
}

/** First run: the one folder all outputs go to (spec: Donut behaviour). */
@Composable
private fun OutputFolderScreen(onChoose: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("FileForge", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            Text("Your Files, Your Way.", style = MaterialTheme.typography.titleMedium)
            Text(
                "Choose the folder where converted files will go. We suggest making a folder called " +
                    "\"Converted\" inside Documents. Your original files are never changed.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onChoose, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text("Choose folder")
            }
        }
    }
}
