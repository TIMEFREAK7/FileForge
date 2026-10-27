package io.github.timefreak7.fileforge.image

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.os.DeadObjectException
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.exifinterface.media.ExifInterface
import io.github.timefreak7.fileforge.core.Format
import io.github.timefreak7.fileforge.core.Limits
import io.github.timefreak7.fileforge.core.Preset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.coroutines.resume

/** Main-process side of the image engine: binds the right parser process and enforces the S5 timeout. */
class ImageEngineClient(private val context: Context) : Closeable {

    sealed interface Outcome {
        data class Success(val width: Int, val height: Int) : Outcome
        data class Failure(val message: String) : Outcome
    }

    private class Connection(val conn: ServiceConnection, val engine: IImageEngine)

    private val connections = ConcurrentHashMap<Class<out ImageEngineService>, Connection>()

    /** Converts [input] (detected as [inputFormat]) into [output]. Never throws for a bad file. */
    suspend fun convert(input: File, inputFormat: Format, output: File, target: Format, preset: Preset): Outcome {
        val service = if (inputFormat == Format.HEIC || target == Format.HEIC) {
            MediaImageService::class.java
        } else {
            IsolatedImageService::class.java
        }
        val connection = connections[service] ?: bind(service)?.also { connections[service] = it }
            ?: return Outcome.Failure("The converter could not start.")

        val result = CompletableDeferred<Bundle>()
        val inFd = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY)
        val outFd = ParcelFileDescriptor.open(
            output,
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE,
        )
        // A binder call cannot be interrupted, so it runs on its own thread and is abandoned on timeout.
        thread(name = "image-engine") {
            try {
                result.complete(
                    connection.engine.convert(inFd, outFd, target.mime, preset.quality, preset.maxSide, preset.keepMetadata),
                )
            } catch (t: Throwable) {
                result.completeExceptionally(t)
            } finally {
                inFd.close()
                outFd.close()
            }
        }
        val bundle = try {
            withTimeoutOrNull(Limits.imageTimeoutMs(input.length())) { result.await() }
        } catch (e: DeadObjectException) {
            drop(service)
            return Outcome.Failure("This file crashed the converter, so it was skipped.")
        } catch (e: Exception) {
            drop(service)
            return Outcome.Failure("The converter stopped unexpectedly (${e.javaClass.simpleName}).")
        }
        if (bundle == null) {
            drop(service, kill = true)
            return Outcome.Failure("This file took too long to convert and was stopped.")
        }
        if (!bundle.getBoolean("ok")) return Outcome.Failure(bundle.getString("error") ?: "Conversion failed.")
        bundle.getBundle("exif")?.let { tags ->
            // Writes only into our own freshly encoded output; the untrusted input was parsed remotely.
            try {
                ExifInterface(output).apply {
                    ImageConverter.KEPT_TAGS.forEach { tag -> tags.getString(tag)?.let { setAttribute(tag, it) } }
                    setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                    saveAttributes()
                }
            } catch (e: Exception) {
                return Outcome.Failure("The image converted, but its photo details could not be saved.")
            }
        }
        return Outcome.Success(bundle.getInt("w"), bundle.getInt("h"))
    }

    private suspend fun bind(service: Class<out ImageEngineService>): Connection? =
        suspendCancellableCoroutine { cont ->
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    if (cont.isActive) cont.resume(Connection(this, IImageEngine.Stub.asInterface(binder)))
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    // The process died while idle: forget it so the next file binds a fresh one.
                    if (connections[service]?.conn === this) connections.remove(service)
                    runCatching { context.unbindService(this) }
                }

                override fun onNullBinding(name: ComponentName) {
                    if (cont.isActive) cont.resume(null)
                }
            }
            if (!context.bindService(Intent(context, service), conn, Context.BIND_AUTO_CREATE)) {
                context.unbindService(conn)
                cont.resume(null)
            }
            cont.invokeOnCancellation { context.unbindService(conn) }
        }

    private fun drop(service: Class<out ImageEngineService>, kill: Boolean = false) {
        val connection = connections.remove(service) ?: return
        // shortcut: only the same-uid ":media" process can be killed; a hung isolated process is
        // unbound and left for the system to reclaim. Revisit if hangs show up in the fuzz pass.
        if (kill && service == MediaImageService::class.java) {
            runCatching { Process.killProcess(connection.engine.pid()) }
        }
        runCatching { context.unbindService(connection.conn) }
    }

    override fun close() {
        connections.keys.toList().forEach { drop(it) }
    }

    companion object {
        /** HEIC output needs a hardware HEVC encoder; checked once, in the main process. */
        val heicEncodeAvailable: Boolean by lazy {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any {
                it.isEncoder && it.isHardwareAccelerated && MediaFormat.MIMETYPE_VIDEO_HEVC in it.supportedTypes
            }
        }
    }
}
