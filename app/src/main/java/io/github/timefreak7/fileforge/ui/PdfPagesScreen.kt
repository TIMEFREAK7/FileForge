package io.github.timefreak7.fileforge.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.timefreak7.fileforge.core.Format
import io.github.timefreak7.fileforge.image.ParserFailure
import io.github.timefreak7.fileforge.jobs.AppSettings
import io.github.timefreak7.fileforge.jobs.IncomingFile
import io.github.timefreak7.fileforge.jobs.JobQueue
import io.github.timefreak7.fileforge.pdf.PageRef
import io.github.timefreak7.fileforge.pdf.PdfEngineClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File


/** One page tile; [key] keeps selection attached to the page while pages move. */
private data class PageItem(val key: Int, val ref: PageRef)

/**
 * The PDF organiser (spec: page tools): rotate, delete, reorder and add pages, then save them as one PDF,
 * extract the selected pages, or split before each selected page. Saving queues a normal job.
 */
@Composable
fun PdfPagesScreen(start: List<IncomingFile>, onQueued: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { PdfEngineClient(context) }
    val dir = remember { File(context.cacheDir, "organiser").apply { deleteRecursively(); mkdirs() } }
    // Thumbnails render one at a time so a fast scroll does not queue hundreds of renders at once.
    val renderLock = remember { Mutex() }
    DisposableEffect(Unit) {
        onDispose {
            engine.close()
            dir.deleteRecursively()
        }
    }

    val pdfs = remember { mutableStateListOf<LoadedPdf>() }
    val items = remember { mutableStateListOf<PageItem>() }
    var selected by remember { mutableStateOf(setOf<Int>()) }
    var nextKey by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf<PasswordAsk?>(null) }
    // S14: passwords stay in memory on this screen and in the job; this choice applies to the saved PDF.
    var removePassword by remember { mutableStateOf(false) }

    suspend fun askPassword(request: PasswordRequest): Pair<String, Boolean>? =
        PasswordAsk(request, CompletableDeferred()).also { asking = it }.answer.await().also { asking = null }

    fun add(files: List<IncomingFile>) {
        scope.launch {
            loading = true
            val errors = mutableListOf<String>()
            for (f in files) {
                try {
                    val loaded = loadPdf(context, engine, f, dir, ::askPassword) ?: continue
                    if (loaded.removePassword) removePassword = true
                    val input = pdfs.size
                    pdfs += loaded
                    repeat(loaded.pages) { items += PageItem(nextKey++, PageRef(input, it)) }
                } catch (e: ParserFailure) {
                    errors += "${f.name}: ${e.message}"
                }
            }
            message = errors.joinToString("\n").ifEmpty { null }
            loading = false
        }
    }
    LaunchedEffect(Unit) { add(start) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch { add(withContext(Dispatchers.IO) { uris.map { JobQueue.describe(context, it) } }) }
    }

    fun queue(what: String, pages: List<PageRef> = emptyList(), splits: List<List<PageRef>> = emptyList()) {
        // The job copies these private files at once, so the organiser folder can go when this screen closes.
        val files = pdfs.map { IncomingFile(Uri.fromFile(it.file), it.name, Format.PDF) }
        if (loading) return
        loading = true // also stops a second tap from queuing the job twice
        scope.launch {
            val passwords = pdfs.withIndex().mapNotNull { (i, p) -> p.password?.let { i to it } }.toMap()
            JobQueue.enqueuePages(context, files, AppSettings(context).defaultPreset(Format.PDF), pages, splits, passwords, removePassword)
            onQueued(what)
            onClose()
        }
    }

    asking?.let { ask ->
        PasswordDialog(
            files = listOf(ask.request),
            offerRemove = true,
            note = null,
            onSubmit = { pw, remove -> ask.answer.complete(pw[0] to remove) },
            onDismiss = { ask.answer.complete(null) },
        )
    }
    BackHandler(onBack = onClose)
    val chosen = items.filter { it.key in selected }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("Close") }
                Text(
                    if (selected.isEmpty()) "${items.size} pages" else "${selected.size} of ${items.size} selected",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                TextButton(onClick = { selected = if (selected.size == items.size) emptySet() else items.map { it.key }.toSet() }) {
                    Text(if (selected.size == items.size && items.isNotEmpty()) "Clear" else "Select all")
                }
            }
            message?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
            }
            Box(Modifier.weight(1f)) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(104.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(items, key = { _, it -> it.key }) { position, item ->
                        val pdf = pdfs[item.ref.input]
                        PageTile(
                            engine = engine,
                            renderLock = renderLock,
                            pdf = pdf,
                            item = item,
                            position = position,
                            isSelected = item.key in selected,
                            onToggle = { selected = if (item.key in selected) selected - item.key else selected + item.key },
                        )
                    }
                }
                if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val some = chosen.isNotEmpty()
                OutlinedButton(enabled = some, onClick = {
                    items.replaceAll { if (it.key in selected) it.copy(ref = it.ref.copy(rotation = (it.ref.rotation + 90) % 360)) else it }
                }) { Text("Rotate") }
                OutlinedButton(enabled = some && chosen.size < items.size, onClick = {
                    items.removeAll { it.key in selected }
                    selected = emptySet()
                }) { Text("Delete") }
                OutlinedButton(enabled = some, onClick = { move(items, selected, earlier = true) }) { Text("Move earlier") }
                OutlinedButton(enabled = some, onClick = { move(items, selected, earlier = false) }) { Text("Move later") }
                OutlinedButton(enabled = !loading, onClick = { picker.launch(arrayOf("application/pdf")) }) { Text("Add PDF") }
                OutlinedButton(enabled = some && chosen.size < items.size, onClick = {
                    queue("Extracting ${chosen.size} pages", pages = chosen.map { it.ref })
                }) { Text("Extract selected") }
                val splits = splitBefore(items, selected)
                OutlinedButton(enabled = splits.size > 1, onClick = {
                    queue("Splitting into ${splits.size} PDFs", splits = splits)
                }) { Text("Split before selected") }
            }
            Button(
                enabled = items.isNotEmpty() && !loading,
                onClick = { queue("Saving ${items.size} pages", pages = items.map { it.ref }) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(min = 52.dp),
            ) { Text("Save as PDF") }
        }
    }
}

@Composable
private fun PageTile(
    engine: PdfEngineClient,
    renderLock: Mutex,
    pdf: LoadedPdf,
    item: PageItem,
    position: Int,
    isSelected: Boolean,
    onToggle: () -> Unit,
) {
    val thumb by produceState<ImageBitmap?>(null, pdf.file, item.ref.page) {
        value = runCatching { renderLock.withLock { engine.renderBitmap(pdf.file, item.ref.page, 240, pdf.password) } }.getOrNull()?.asImageBitmap()
    }
    val rotated = if (item.ref.rotation != 0) ", turned ${item.ref.rotation} degrees" else ""
    Card(
        border = if (isSelected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = Modifier
            .toggleable(value = isSelected, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { contentDescription = "Position ${position + 1}: page ${item.ref.page + 1} of ${pdf.name}$rotated" },
    ) {
        Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().aspectRatio(0.71f), contentAlignment = Alignment.Center) {
                thumb?.let { Image(it, contentDescription = null, modifier = Modifier.rotate(item.ref.rotation.toFloat())) }
                    ?: CircularProgressIndicator(Modifier.padding(24.dp))
            }
            Text("${position + 1}", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}


/** Moves every selected page one step, keeping selected neighbours together. */
private fun move(items: MutableList<PageItem>, selected: Set<Int>, earlier: Boolean) {
    val indices = if (earlier) 1 until items.size else (items.size - 2 downTo 0)
    for (i in indices) {
        val j = if (earlier) i - 1 else i + 1
        if (items[i].key in selected && items[j].key !in selected) {
            val page = items[i]
            items[i] = items[j]
            items[j] = page
        }
    }
}

/** Page groups for a split: a new file starts at each selected page (the first page always starts one). */
private fun splitBefore(items: List<PageItem>, selected: Set<Int>): List<List<PageRef>> {
    val parts = mutableListOf<MutableList<PageRef>>()
    items.forEachIndexed { i, item ->
        if (i == 0 || item.key in selected) parts += mutableListOf<PageRef>()
        parts.last() += item.ref
    }
    return parts
}
