package com.layerbit.sheaf.prefs

import android.content.Context

/**
 * What the app has already asked or shown once, so it never asks or shows it twice.
 *
 * Kept apart from [SheafSettings] because none of this is a choice the user makes on the
 * Settings screen: it is the app's own memory of having been polite already. A settings reset
 * should not bring the intro cards back, and an intro being seen should not ride along in
 * every settings write.
 */
class Prompts(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("sheaf.prompts", Context.MODE_PRIVATE)

    /** The one-line explanation before Android's notification dialog has been shown. */
    var askedNotifications: Boolean
        get() = prefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false)
        set(value) = prefs.edit().putBoolean(KEY_ASKED_NOTIFICATIONS, value).apply()

    /** The three intro cards on first run. */
    var introSeen: Boolean
        get() = prefs.getBoolean(KEY_INTRO_SEEN, false)
        set(value) = prefs.edit().putBoolean(KEY_INTRO_SEEN, value).apply()

    /** Jobs that finished with every file produced, counted towards the review prompt. */
    var successfulJobs: Int
        get() = prefs.getInt(KEY_SUCCESSES, 0)
        set(value) = prefs.edit().putInt(KEY_SUCCESSES, value).apply()

    /** When the Play review sheet was last asked for, in epoch millis; 0 for never. */
    var lastReviewAsk: Long
        get() = prefs.getLong(KEY_LAST_REVIEW, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_REVIEW, value).apply()

    /**
     * Whether now is a good moment to ask for a review: after the third job that worked, and
     * never twice inside sixty days. Play applies its own quota on top, so this is a floor on
     * politeness rather than a guarantee the sheet appears.
     */
    fun reviewDue(now: Long): Boolean =
        successfulJobs >= REVIEW_AFTER_JOBS && now - lastReviewAsk >= REVIEW_GAP_MS

    companion object {
        private const val KEY_ASKED_NOTIFICATIONS = "askedNotifications"
        private const val KEY_INTRO_SEEN = "introSeen"
        private const val KEY_SUCCESSES = "successfulJobs"
        private const val KEY_LAST_REVIEW = "lastReviewAsk"
        const val REVIEW_AFTER_JOBS = 3
        const val REVIEW_GAP_MS = 60L * 24 * 60 * 60 * 1000
    }
}
