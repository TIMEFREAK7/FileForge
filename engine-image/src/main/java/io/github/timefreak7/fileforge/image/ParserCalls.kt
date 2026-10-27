package io.github.timefreak7.fileforge.image

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.DeadObjectException
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SharedMemory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.coroutines.resume

/** A plain-language failure that ends one operation; its message is shown to the user. */
class ParserFailure(message: String) : Exception(message)

/**
 * Main-process side of the parser processes: binds them, runs one call at a time with the S5 timeout,
 * and turns crashes and hangs into [ParserFailure]. Shared by the image and PDF engines.
 */
class ParserCalls<T : Any>(
    private val context: Context,
    private val asInterface: (IBinder) -> T,
    private val pidOf: (T) -> Int,
) : Closeable {
    private class Connection<T>(val conn: ServiceConnection, val engine: T)

    private val connections = ConcurrentHashMap<Class<out Service>, Connection<T>>()

    /**
     * Calls [block] in [service]'s process and returns its "ok" bundle, or throws [ParserFailure].
     * [fds] are closed here whatever happens. [killable] marks a same-uid process that may be killed on timeout.
     */
    suspend fun call(
        service: Class<out Service>,
        timeoutMs: Long,
        killable: Boolean,
        vararg fds: ParcelFileDescriptor,
        block: (T, Array<out ParcelFileDescriptor>) -> Bundle,
    ): Bundle {
        val connection = connections[service] ?: bind(service)?.also { connections[service] = it }
            ?: run {
                fds.forEach { it.close() }
                throw ParserFailure("The converter could not start.")
            }
        val result = CompletableDeferred<Bundle>()
        // A binder call cannot be interrupted, so it runs on its own thread and is abandoned on timeout.
        thread(name = "parser-call") {
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
            drop(service, false)
            throw ParserFailure("This file crashed the converter, so it was skipped.")
        } catch (e: Exception) {
            drop(service, false)
            throw ParserFailure("The converter stopped unexpectedly (${e.javaClass.simpleName}).")
        }
        if (bundle == null) {
            drop(service, killable)
            throw ParserFailure("This file took too long to convert and was stopped.")
        }
        bundle.classLoader = SharedMemory::class.java.classLoader
        if (!bundle.getBoolean("ok")) throw ParserFailure(bundle.getString("error") ?: "Conversion failed.")
        return bundle
    }

    private suspend fun bind(service: Class<out Service>): Connection<T>? =
        suspendCancellableCoroutine { cont ->
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    if (cont.isActive) cont.resume(Connection(this, asInterface(binder)))
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    // The process died while idle: forget it so the next call binds a fresh one.
                    if (connections[service]?.conn === this) connections.remove(service)
                    runCatching { context.unbindService(this) }
                }

                override fun onNullBinding(name: ComponentName) {
                    if (cont.isActive) cont.resume(null)
                }
            }
            if (!context.bindService(Intent(context, service), conn, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)) {
                context.unbindService(conn)
                cont.resume(null)
            }
            cont.invokeOnCancellation { context.unbindService(conn) }
        }

    private fun drop(service: Class<out Service>, kill: Boolean) {
        val connection = connections.remove(service) ?: return
        // shortcut: only a same-uid (":media") process can be killed; a hung isolated process is
        // unbound and left for the system to reclaim. Revisit if hangs show up in the fuzz pass.
        if (kill) runCatching { Process.killProcess(pidOf(connection.engine)) }
        runCatching { context.unbindService(connection.conn) }
    }

    override fun close() {
        connections.keys.toList().forEach { drop(it, false) }
    }
}
