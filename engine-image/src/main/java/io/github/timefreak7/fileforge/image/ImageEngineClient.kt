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
import android.os.SharedMemory
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
        /** [engineMs] and [cpuset] describe the converter process, for the self-test's speed check. */
        data class Success(
            val width: Int,
            val height: Int,
            val engineMs: Long = 0,
            val decodeMs: Long = 0,
            val encodeMs: Long = 0,
            val cpuset: String = "",
        ) : Outcome
        data class Failure(val message: String) : Outcome
    }

    private class Connection(val conn: ServiceConnection, val engine: IImageEngine)

    private val connections = ConcurrentHashMap<Class<out ImageEngineService>, Connection>()

    /** A plain-language failure that ends one conversion. */
    private class Stop(val failure: Outcome.Failure) : Exception()

    /**
     * Converts [input] (detected as [inputFormat]) into [output]. Never throws for a bad file.
     * JPG/PNG/WebP are parsed in the isolated process and encoded in ":media"; HEIC input goes wholly
     * to ":media". [inMediaProcess] forces the whole conversion into ":media" (self-test speed check).
     */
    suspend fun convert(
        input: File,
        inputFormat: Format,
        output: File,
        target: Format,
        preset: Preset,
        inMediaProcess: Boolean = false,
    ): Outcome {
        val timeout = Limits.imageTimeoutMs(input.length())
        val media = MediaImageService::class.java
        return try {
            val (result, exif) = if (inMediaProcess || inputFormat == Format.HEIC) {
                val r = call(media, timeout, readOnly(input), writable(output)) { e, (i, o) ->
                    e.convert(i, o, target.mime, preset.quality, preset.maxSide, preset.keepMetadata)
                }
                r to r.getBundle("exif")
            } else {
                val pixels = call(IsolatedImageService::class.java, timeout, readOnly(input)) { e, (i) ->
                    e.decode(i, preset.maxSide, preset.keepMetadata && target != Format.HEIC)
                }
                try {
                    val encoded = call(media, timeout, writable(output)) { e, (o) ->
                        e.encode(pixels, o, target.mime, preset.quality)
                    }
                    Bundle(pixels).apply { putAll(encoded) } to pixels.getBundle("exif")
                } finally {
                    pixels.getParcelable("mem", SharedMemory::class.java)?.close()
                }
            }
            exif?.let { writeExif(output, it) }
            Outcome.Success(
                result.getInt("w"),
                result.getInt("h"),
                result.getLong("ms"),
                result.getLong("decodeMs"),
                result.getLong("encodeMs"),
                result.getString("cpuset").orEmpty(),
            )
        } catch (e: Stop) {
            e.failure
        }
    }

    private fun readOnly(f: File) = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)

    private fun writable(f: File) = ParcelFileDescriptor.open(
        f,
        ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE,
    )

    /** One call into a parser process, with the S5 timeout. Returns its "ok" bundle or throws [Stop]. */
    private suspend fun call(
        service: Class<out ImageEngineService>,
        timeoutMs: Long,
        vararg fds: ParcelFileDescriptor,
        block: (IImageEngine, Array<out ParcelFileDescriptor>) -> Bundle,
    ): Bundle {
        val connection = connections[service] ?: bind(service)?.also { connections[service] = it }
            ?: run {
                fds.forEach { it.close() }
                throw Stop(Outcome.Failure("The converter could not start."))
            }
        val result = CompletableDeferred<Bundle>()
        // A binder call cannot be interrupted, so it runs on its own thread and is abandoned on timeout.
        thread(name = "image-engine") {
            try {
                result.complete(block(connection.engine, fds))
            } catch (t: Throwable) {
                result.completeExceptionally(t)
            } finally {
                fds.forEach { it.close() }
            }
        }
        val bundle = try {
            withTimeoutOrNull(timeoutMs) { result.await() }
        } catch (e: DeadObjectException) {
            drop(service)
            throw Stop(Outcome.Failure("This file crashed the converter, so it was skipped."))
        } catch (e: Exception) {
            drop(service)
            throw Stop(Outcome.Failure("The converter stopped unexpectedly (${e.javaClass.simpleName})."))
        }
        if (bundle == null) {
            drop(service, kill = true)
            throw Stop(Outcome.Failure("This file took too long to convert and was stopped."))
        }
        bundle.classLoader = SharedMemory::class.java.classLoader
        if (!bundle.getBoolean("ok")) throw Stop(Outcome.Failure(bundle.getString("error") ?: "Conversion failed."))
        return bundle
    }

    /** Writes only into our own freshly encoded output; the untrusted input was parsed remotely. */
    private fun writeExif(output: File, tags: Bundle) {
        try {
            ExifInterface(output).apply {
                ImageConverter.KEPT_TAGS.forEach { tag -> tags.getString(tag)?.let { setAttribute(tag, it) } }
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                saveAttributes()
            }
        } catch (e: Exception) {
            throw Stop(Outcome.Failure("The image converted, but its photo details could not be saved."))
        }
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
            // BIND_IMPORTANT: the converter runs at its client's priority. Without it the phone treated the
            // parser processes as background work and a 12 MP photo took ~75x longer (Phase 1 speed check).
            if (!context.bindService(Intent(context, service), conn, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)) {
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
