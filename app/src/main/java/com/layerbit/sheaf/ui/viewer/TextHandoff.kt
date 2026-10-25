package com.layerbit.sheaf.ui.viewer

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.layerbit.sheaf.pdf.LinkGeometry

/**
 * Handing selected text, or a link, to the rest of the phone.
 *
 * Sheaf itself never touches the network, and none of this changes that: each of these asks
 * Android for another app to do the job - a dictionary, a translator, a browser - and that
 * app is the one the reader chose to install. Every handoff is guarded, because on any given
 * phone the app for it may simply not be there, and a missing dictionary is a sentence on the
 * screen, not a crash.
 */
internal object TextHandoff {

    /** Puts [text] on the clipboard. True if it went. */
    fun copy(context: Context, text: String, label: String): Boolean = runCatching {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        true
    }.getOrDefault(false)

    /**
     * Whether the system will say "Copied" itself. From Android 13 it shows its own
     * confirmation, and a second one from the app on top of it is noise.
     */
    val systemConfirmsCopy: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun share(context: Context, text: String): Boolean = start(
        context,
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
            null
        )
    )

    /**
     * Looks a word up. Android 10 has an intent for exactly this; few apps answer it, so the
     * fallback is the text-processing menu every dictionary and translator registers for,
     * which is what a long-press in any other app offers.
     */
    fun define(context: Context, text: String): Boolean =
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            start(context, Intent(ACTION_DEFINE).putExtra(Intent.EXTRA_TEXT, text))) ||
            processText(context, text)

    /** Translates [text] with whichever translator the phone has, by the same two routes. */
    fun translate(context: Context, text: String): Boolean =
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            start(context, Intent(ACTION_TRANSLATE).putExtra(Intent.EXTRA_TEXT, text))) ||
            processText(context, text)

    /**
     * Opens a link from the document in another app. Only the schemes [LinkGeometry] calls
     * safe get this far; a document asking the phone to open anything else is ignored.
     */
    fun openLink(context: Context, uri: String): Boolean {
        if (!LinkGeometry.isSafeWebUri(uri)) return false
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return false
        // Browsable first, as a link tapped in a browser would be; some mail apps only answer
        // the plain form, so that is the second try.
        return start(context, Intent(Intent.ACTION_VIEW, parsed).addCategory(Intent.CATEGORY_BROWSABLE)) ||
            start(context, Intent(Intent.ACTION_VIEW, parsed))
    }

    private fun processText(context: Context, text: String): Boolean = start(
        context,
        Intent.createChooser(
            Intent(Intent.ACTION_PROCESS_TEXT)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true),
            null
        )
    )

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    /** Spelled out rather than taken from [Intent], whose constants for them are API 29. */
    private const val ACTION_DEFINE = "android.intent.action.DEFINE"
    private const val ACTION_TRANSLATE = "android.intent.action.TRANSLATE"
}
