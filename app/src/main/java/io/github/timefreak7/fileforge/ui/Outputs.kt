package io.github.timefreak7.fileforge.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * S8: outputs leave the app only with a read-only grant for that one file. Outputs live in the user's
 * own folder, so the grant is on the folder's document URI; no write flag is ever added.
 */
object Outputs {
    fun viewIntent(uri: Uri, mime: String) = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    fun shareIntent(uri: Uri, mime: String): Intent {
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(null, uri)
        return Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun launch(context: Context, intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "No app on this phone can open that file.", Toast.LENGTH_SHORT).show()
        }
    }
}
