package io.github.timefreak7.fileforge.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.timefreak7.fileforge.core.Format
import io.github.timefreak7.fileforge.image.ParserFailure
import io.github.timefreak7.fileforge.jobs.AppSettings
import io.github.timefreak7.fileforge.jobs.IncomingFile
import io.github.timefreak7.fileforge.jobs.JobQueue
import io.github.timefreak7.fileforge.pdf.EditSpec
import io.github.timefreak7.fileforge.pdf.EditSpec.Kind
import io.github.timefreak7.fileforge.pdf.FormField
import io.github.timefreak7.fileforge.pdf.PdfEngineClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private enum class Tool(val label: String) {
    BROWSE("Browse"), PEN("Pen"), HIGHLIGHT("Highlight"), TEXT("Text"), RECT("Box"), ELLIPSE("Circle"), LINE("Line"), SIGN("Sign"), HIDE("Hide")
}

private val Colours = listOf(0xFF000000.toInt() to "Black", 0xFF1565C0.toInt() to "Blue", 0xFFC62828.toInt() to "Red", 0xFF2E7D32.toInt() to "Green")
private const val HIGHLIGHT = 0xFFFFEB3B.toInt()
private const val STAMP_PX_PER_PT = 4f

/** PDF text with the built-in Helvetica covers Latin-1; anything else is laid out by Android and stamped (spec). */
fun isPdfText(text: String) = text.all { it == '\n' || it in ' '..'~' || it in ' '..'ÿ' }

/**
 * The PDF editor (spec: PDF editor): markup, signatures, form filling and hiding areas. One page at a
 * time, so drawing never fights with scrolling. Saving queues an edit job; the PDF is changed only there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfEditorScreen(start: IncomingFile, onQueued: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { PdfEngineClient(context) }
    val dir = remember { File(context.cacheDir, "editor").apply { deleteRecursively(); mkdirs() } }
    val renderLock = remember { Mutex() }
    DisposableEffect(Unit) {
        onDispose {
            engine.close()
            dir.deleteRecursively()
        }
    }

    var pdf by remember { mutableStateOf<LoadedPdf?>(null) }
    var sizes by remember { mutableStateOf(FloatArray(0)) }
    var fields by remember { mutableStateOf<List<FormField>>(emptyList()) }
    var xfa by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf<PasswordAsk?>(null) }
    val marks = remember { mutableStateListOf<EditSpec.Mark>() }
    val stamps = remember { mutableStateListOf<File>() }
    val stampImages = remember { mutableStateMapOf<Int, ImageBitmap>() }
    val values = remember { mutableStateMapOf<String, String>() }
    var tool by remember { mutableStateOf(Tool.BROWSE) }
    var colour by remember { mutableStateOf(Colours[0].first) }
    var signature by remember { mutableStateOf<File?>(null) }
    var signatureWidth by remember { mutableStateOf(0.3f) }
    var choosingSignature by remember { mutableStateOf(false) }
    var drawingSignature by remember { mutableStateOf(false) }
    var textAt by remember { mutableStateOf<Triple<Int, Float, Float>?>(null) }
    var formOpen by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            val loaded = loadPdf(context, engine, start, dir) { request ->
                PasswordAsk(request, CompletableDeferred()).also { asking = it }.answer.await().also { asking = null }
            }
            if (loaded == null) {
                onClose()
                return@LaunchedEffect
            }
            sizes = engine.pageSizes(loaded.file, loaded.password)
            runCatching { engine.formFields(loaded.file, loaded.password) }.getOrNull()?.let { (f, x) ->
                fields = f
                xfa = x
            }
            pdf = loaded
        } catch (e: ParserFailure) {
            error = "${start.name}: ${e.message}"
        }
    }

    fun stampIndex(file: File): Int {
        stamps.indexOf(file).takeIf { it >= 0 }?.let { return it }
        stamps += file
        BitmapFactory.decodeFile(file.absolutePath)?.let { stampImages[stamps.size - 1] = it.asImageBitmap() }
        return stamps.size - 1
    }

    fun save(p: LoadedPdf) {
        if (saving) return
        saving = true
        scope.launch {
            val files = stamps.toMutableList()
            val plain = HashMap<String, String>()
            val stamped = HashMap<String, Int>()
            for ((name, value) in values) {
                val field = fields.firstOrNull { it.name == name } ?: continue
                if (field.type == FormField.Type.TEXT && value.isNotEmpty() && !isPdfText(value)) {
                    files += withContext(Dispatchers.Default) { renderFieldStamp(value, field.widthPt, field.heightPt, dir) }
                    stamped[name] = files.size - 1
                } else {
                    plain[name] = value
                }
            }
            val spec = EditSpec(marks.toList(), plain, stamped)
            JobQueue.enqueueEdit(
                context, IncomingFile(Uri.fromFile(p.file), p.name, Format.PDF), spec, files,
                AppSettings(context).defaultPreset(Format.PDF), p.password, p.removePassword,
            )
            onQueued(if (spec.hasRedactions) "Saving with hidden areas" else "Saving marked-up PDF")
            onClose()
        }
    }

    BackHandler(onBack = onClose)
    asking?.let { ask ->
        PasswordDialog(
            files = listOf(ask.request),
            offerRemove = true,
            note = null,
            onSubmit = { pw, remove -> ask.answer.complete(pw[0] to remove) },
            onDismiss = { ask.answer.complete(null) },
        )
    }
    val loaded = pdf
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            val pager = rememberPagerState { loaded?.pages ?: 0 }
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("Close") }
                Text(
                    if (loaded == null) "Opening…" else "Page ${pager.currentPage + 1} of ${loaded.pages}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                Button(
                    enabled = loaded != null && !saving && (marks.isNotEmpty() || values.isNotEmpty()),
                    onClick = { loaded?.let(::save) },
                ) { Text("Save") }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when {
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(24.dp))
                    loaded == null -> CircularProgressIndicator()
                    else -> HorizontalPager(state = pager, userScrollEnabled = tool == Tool.BROWSE, modifier = Modifier.fillMaxSize()) { index ->
                        EditablePage(
                            engine = engine,
                            renderLock = renderLock,
                            pdf = loaded,
                            index = index,
                            widthPt = sizes.getOrElse(index * 2) { 595f },
                            heightPt = sizes.getOrElse(index * 2 + 1) { 842f },
                            tool = tool,
                            colour = if (tool == Tool.HIGHLIGHT) HIGHLIGHT else colour,
                            marks = marks.filter { it.page == index },
                            stampImages = stampImages,
                            onMark = { marks += it },
                            onTap = { u, v ->
                                when (tool) {
                                    Tool.TEXT -> textAt = Triple(index, u, v)
                                    Tool.SIGN -> signature?.let { file ->
                                        val bmp = stampImages[stampIndex(file)] ?: return@let
                                        val w = signatureWidth
                                        val h = w * bmp.height / bmp.width * sizes[index * 2] / sizes[index * 2 + 1]
                                        marks += EditSpec.Mark(index, Kind.IMAGE, listOf(u - w / 2, v - h / 2, u + w / 2, v + h / 2), stamp = stampIndex(file))
                                    } ?: run { choosingSignature = true }
                                    else -> Unit
                                }
                            },
                        )
                    }
                }
            }
            // One line saying what the current tool does.
            Text(
                when (tool) {
                    Tool.BROWSE -> "Swipe to change page. Pick a tool to mark up."
                    Tool.TEXT -> "Tap where the text should start."
                    Tool.SIGN -> if (signature == null) "Choose or draw a signature first." else "Tap where the signature goes."
                    Tool.HIDE -> "Drag over what to hide. Those pages become images, so the hidden text is really gone."
                    Tool.PEN -> "Draw with one finger."
                    else -> "Drag to draw."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Tool.entries.forEach { t ->
                    FilterChip(
                        selected = tool == t,
                        onClick = {
                            tool = t
                            if (t == Tool.SIGN && signature == null) choosingSignature = true
                        },
                        label = { Text(t.label) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
                if (fields.isNotEmpty() || xfa) {
                    FilterChip(selected = formOpen, onClick = { formOpen = true }, label = { Text("Form") }, modifier = Modifier.heightIn(min = 48.dp))
                }
                TextButton(enabled = marks.isNotEmpty(), onClick = { marks.removeAt(marks.lastIndex) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Undo") }
            }
            if (tool in setOf(Tool.PEN, Tool.TEXT, Tool.RECT, Tool.ELLIPSE, Tool.LINE)) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Colours.forEach { (c, name) ->
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .then(if (colour == c) Modifier.border(4.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
                                .semantics { contentDescription = "$name${if (colour == c) ", selected" else ""}" }
                                .pointerInput(c) { detectTapGestures { colour = c } },
                        )
                    }
                }
            }
            if (tool == Tool.SIGN && signature != null) {
                Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(0.2f to "Small", 0.3f to "Medium", 0.45f to "Large").forEach { (w, label) ->
                        FilterChip(selected = signatureWidth == w, onClick = { signatureWidth = w }, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                    TextButton(onClick = { choosingSignature = true }) { Text("Change") }
                }
            }
        }
    }

    textAt?.let { (page, u, v) ->
        TextEntryDialog(
            onDone = { text, size ->
                textAt = null
                if (isPdfText(text)) {
                    marks += EditSpec.Mark(page, Kind.TEXT, listOf(u, v), colour, text = text, size = size)
                } else {
                    val (file, wPt, hPt) = renderTextStamp(text, size, colour, dir)
                    marks += EditSpec.Mark(page, Kind.IMAGE, listOf(u, v, u + wPt / sizes[page * 2], v + hPt / sizes[page * 2 + 1]), stamp = stampIndex(file))
                }
            },
            onDismiss = { textAt = null },
        )
    }
    if (choosingSignature) {
        val saved = remember { Signatures.all(context) }
        AlertDialog(
            onDismissRequest = { choosingSignature = false },
            title = { Text("Choose a signature") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (saved.isEmpty()) Text("No signatures yet. Draw one; it is kept only on this phone.")
                    SignatureList(saved, onPick = { signature = it; choosingSignature = false }, onDelete = { it.delete(); choosingSignature = false })
                }
            },
            confirmButton = { Button(onClick = { choosingSignature = false; drawingSignature = true }) { Text("Draw new") } },
            dismissButton = { TextButton(onClick = { choosingSignature = false }) { Text("Cancel") } },
        )
    }
    if (drawingSignature) {
        SignaturePadDialog(onSaved = { signature = it; drawingSignature = false }, onDismiss = { drawingSignature = false })
    }
    if (formOpen) {
        ModalBottomSheet(onDismissRequest = { formOpen = false }) {
            FormFields(fields, xfa, values)
        }
    }
}

@Composable
private fun EditablePage(
    engine: PdfEngineClient,
    renderLock: Mutex,
    pdf: LoadedPdf,
    index: Int,
    widthPt: Float,
    heightPt: Float,
    tool: Tool,
    colour: Int,
    marks: List<EditSpec.Mark>,
    stampImages: Map<Int, ImageBitmap>,
    onMark: (EditSpec.Mark) -> Unit,
    onTap: (Float, Float) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val aspect = widthPt / heightPt
        val boxW = minOf(maxWidth, maxHeight * aspect)
        val boxH = boxW / aspect
        val widthPx = with(density) { boxW.roundToPx() }
        val page by produceState<ImageBitmap?>(null, index, widthPx) {
            value = runCatching { renderLock.withLock { engine.renderBitmap(pdf.file, index, widthPx.coerceIn(1, 1600), pdf.password) } }.getOrNull()?.asImageBitmap()
        }
        var draft by remember { mutableStateOf<EditSpec.Mark?>(null) }
        var area by remember { mutableStateOf(IntSize.Zero) }
        fun uv(o: Offset) = listOf((o.x / area.width).coerceIn(0f, 1f), (o.y / area.height).coerceIn(0f, 1f))
        val kind = when (tool) {
            Tool.PEN -> Kind.INK
            Tool.HIGHLIGHT -> Kind.HIGHLIGHT
            Tool.RECT -> Kind.RECT
            Tool.ELLIPSE -> Kind.ELLIPSE
            Tool.LINE -> Kind.LINE
            Tool.HIDE -> Kind.REDACT
            else -> null
        }
        val input = when {
            tool == Tool.TEXT || tool == Tool.SIGN -> Modifier.pointerInput(tool) {
                detectTapGestures { o -> uv(o).let { onTap(it[0], it[1]) } }
            }
            kind != null -> Modifier.pointerInput(tool, colour, index) {
                detectDragGestures(
                    onDragStart = { o -> draft = EditSpec.Mark(index, kind, uv(o) + uv(o), colour, width = if (kind == Kind.INK) 2f else 2.5f) },
                    onDrag = { change, _ ->
                        val d = draft ?: return@detectDragGestures
                        val p = uv(change.position)
                        draft = if (kind == Kind.INK) d.copy(points = d.points + p) else d.copy(points = d.points.take(2) + p)
                    },
                    onDragEnd = {
                        draft?.let { d ->
                            val big = kind == Kind.INK || kotlin.math.abs(d.points[2] - d.points[0]) + kotlin.math.abs(d.points[3] - d.points[1]) > 0.01f
                            if (big) onMark(if (kind == Kind.INK) d.copy(points = d.points.drop(2)) else d)
                        }
                        draft = null
                    },
                    onDragCancel = { draft = null },
                )
            }
            else -> Modifier
        }
        Box(
            Modifier
                .size(boxW, boxH)
                .background(Color.White)
                .semantics { contentDescription = "Page ${index + 1}" }
                .then(input),
        ) {
            page?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) } ?: CircularProgressIndicator(Modifier.align(Alignment.Center))
            Canvas(Modifier.fillMaxSize()) {
                area = IntSize(size.width.toInt(), size.height.toInt())
                val ptPx = size.width / widthPt
                (marks + listOfNotNull(draft)).forEach { drawMark(it, ptPx, stampImages) }
            }
        }
    }
}

private fun DrawScope.drawMark(m: EditSpec.Mark, ptPx: Float, stampImages: Map<Int, ImageBitmap>) {
    val p = m.points
    if (p.size < 2) return
    fun pt(i: Int) = Offset(p[i * 2] * size.width, p[i * 2 + 1] * size.height)
    val color = Color(m.color)
    val stroke = Stroke(m.width * ptPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val corners = p.size >= 4
    val topLeft = if (corners) Offset(minOf(pt(0).x, pt(1).x), minOf(pt(0).y, pt(1).y)) else Offset.Zero
    val box = if (corners) Size(kotlin.math.abs(pt(1).x - pt(0).x), kotlin.math.abs(pt(1).y - pt(0).y)) else Size.Zero
    when (m.kind) {
        Kind.INK -> drawPath(Path().apply { moveTo(pt(0).x, pt(0).y); for (i in 1 until p.size / 2) lineTo(pt(i).x, pt(i).y) }, color, style = stroke)
        Kind.LINE -> if (corners) drawLine(color, pt(0), pt(1), m.width * ptPx, StrokeCap.Round)
        Kind.RECT -> if (corners) drawRect(color, topLeft, box, style = stroke)
        Kind.ELLIPSE -> if (corners) drawOval(color, topLeft, box, style = stroke)
        Kind.HIGHLIGHT -> if (corners) drawRect(color, topLeft, box, blendMode = androidx.compose.ui.graphics.BlendMode.Multiply)
        Kind.REDACT -> if (corners) drawRect(Color.Black, topLeft, box)
        Kind.IMAGE -> if (corners) stampImages[m.stamp]?.let { img ->
            drawImage(
                img,
                dstOffset = androidx.compose.ui.unit.IntOffset(topLeft.x.toInt(), topLeft.y.toInt()),
                dstSize = IntSize(box.width.toInt().coerceAtLeast(1), box.height.toInt().coerceAtLeast(1)),
            )
        }
        Kind.TEXT -> {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                this.color = m.color
                textSize = m.size * ptPx
                typeface = android.graphics.Typeface.SANS_SERIF
            }
            m.text.split('\n').forEachIndexed { i, line ->
                drawContext.canvas.nativeCanvas.drawText(line, pt(0).x, pt(0).y + m.size * ptPx * (1 + 1.2f * i), paint)
            }
        }
    }
}

/** Text in any script, laid out by Android's text engine (it shapes Devanagari; PDFBox cannot), as a PNG. */
internal fun renderTextStamp(text: String, sizePt: Float, color: Int, dir: File): Triple<File, Float, Float> {
    val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = sizePt * STAMP_PX_PER_PT
    }
    val width = text.split('\n').maxOf { kotlin.math.ceil(paint.measureText(it)).toInt() }.coerceAtLeast(1)
    val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
    val bitmap = Bitmap.createBitmap(width, layout.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    layout.draw(android.graphics.Canvas(bitmap))
    val file = File(dir, "${UUID.randomUUID()}.png")
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    val result = Triple(file, bitmap.width / STAMP_PX_PER_PT, bitmap.height / STAMP_PX_PER_PT)
    bitmap.recycle()
    return result
}

/** A form value the field's font cannot show, drawn to the field's exact shape (spec: flattened as an image). */
internal fun renderFieldStamp(value: String, widthPt: Float, heightPt: Float, dir: File): File {
    val w = (widthPt.coerceAtLeast(20f) * STAMP_PX_PER_PT).toInt()
    val h = (heightPt.coerceAtLeast(10f) * STAMP_PX_PER_PT).toInt()
    val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        textSize = minOf(heightPt * 0.6f, 14f) * STAMP_PX_PER_PT
    }
    val layout = StaticLayout.Builder.obtain(value, 0, value.length, paint, w).setMaxLines(maxOf(1, (h / (paint.textSize * 1.2f)).toInt())).build()
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.translate(4f, ((h - layout.height) / 2f).coerceAtLeast(0f))
    layout.draw(canvas)
    val file = File(dir, "${UUID.randomUUID()}.png")
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
    return file
}

@Composable
private fun TextEntryDialog(onDone: (String, Float) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var size by remember { mutableStateOf(14f) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add text") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = text, onValueChange = { text = it.take(2_000) }, label = { Text("Text") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(10f to "Small", 14f to "Medium", 22f to "Large").forEach { (s, label) ->
                        FilterChip(selected = size == s, onClick = { size = s }, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
                if (text.isNotEmpty() && !isPdfText(text)) {
                    Text(
                        "This text is added as a picture so every letter shows correctly; it cannot be selected or searched later.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { Button(enabled = text.isNotBlank(), onClick = { onDone(text.trimEnd(), size) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The form as a plain list: easier on a phone and with TalkBack than tapping tiny boxes on the page. */
@Composable
private fun FormFields(fields: List<FormField>, xfa: Boolean, values: MutableMap<String, String>) {
    Column(Modifier.padding(horizontal = 24.dp).navigationBarsPadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Fill the form", style = MaterialTheme.typography.headlineSmall)
        if (xfa) {
            Text("This form uses XFA, a format FileForge cannot fill. Open it in Adobe Acrobat instead.", color = MaterialTheme.colorScheme.error)
            return@Column
        }
        fields.forEach { f ->
            val value = values[f.name] ?: f.value
            val label = f.name.substringAfterLast('.').ifEmpty { f.name } + if (f.page > 0) " (page ${f.page + 1})" else ""
            when (f.type) {
                FormField.Type.TEXT -> OutlinedTextField(
                    value = value,
                    onValueChange = { values[f.name] = it.take(2_000) },
                    label = { Text(label) },
                    enabled = !f.readOnly,
                    singleLine = !f.multiline,
                    modifier = Modifier.fillMaxWidth(),
                )
                FormField.Type.CHECKBOX -> Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, modifier = Modifier.weight(1f))
                    Switch(
                        checked = value != "Off" && value.isNotEmpty(),
                        enabled = !f.readOnly,
                        onCheckedChange = { values[f.name] = if (it) f.options.firstOrNull() ?: "Yes" else "Off" },
                    )
                }
                FormField.Type.RADIO, FormField.Type.CHOICE -> Column {
                    Text(label, style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        f.options.forEach { option ->
                            FilterChip(
                                selected = value == option,
                                enabled = !f.readOnly,
                                onClick = { values[f.name] = option },
                                label = { Text(option) },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }
                }
            }
        }
        OutlinedButton(onClick = { values.clear() }, modifier = Modifier.fillMaxWidth()) { Text("Undo form changes") }
    }
}
