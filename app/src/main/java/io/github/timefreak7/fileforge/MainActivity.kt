package io.github.timefreak7.fileforge

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

// Placeholder until Phase 1 brings the Compose UI.
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "FileForge — Phase 0" })
    }
}
