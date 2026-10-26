package io.github.timefreak7.fileforge

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.timefreak7.fileforge.spike.IParserService
import io.github.timefreak7.fileforge.spike.ParserService
import io.github.timefreak7.fileforge.spike.Phase0Checks
import kotlin.concurrent.thread

// Placeholder until Phase 1 brings the Compose UI: for now it only runs the Phase 0 checks.
class MainActivity : Activity() {
    private val ink = Color.rgb(0x1B, 0x26, 0x33)
    private lateinit var column: LinearLayout
    private lateinit var runButton: Button
    private var connection: ServiceConnection? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(24)
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        // No app theme yet, so colours are set explicitly: the OEM default theme left text invisible.
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.rgb(0xEB, 0xEE, 0xF2))
            addView(column)
        })
        addText("FileForge — Phase 0", 22f)
        addText("Runs the Phase 0 checks on this phone. Takes about half a minute; then send a screenshot of everything below.", 15f)
        runButton = Button(this).apply {
            text = "Run Phase 0 checks"
            minHeight = dp(48)
            setOnClickListener { runChecks() }
        }
        column.addView(runButton)
    }

    private fun runChecks() {
        runButton.isEnabled = false
        while (column.childCount > 3) column.removeViewAt(3)
        addText("Running…", 15f)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val service = IParserService.Stub.asInterface(binder)
                thread { runAll(service) }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                runOnUiThread { addText("❌ Parser service crashed or was killed", 15f) }
            }
        }
        connection = conn
        bindService(Intent(this, ParserService::class.java), conn, Context.BIND_AUTO_CREATE)
    }

    private fun runAll(service: IParserService) {
        val checks = Phase0Checks(this)
        listOf(
            { checks.isolated(service) },
            { checks.isolatedCannotReadAppFiles(service) },
            { checks.isolatedPdfRender(service) },
            { checks.encodeMainProcess() },
            { checks.encodeIsolatedProcess(service) },
            { checks.mp3RoundTrip() },
            { checks.platformPdfApis() },
        ).forEach { check -> show(check()) }
        val devanagari = checks.devanagari()
        show(devanagari.result)
        runOnUiThread {
            devanagari.pdfBox?.let { addImage("PDFBox (under test):", it) }
            addImage("Android (correct):", devanagari.android)
            addText("Done.", 15f)
            unbind()
            runButton.isEnabled = true
        }
    }

    private fun show(r: Phase0Checks.Result) {
        val mark = when (r.ok) {
            true -> "✅"
            false -> "❌"
            null -> "ℹ️"
        }
        runOnUiThread { addText("$mark ${r.name}\n${r.detail}", 14f) }
    }

    private fun addText(text: String, size: Float) {
        column.addView(TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(ink)
            setPadding(0, dp(8), 0, dp(8))
        })
    }

    private fun addImage(caption: String, bitmap: Bitmap) {
        addText(caption, 14f)
        column.addView(ImageView(this).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            contentDescription = caption
        })
    }

    private fun unbind() {
        connection?.let { unbindService(it) }
        connection = null
    }

    override fun onDestroy() {
        unbind()
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
