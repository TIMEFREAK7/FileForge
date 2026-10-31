package io.github.timefreak7.fileforge.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.UUID

/** Drawn signatures (spec: Signatures screen): PNGs inside the app only, excluded from backup and transfer (S9). */
object Signatures {
    private fun dir(context: Context) = File(context.filesDir, "signatures").apply { mkdirs() }

    fun all(context: Context): List<File> =
        dir(context).listFiles { f -> f.extension == "png" }.orEmpty().sortedByDescending { it.lastModified() }

    /** Draws [strokes] (in pixels of the pad) black on transparent, cropped to the ink, and saves it. Null when empty. */
    fun save(context: Context, strokes: List<List<Offset>>, strokePx: Float): File? {
        val points = strokes.flatten()
        if (points.isEmpty()) return null
        val margin = strokePx * 2
        val left = points.minOf { it.x } - margin
        val top = points.minOf { it.y } - margin
        val w = (points.maxOf { it.x } - left + margin).toInt().coerceAtLeast(8)
        val h = (points.maxOf { it.y } - top + margin).toInt().coerceAtLeast(8)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = strokePx
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val canvas = Canvas(bitmap)
        for (stroke in strokes) {
            if (stroke.size == 1) {
                canvas.drawPoint(stroke[0].x - left, stroke[0].y - top, paint)
                continue
            }
            val path = Path()
            stroke.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x - left, p.y - top) else path.lineTo(p.x - left, p.y - top) }
            canvas.drawPath(path, paint)
        }
        val file = File(dir(context), "${UUID.randomUUID()}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }
}

/** Draw a signature with a finger; saved on Save. */
@Composable
fun SignaturePadDialog(onSaved: (File) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val strokePx = with(androidx.compose.ui.platform.LocalDensity.current) { 3.dp.toPx() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Draw your signature") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(2.2f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .onSizeChanged { size = it }
                        .semantics { contentDescription = "Signature pad. Draw with one finger." }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { current = listOf(it) },
                                onDrag = { change, _ -> current = current + change.position },
                                onDragEnd = {
                                    strokes += current
                                    current = emptyList()
                                },
                            )
                        },
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        for (stroke in strokes + listOf(current)) {
                            if (stroke.size < 2) continue
                            val path = androidx.compose.ui.graphics.Path().apply {
                                moveTo(stroke[0].x, stroke[0].y)
                                stroke.drop(1).forEach { lineTo(it.x, it.y) }
                            }
                            drawPath(path, Color.Black, style = Stroke(strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                    }
                }
                Text("Saved only on this phone. It is a picture of your signature, not a digital certificate.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(enabled = strokes.isNotEmpty(), onClick = { Signatures.save(context, strokes, strokePx)?.let(onSaved) }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(enabled = strokes.isNotEmpty(), onClick = { strokes.clear() }) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** Saved signatures as a list to pick from; [onPick] null makes it a manage-only list. */
@Composable
fun SignatureList(signatures: List<File>, onPick: ((File) -> Unit)?, onDelete: (File) -> Unit) {
    signatures.forEach { file ->
        val bitmap = remember(file) { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(64.dp).clip(RoundedCornerShape(8.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                    bitmap?.let { Image(it, contentDescription = "Saved signature", modifier = Modifier.padding(4.dp)) }
                }
                if (onPick != null) TextButton(onClick = { onPick(file) }) { Text("Use") }
                TextButton(onClick = { onDelete(file) }) { Text("Delete") }
            }
        }
    }
}

/** Settings → Signatures: draw, view and delete. */
@Composable
fun SignaturesScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    var signatures by remember { mutableStateOf(Signatures.all(context)) }
    var drawing by remember { mutableStateOf(false) }
    BackHandler(onBack = onClose)
    Surface(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClose) { Text("Close") }
                    Text("Signatures", style = MaterialTheme.typography.headlineSmall)
                }
            }
            item {
                Text(
                    "Signatures are kept only inside FileForge on this phone and never backed up.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { SignatureList(signatures, onPick = null, onDelete = { it.delete(); signatures = Signatures.all(context) }) }
            item {
                OutlinedButton(onClick = { drawing = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Draw a new signature") }
            }
        }
    }
    if (drawing) {
        SignaturePadDialog(onSaved = { drawing = false; signatures = Signatures.all(context) }, onDismiss = { drawing = false })
    }
}
