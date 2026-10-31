package io.github.timefreak7.fileforge.pdf

import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the editor asks for, applied in one pass by the engine. Positions are normalised to the
 * page as shown: u left→right and v top→bottom, both 0..1. It travels as a file, not a binder argument,
 * because pen strokes can exceed the binder's ~1 MB limit.
 */
data class EditSpec(
    val marks: List<Mark> = emptyList(),
    /** Form values by fully qualified field name; check boxes take their on value or "Off". */
    val fields: Map<String, String> = emptyMap(),
    /** Text fields whose value is stamped as an image (scripts the form's font cannot show), by stamp index. */
    val stampedFields: Map<String, Int> = emptyMap(),
) {
    enum class Kind { INK, HIGHLIGHT, RECT, ELLIPSE, LINE, TEXT, IMAGE, REDACT }

    /**
     * [points]: INK is a polyline (u, v pairs); TEXT is its top-left corner; the others are two opposite
     * corners. [stamp] indexes the PNG files sent with the spec (IMAGE). [width] and [size] are in points.
     */
    data class Mark(
        val page: Int,
        val kind: Kind,
        val points: List<Float>,
        val color: Int = 0xFF000000.toInt(),
        val width: Float = 2f,
        val text: String = "",
        val size: Float = 14f,
        val stamp: Int = -1,
    )

    val hasRedactions: Boolean get() = marks.any { it.kind == Kind.REDACT }
    val hasEdits: Boolean get() = marks.any { it.kind != Kind.REDACT } || fields.isNotEmpty() || stampedFields.isNotEmpty()

    fun toJson(): String = JSONObject().apply {
        put("marks", JSONArray(marks.map { m ->
            JSONObject()
                .put("page", m.page).put("kind", m.kind.name).put("points", JSONArray(m.points.map { it.toDouble() }))
                .put("color", m.color).put("width", m.width.toDouble()).put("text", m.text)
                .put("size", m.size.toDouble()).put("stamp", m.stamp)
        }))
        put("fields", JSONObject(fields as Map<*, *>))
        put("stampedFields", JSONObject(stampedFields as Map<*, *>))
    }.toString()

    companion object {
        fun fromJson(json: String): EditSpec {
            val o = JSONObject(json)
            val marks = o.optJSONArray("marks") ?: JSONArray()
            return EditSpec(
                marks = List(marks.length()) { i ->
                    val m = marks.getJSONObject(i)
                    val p = m.getJSONArray("points")
                    Mark(
                        page = m.getInt("page"),
                        kind = Kind.valueOf(m.getString("kind")),
                        points = List(p.length()) { p.getDouble(it).toFloat() },
                        color = m.optInt("color", 0xFF000000.toInt()),
                        width = m.optDouble("width", 2.0).toFloat(),
                        text = m.optString("text"),
                        size = m.optDouble("size", 14.0).toFloat(),
                        stamp = m.optInt("stamp", -1),
                    )
                },
                fields = o.optJSONObject("fields")?.let { f -> f.keys().asSequence().associateWith { f.getString(it) } }.orEmpty(),
                stampedFields = o.optJSONObject("stampedFields")?.let { f -> f.keys().asSequence().associateWith { f.getInt(it) } }.orEmpty(),
            )
        }
    }
}

/** One fillable field as the engine found it. [rect] is the first widget's (page, width, height) in points. */
data class FormField(
    val name: String,
    val type: Type,
    val value: String,
    val options: List<String>,
    val readOnly: Boolean,
    val multiline: Boolean,
    val page: Int,
    val widthPt: Float,
    val heightPt: Float,
) {
    enum class Type { TEXT, CHECKBOX, RADIO, CHOICE }

    companion object {
        fun listFromJson(json: String): List<FormField> {
            val a = JSONArray(json)
            return List(a.length()) { i ->
                val f = a.getJSONObject(i)
                val o = f.getJSONArray("options")
                FormField(
                    name = f.getString("name"),
                    type = Type.valueOf(f.getString("type")),
                    value = f.optString("value"),
                    options = List(o.length()) { o.getString(it) },
                    readOnly = f.optBoolean("readOnly"),
                    multiline = f.optBoolean("multiline"),
                    page = f.optInt("page"),
                    widthPt = f.optDouble("w").toFloat(),
                    heightPt = f.optDouble("h").toFloat(),
                )
            }
        }
    }
}
