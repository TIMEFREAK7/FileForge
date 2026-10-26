package io.github.timefreak7.fileforge

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.TextView

// Placeholder until Phase 1 brings the Compose UI.
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        setContentView(TextView(this).apply {
            text = "FileForge — Phase 0\n\nNothing to use here yet. The Phase 0 checks run from a computer: see docs/phase0.md."
            textSize = 18f
            // No app theme yet, so set colours explicitly: the OEM default theme left this text invisible.
            setTextColor(Color.rgb(0x1B, 0x26, 0x33))
            setBackgroundColor(Color.rgb(0xEB, 0xEE, 0xF2))
            setPadding(pad, pad, pad, pad)
        })
    }
}
