package io.github.timefreak7.fileforge.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentNameDictionary
import com.tom_roush.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDXFAResource
import com.tom_roush.pdfbox.util.Matrix
import io.github.timefreak7.fileforge.core.Limits
import io.github.timefreak7.fileforge.core.PageGeometry
import io.github.timefreak7.fileforge.image.ConversionException
import io.github.timefreak7.fileforge.pdf.PdfOps.copy
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileInputStream
import kotlin.math.roundToInt

/** Phase 3 operations: markup, signatures, forms and redaction. All run in the isolated process. */
object PdfEdit {
    private const val XFA_MESSAGE = "This form uses XFA, a format FileForge cannot fill. Open it in Adobe Acrobat instead."
    private const val MAX_FIELDS = 500

    private fun readSpec(fd: ParcelFileDescriptor): EditSpec =
        EditSpec.fromJson(FileInputStream(fd.fileDescriptor).use { String(it.readBytes(), Charsets.UTF_8) })

    /** The fields a user can fill, as JSON for [FormField.listFromJson]; "xfa" when the form is XFA. */
    fun formFields(pdf: ParcelFileDescriptor, password: String): Pair<String, Boolean> = PdfOps.open(pdf, password).use { doc ->
        val acro = doc.documentCatalog.acroForm ?: return "[]" to false
        if (acro.hasXFA()) return "[]" to true
        val pages = doc.pages.toList()
        val fields = JSONArray()
        for (f in acro.fieldTree) {
            if (f !is PDTerminalField || fields.length() >= MAX_FIELDS) continue
            val type = when (f) {
                is PDTextField -> FormField.Type.TEXT
                is PDCheckBox -> FormField.Type.CHECKBOX
                is PDRadioButton -> FormField.Type.RADIO
                is PDChoice -> FormField.Type.CHOICE
                else -> continue // push buttons and signature fields cannot be filled here
            }
            val options = when (f) {
                is PDCheckBox -> listOf(f.onValue)
                is PDRadioButton -> f.onValues.toList()
                is PDChoice -> f.options
                else -> emptyList()
            }
            val widget = f.widgets.firstOrNull()
            val rect = widget?.rectangle
            fields.put(
                JSONObject()
                    .put("name", f.fullyQualifiedName.take(500))
                    .put("type", type.name)
                    .put("value", f.valueAsString.take(2_000))
                    .put("options", JSONArray(options.take(200).map { it.take(500) }))
                    .put("readOnly", f.isReadOnly)
                    .put("multiline", (f as? PDTextField)?.isMultiline == true)
                    .put("page", widget?.let { pageOf(doc, pages, it) } ?: 0)
                    .put("w", (rect?.width ?: 0f).toDouble())
                    .put("h", (rect?.height ?: 0f).toDouble()),
            )
        }
        fields.toString() to false
    }

    private fun pageOf(doc: PDDocument, pages: List<PDPage>, widget: PDAnnotationWidget): Int {
        widget.page?.let { p -> doc.pages.indexOf(p).takeIf { it >= 0 }?.let { return it } }
        return pages.indexOfFirst { p -> p.annotations.any { it.cosObject === widget.cosObject } }.coerceAtLeast(0)
    }

    /** Applies marks, stamps and form values from [specFd]. [stamps] are PNGs FileForge made (signatures, text). */
    fun edit(
        input: ParcelFileDescriptor,
        password: String,
        specFd: ParcelFileDescriptor,
        stamps: Array<ParcelFileDescriptor>,
        output: ParcelFileDescriptor,
        removePassword: Boolean,
    ): Int {
        val spec = readSpec(specFd)
        PdfOps.open(input, password).use { doc ->
            val images = HashMap<Int, PDImageXObject>()
            fun stamp(i: Int): PDImageXObject = images.getOrPut(i) {
                val fd = stamps.getOrNull(i) ?: throw ConversionException("A signature or text stamp is missing.")
                val bitmap = BitmapFactory.decodeFileDescriptor(fd.fileDescriptor) ?: throw ConversionException("A signature or text stamp could not be read.")
                try {
                    LosslessFactory.createFromImage(doc, bitmap)
                } finally {
                    bitmap.recycle()
                }
            }
            fillForm(doc, spec, ::stamp)
            for ((index, marks) in spec.marks.filter { it.kind != EditSpec.Kind.REDACT }.groupBy { it.page }) {
                if (index !in 0 until doc.numberOfPages) throw ConversionException("A marked page does not exist.")
                val page = doc.getPage(index)
                val box = page.cropBox
                val m = PageGeometry.displayToUser(box.lowerLeftX, box.lowerLeftY, box.width, box.height, page.rotation)
                val (dw, dh) = PageGeometry.displaySize(box.width, box.height, page.rotation)
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.transform(Matrix(m[0], m[1], m[2], m[3], m[4], m[5]))
                    marks.forEach { draw(cs, it, dw, dh, ::stamp) }
                }
            }
            PdfOps.keepOrRemovePassword(doc, password, removePassword)
            PdfOps.save(doc, output)
            return doc.numberOfPages
        }
    }

    private fun fillForm(doc: PDDocument, spec: EditSpec, stamp: (Int) -> PDImageXObject) {
        if (spec.fields.isEmpty() && spec.stampedFields.isEmpty()) return
        val acro = doc.documentCatalog.acroForm ?: throw ConversionException("This PDF has no form to fill.")
        if (acro.hasXFA()) throw ConversionException(XFA_MESSAGE)
        for ((name, value) in spec.fields) {
            val field = acro.getField(name) ?: continue
            try {
                when (field) {
                    is PDCheckBox -> if (value == "Off") field.unCheck() else field.check()
                    is PDRadioButton -> field.setValue(value)
                    is PDChoice -> field.setValue(value)
                    is PDTextField -> field.setValue(value)
                }
            } catch (e: Exception) {
                throw ConversionException("The form's font cannot show the value of \"${name.take(60)}\".")
            }
        }
        // Scripts the form's font cannot show: the value is drawn as an image and the field flattened (spec).
        val flatten = ArrayList<PDField>()
        val pages = doc.pages.toList()
        for ((name, index) in spec.stampedFields) {
            val field = acro.getField(name) as? PDTextField ?: continue
            field.setValue("")
            for (widget in field.widgets) {
                val r = widget.rectangle ?: continue
                val page = pages[pageOf(doc, pages, widget)]
                // shortcut: drawn in user space, so on a rotated page the stamp is rotated with it; rare for forms.
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use {
                    it.drawImage(stamp(index), r.lowerLeftX + 2, r.lowerLeftY + 1, r.width - 4, r.height - 2)
                }
            }
            flatten += field
        }
        if (flatten.isNotEmpty()) acro.flatten(flatten, true)
    }

    private fun draw(cs: PDPageContentStream, mark: EditSpec.Mark, dw: Float, dh: Float, stamp: (Int) -> PDImageXObject) {
        val p = mark.points
        fun x(i: Int) = p[i * 2] * dw
        fun y(i: Int) = (1 - p[i * 2 + 1]) * dh
        if (p.size < 2) return
        val c = mark.color
        cs.setStrokingColor(Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f)
        cs.setNonStrokingColor(Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f)
        cs.setLineWidth(mark.width)
        cs.setLineCapStyle(1)
        cs.setLineJoinStyle(1)
        val corners = p.size >= 4
        val left = if (corners) minOf(x(0), x(1)) else 0f
        val bottom = if (corners) minOf(y(0), y(1)) else 0f
        val w = if (corners) kotlin.math.abs(x(1) - x(0)) else 0f
        val h = if (corners) kotlin.math.abs(y(1) - y(0)) else 0f
        when (mark.kind) {
            EditSpec.Kind.INK -> {
                cs.moveTo(x(0), y(0))
                if (p.size == 2) cs.lineTo(x(0) + 0.1f, y(0)) else for (i in 1 until p.size / 2) cs.lineTo(x(i), y(i))
                cs.stroke()
            }
            EditSpec.Kind.LINE -> if (corners) {
                cs.moveTo(x(0), y(0))
                cs.lineTo(x(1), y(1))
                cs.stroke()
            }
            EditSpec.Kind.RECT -> if (corners) {
                cs.addRect(left, bottom, w, h)
                cs.stroke()
            }
            EditSpec.Kind.ELLIPSE -> if (corners) {
                ellipse(cs, left, bottom, w, h)
                cs.stroke()
            }
            EditSpec.Kind.HIGHLIGHT -> if (corners) {
                cs.saveGraphicsState()
                // Full colour, multiplied: white paper turns yellow and the text under it stays black.
                cs.setGraphicsStateParameters(PDExtendedGraphicsState().apply { blendMode = BlendMode.MULTIPLY })
                cs.addRect(left, bottom, w, h)
                cs.fill()
                cs.restoreGraphicsState()
            }
            EditSpec.Kind.TEXT -> {
                // Latin text only; the editor sends other scripts as IMAGE stamps (PDFBox does no shaping).
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA, mark.size)
                cs.newLineAtOffset(x(0), y(0) - mark.size)
                mark.text.split('\n').forEachIndexed { i, line ->
                    if (i > 0) cs.newLineAtOffset(0f, -mark.size * 1.2f)
                    try {
                        cs.showText(line)
                    } catch (e: IllegalArgumentException) {
                        throw ConversionException("Some text could not be written; try typing it again.")
                    }
                }
                cs.endText()
            }
            EditSpec.Kind.IMAGE -> if (corners) cs.drawImage(stamp(mark.stamp), left, bottom, w, h)
            EditSpec.Kind.REDACT -> Unit
        }
    }

    private fun ellipse(cs: PDPageContentStream, x: Float, y: Float, w: Float, h: Float) {
        val k = 0.5523f // Bézier circle constant
        val rx = w / 2
        val ry = h / 2
        val cx = x + rx
        val cy = y + ry
        cs.moveTo(cx + rx, cy)
        cs.curveTo(cx + rx, cy + ry * k, cx + rx * k, cy + ry, cx, cy + ry)
        cs.curveTo(cx - rx * k, cy + ry, cx - rx, cy + ry * k, cx - rx, cy)
        cs.curveTo(cx - rx, cy - ry * k, cx - rx * k, cy - ry, cx, cy - ry)
        cs.curveTo(cx + rx * k, cy - ry, cx + rx, cy - ry * k, cx + rx, cy)
        cs.closePath()
    }

    /**
     * Redaction (S13): every page with a hidden area is rasterised with the areas painted black, so the text
     * under them is gone, not just covered. The result is a new document holding only page content: no
     * document info, XMP, bookmarks, attachments, structure tree, comments, links or form fields, and no
     * earlier revisions. Unmarked pages keep their text.
     */
    fun redact(input: ParcelFileDescriptor, password: String, specFd: ParcelFileDescriptor, output: ParcelFileDescriptor, removePassword: Boolean): Int {
        val boxes = readSpec(specFd).marks.filter { it.kind == EditSpec.Kind.REDACT && it.points.size >= 4 }.groupBy { it.page }
        if (boxes.isEmpty()) throw ConversionException("No areas were marked to hide.")
        val forRenderer = input.dup()
        PdfOps.open(input, password).use { src ->
            PdfOps.renderer(forRenderer, password).use { renderer ->
                // Visible form values on kept pages become page content before widgets are dropped.
                runCatching { src.documentCatalog.acroForm?.flatten() }
                PDDocument(MemoryUsageSetting.setupMainMemoryOnly()).use { out ->
                    for (i in 0 until src.numberOfPages) {
                        val rects = boxes[i]
                        out.addPage(if (rects == null) contentOnly(src.getPage(i)) else rasterised(out, renderer, i, rects))
                    }
                    if (src.isEncrypted && !removePassword) PdfOps.protect(out, password, src.currentAccessPermission.copy())
                    PdfOps.save(out, output)
                    return out.numberOfPages
                }
            }
        }
    }

    /** A copy of [page] with only what draws it: anything that could point back to other objects is left out. */
    private fun contentOnly(page: PDPage): PDPage {
        val d = COSDictionary()
        d.setItem(COSName.TYPE, COSName.PAGE)
        d.setItem(COSName.MEDIA_BOX, page.mediaBox.cosArray)
        d.setItem(COSName.CROP_BOX, page.cropBox.cosArray)
        d.setInt(COSName.ROTATE, page.rotation)
        d.setItem(COSName.RESOURCES, page.resources?.cosObject ?: COSDictionary())
        page.cosObject.getDictionaryObject(COSName.CONTENTS)?.let { d.setItem(COSName.CONTENTS, it) }
        return PDPage(d)
    }

    private fun rasterised(out: PDDocument, renderer: PdfRenderer, index: Int, rects: List<EditSpec.Mark>): PDPage {
        renderer.openPage(index).use { p ->
            var w = (p.width * 200 / 72f).roundToInt()
            var h = (p.height * 200 / 72f).roundToInt()
            if (w.toLong() * h > Limits.MAX_RENDER_PIXELS) {
                val s = kotlin.math.sqrt(Limits.MAX_RENDER_PIXELS.toDouble() / (w.toLong() * h))
                w = (w * s).toInt()
                h = (h * s).toInt()
            }
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.WHITE)
                p.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val black = Paint().apply { color = Color.BLACK }
                val canvas = Canvas(bitmap)
                for (r in rects) {
                    val q = r.points
                    canvas.drawRect(minOf(q[0], q[2]) * w, minOf(q[1], q[3]) * h, maxOf(q[0], q[2]) * w, maxOf(q[1], q[3]) * h, black)
                }
                val image = JPEGFactory.createFromByteArray(out, Jpeg.encode(bitmap, w, h, 90))
                val page = PDPage(PDRectangle(p.width.toFloat(), p.height.toFloat()))
                PDPageContentStream(out, page).use { it.drawImage(image, 0f, 0f, p.width.toFloat(), p.height.toFloat()) }
                return page
            } finally {
                bitmap.recycle()
            }
        }
    }

    /** Debug self-test files only (refused in release builds by the service): "redaction", "form", "xfa". */
    fun makeTestPdf(kind: String, phrase: String, output: ParcelFileDescriptor) {
        PDDocument().use { doc ->
            val first = PDPage(PDRectangle.A4)
            val second = PDPage(PDRectangle.A4)
            doc.addPage(first)
            doc.addPage(second)
            fun text(page: PDPage, s: String, y: Float) =
                // Uncompressed, so a leak of the original stream would show up in a byte search.
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, false).use {
                    it.beginText()
                    it.setFont(PDType1Font.HELVETICA, 14f)
                    it.newLineAtOffset(60f, y)
                    it.showText(s)
                    it.endText()
                }
            when (kind) {
                "redaction" -> {
                    text(first, phrase, 700f)
                    text(second, "Keep this page", 700f)
                    doc.documentInformation.title = phrase
                    doc.documentInformation.subject = phrase
                    doc.documentCatalog.metadata = PDMetadata(doc).apply {
                        createOutputStream().use { it.write("<x:xmpmeta><dc:title>$phrase</dc:title></x:xmpmeta>".toByteArray()) }
                    }
                    doc.documentCatalog.documentOutline = PDDocumentOutline().apply {
                        addLast(PDOutlineItem().apply { title = phrase; destination = PDPageFitDestination().apply { page = first } })
                    }
                    val file = PDEmbeddedFile(doc, phrase.toByteArray().inputStream()).apply { subtype = "text/plain" }
                    val spec = PDComplexFileSpecification().apply { this.file = "note.txt"; embeddedFile = file }
                    val tree = PDEmbeddedFilesNameTreeNode().apply { names = mapOf("note.txt" to spec) }
                    doc.documentCatalog.names = PDDocumentNameDictionary(doc.documentCatalog).apply { embeddedFiles = tree }
                    second.annotations = listOf(PDAnnotationText().apply { contents = phrase; rectangle = PDRectangle(60f, 600f, 20f, 20f) })
                }
                "form", "xfa" -> {
                    val acro = PDAcroForm(doc)
                    doc.documentCatalog.acroForm = acro
                    val resources = PDResources().apply { put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA) }
                    acro.defaultResources = resources
                    acro.defaultAppearance = "/Helv 12 Tf 0 g"
                    val widgets = ArrayList<com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation>()
                    for ((i, name) in listOf("name", "city").withIndex()) {
                        val field = PDTextField(acro).apply { partialName = name; defaultAppearance = "/Helv 12 Tf 0 g" }
                        acro.fields.add(field)
                        val widget = field.widgets[0]
                        widget.rectangle = PDRectangle(60f, 700f - i * 60f, 300f, 30f)
                        widget.page = first
                        widgets += widget
                    }
                    first.annotations = widgets
                    if (kind == "xfa") {
                        val stream = doc.document.createCOSStream()
                        stream.createOutputStream().use { it.write("<xdp:xdp xmlns:xdp=\"http://ns.adobe.com/xdp/\"/>".toByteArray()) }
                        acro.xfa = PDXFAResource(stream as COSStream)
                    }
                }
                else -> throw ConversionException("Unknown test file.")
            }
            PdfOps.save(doc, output)
        }
    }
}
