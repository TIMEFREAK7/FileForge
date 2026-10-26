package io.github.timefreak7.fileforge.jobs

import android.content.Context
import android.net.Uri
import io.github.timefreak7.fileforge.core.Format
import io.github.timefreak7.fileforge.core.Preset

/** User settings, shared by the UI and the worker. */
class AppSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** The output folder picked at first run (a Storage Access Framework tree URI). */
    var outputTree: Uri?
        get() = prefs.getString("outputTree", null)?.let(Uri::parse)
        set(value) = prefs.edit().putString("outputTree", value?.toString()).apply()

    var keepMetadata: Boolean
        get() = prefs.getBoolean("keepMetadata", false)
        set(value) = prefs.edit().putBoolean("keepMetadata", value).apply()

    /** Default longest side for the donut's one-swipe conversion; 0 keeps the original size. */
    var defaultMaxSide: Int
        get() = prefs.getInt("maxSide", 0)
        set(value) = prefs.edit().putInt("maxSide", value).apply()

    fun quality(format: Format): Int = prefs.getInt("quality.${format.name}", 90)

    fun setQuality(format: Format, value: Int) = prefs.edit().putInt("quality.${format.name}", value).apply()

    /** Fixed donut positions; rearranged only in Settings, never by usage. */
    var donutOrder: List<Format>
        get() {
            val saved = prefs.getString("donutOrder", null)?.split(',')?.mapNotNull { name ->
                Format.entries.firstOrNull { it.name == name }
            }.orEmpty()
            return saved + Format.DEFAULT_ORDER.filter { it !in saved }
        }
        set(value) = prefs.edit().putString("donutOrder", value.joinToString(",") { it.name }).apply()

    fun defaultPreset(target: Format) = Preset(
        quality = quality(target),
        maxSide = defaultMaxSide,
        keepMetadata = keepMetadata,
    )
}
