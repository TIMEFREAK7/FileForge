package io.github.timefreak7.fileforge.ui

import android.content.Context
import io.github.timefreak7.fileforge.core.Limits
import io.github.timefreak7.fileforge.image.ParserFailure
import io.github.timefreak7.fileforge.jobs.IncomingFile
import io.github.timefreak7.fileforge.pdf.PdfEngineClient
import io.github.timefreak7.fileforge.pdf.PdfEngineClient.Companion.isPassword
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * A PDF a screen has loaded: a private copy, so rendering and the job never depend on a share grant.
 * [password] stays in memory only (S14); [removePassword] is the prompt's "Save without a password".
 */
data class LoadedPdf(val name: String, val file: File, val pages: Int, val password: String? = null, val removePassword: Boolean = false)

/** An open password prompt; [answer] completes with (password, save without password) or null for Cancel. */
class PasswordAsk(val request: PasswordRequest, val answer: CompletableDeferred<Pair<String, Boolean>?>)

/** Copies [file] into [dir] and opens it, asking for its password until it opens. Null when cancelled; throws [ParserFailure]. */
suspend fun loadPdf(
    context: Context,
    engine: PdfEngineClient,
    file: IncomingFile,
    dir: File,
    ask: suspend (PasswordRequest) -> Pair<String, Boolean>?,
): LoadedPdf? {
    val dest = copyPdf(context, file, dir)
    var password: String? = null
    var remove = false
    while (true) {
        try {
            return LoadedPdf(file.name.take(200), dest, engine.pageCount(dest, password), password, remove)
        } catch (e: ParserFailure) {
            if (!e.isPassword) throw e
            val answer = ask(PasswordRequest(file.name, e.message.takeIf { e.code == PdfEngineClient.WRONG_PASSWORD }))
            if (answer == null) {
                dest.delete()
                return null
            }
            password = answer.first
            remove = answer.second
        }
    }
}

/** Copies one PDF into [dir] and checks its header. Throws [ParserFailure]. */
private suspend fun copyPdf(context: Context, file: IncomingFile, dir: File): File {
    val dest = File(dir, UUID.randomUUID().toString())
    withContext(Dispatchers.IO) {
        try {
            val tooBig = context.contentResolver.openInputStream(file.uri)!!.use { input ->
                dest.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > Limits.MAX_PDF_BYTES) return@use true
                        out.write(buffer, 0, n)
                    }
                    false
                }
            }
            if (tooBig) throw ParserFailure("This file is larger than ${Limits.MAX_PDF_BYTES / (1024 * 1024)} MB.")
        } catch (e: ParserFailure) {
            throw e
        } catch (e: Exception) {
            throw ParserFailure("This file could not be read. If it was shared from another app, share it again.")
        }
        val header = dest.inputStream().use { it.readNBytes(5) }
        if (!header.contentEquals("%PDF-".toByteArray())) throw ParserFailure("This is not a PDF.")
    }
    return dest
}
