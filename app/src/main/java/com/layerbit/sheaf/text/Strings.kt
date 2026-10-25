package com.layerbit.sheaf.text

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * Translated text for code that has no Context of its own: ops, the PDF layer, view models'
 * messages, job states.
 *
 * Composables use stringResource() and pluralStringResource() as usual; this is for the
 * sentences that are made far from any screen and shown on one later - "Page 4 could not be
 * read" is written inside an op and read in the result list. It reads through the
 * application's resources, which Android updates when the language changes, so a string made
 * after a switch is in the new language.
 *
 * Installed once, first thing in SheafApplication.onCreate.
 */
object Strings {

    private lateinit var app: Context

    fun install(context: Context) {
        app = context.applicationContext
    }

    /** Whether [install] has run - false only in plain JVM unit tests. */
    val installed: Boolean get() = ::app.isInitialized

    /**
     * Outside the app - a JVM unit test of pure logic - there are no resources, so the answer
     * is the id and the arguments, which a test can still check for the numbers it expects.
     */
    fun get(@StringRes id: Int, vararg args: Any): String = when {
        !installed -> "#$id" + args.joinToString(prefix = "(", postfix = ")")
        args.isEmpty() -> app.getString(id)
        else -> app.getString(id, *args)
    }

    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String {
        val values = if (args.isEmpty()) arrayOf<Any>(count) else args
        return if (!installed) "#$id" + values.joinToString(prefix = "(", postfix = ")")
        else app.resources.getQuantityString(id, count, *values)
    }
}
