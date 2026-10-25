package com.layerbit.sheaf.files

import android.content.Context
import android.os.Build
import com.layerbit.sheaf.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The crash log the user sends themselves.
 *
 * Sheaf has no INTERNET permission, so there is no crash reporter and never will be. What it
 * does instead is write the stack trace to its own private storage as the process dies, and
 * offer it from Settings, where the user decides whether it goes anywhere and to whom. Nothing
 * from a document is in it: a stack trace names Sheaf's own classes, and the header is the
 * app version and the phone model, which is what a fix needs and all it gets.
 */
object CrashLog {

    private const val DIR = "crashes"

    /** A handful is plenty to see a pattern; more is a folder nobody reads. */
    private const val KEEP = 5

    fun directory(context: Context): File = File(context.filesDir, DIR)

    /**
     * Chains in ahead of whatever handler is installed now, so the system's own crash dialog
     * and process death still happen exactly as before - this only writes a file first.
     */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Nothing in here may throw: an exception from a crash handler replaces the one
            // being reported, and the log of the real crash is lost.
            runCatching { write(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The newest log, or null when Sheaf has never crashed here. */
    fun latest(context: Context): File? =
        directory(context).listFiles { f -> f.name.endsWith(".txt") }?.maxByOrNull { it.lastModified() }

    fun count(context: Context): Int =
        directory(context).listFiles { f -> f.name.endsWith(".txt") }?.size ?: 0

    fun clear(context: Context) {
        directory(context).listFiles()?.forEach { it.delete() }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val dir = directory(context).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val text = buildString {
            appendLine("Sheaf ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Thread: ${thread.name}")
            appendLine("When: $stamp")
            appendLine()
            append(trace)
        }
        File(dir, "crash-$stamp.txt").writeText(text)
        dir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(KEEP)
            ?.forEach { it.delete() }
    }
}
