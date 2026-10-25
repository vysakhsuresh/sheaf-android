package com.layerbit.sheaf.prefs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Everything the user has told the app to remember about itself.
 *
 * SharedPreferences rather than a table. These are a dozen flags read on almost every frame
 * of composition - the theme decides every colour on screen - and a Room query behind a flow
 * would mean the first frame of the app renders in the wrong theme and then corrects itself.
 * Preferences are already in memory by the time anything asks.
 *
 * Nothing about a document is stored here. Favourites are tool names, which are the app's own
 * identifiers, not the user's content.
 */
class SheafSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("sheaf.settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<Settings> = _state.asStateFlow()

    /** The value right now, for the rare caller that is not in composition. */
    val current: Settings get() = _state.value

    fun update(transform: (Settings) -> Settings) {
        val updated = transform(_state.value)
        prefs.edit()
            .putString(KEY_THEME, updated.theme.name)
            .putBoolean(KEY_KEEP_AWAKE, updated.keepScreenOn)
            .putBoolean(KEY_RESUME, updated.resumeReading)
            .putBoolean(KEY_PAGE_BADGE, updated.showPageBadge)
            .putBoolean(KEY_OPEN_RESULT, updated.openResultWhenDone)
            .putBoolean(KEY_CONFIRM_SAVE, updated.askWhereToSave)
            .putStringSet(KEY_FAVOURITES, updated.favouriteTools)
            .putString(KEY_SAVE_FOLDER, updated.saveFolder)
            .putString(KEY_RECENT_TOOLS, updated.recentTools.joinToString(","))
            .putBoolean(KEY_EDITABLE_MARKS, updated.editableMarks)
            .apply()
        _state.value = updated
    }

    /** Puts [tool] at the front of the recent tools. */
    fun rememberTool(tool: String) = update { settings ->
        settings.copy(recentTools = (listOf(tool) + settings.recentTools.filter { it != tool }).take(MAX_RECENT_TOOLS))
    }

    fun toggleFavourite(tool: String) = update { settings ->
        val favourites = settings.favouriteTools.toMutableSet()
        if (!favourites.remove(tool)) favourites += tool
        settings.copy(favouriteTools = favourites)
    }

    private fun read(): Settings {
        val theme = prefs.getString(KEY_THEME, null)
            ?.let { name -> ThemeChoice.entries.firstOrNull { it.name == name } }
            ?: ThemeChoice.SYSTEM
        return Settings(
            theme = theme,
            keepScreenOn = prefs.getBoolean(KEY_KEEP_AWAKE, true),
            resumeReading = prefs.getBoolean(KEY_RESUME, true),
            showPageBadge = prefs.getBoolean(KEY_PAGE_BADGE, true),
            openResultWhenDone = prefs.getBoolean(KEY_OPEN_RESULT, false),
            askWhereToSave = prefs.getBoolean(KEY_CONFIRM_SAVE, true),
            favouriteTools = prefs.getStringSet(KEY_FAVOURITES, emptySet()).orEmpty(),
            saveFolder = prefs.getString(KEY_SAVE_FOLDER, null),
            recentTools = prefs.getString(KEY_RECENT_TOOLS, null).orEmpty().split(",").filter { it.matches(Regex("[A-Z_]+")) },
            editableMarks = prefs.getBoolean(KEY_EDITABLE_MARKS, false)
        )
    }

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_KEEP_AWAKE = "keepScreenOn"
        const val KEY_RESUME = "resumeReading"
        const val KEY_PAGE_BADGE = "showPageBadge"
        const val KEY_OPEN_RESULT = "openResultWhenDone"
        const val KEY_CONFIRM_SAVE = "askWhereToSave"
        const val KEY_FAVOURITES = "favouriteTools"
        const val KEY_SAVE_FOLDER = "saveFolder"
        const val KEY_RECENT_TOOLS = "recentTools"
        const val KEY_EDITABLE_MARKS = "editableMarks"
        const val MAX_RECENT_TOOLS = 6
    }
}

data class Settings(
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    /** A reader looks at a page without touching it, and the screen going dark is the result. */
    val keepScreenOn: Boolean = true,
    /** Reopen a document where it was left rather than at page one. */
    val resumeReading: Boolean = true,
    /** The page counter that floats over the document. */
    val showPageBadge: Boolean = true,
    /** Open a finished result in the viewer the moment it is ready. */
    val openResultWhenDone: Boolean = false,
    /**
     * Whether saving asks for a destination each time.
     *
     * Off means a result goes to the folder chosen once, which is what makes a batch of forty
     * exported pages one gesture instead of forty.
     */
    val askWhereToSave: Boolean = true,
    val favouriteTools: Set<String> = emptySet(),
    /**
     * The folder results go to when [askWhereToSave] is off.
     *
     * A tree Uri the user granted once, held across restarts. Null until they have chosen
     * one, which is why turning the setting off does not immediately change anything: the
     * next save asks for the folder, and every save after it is silent.
     */
    val saveFolder: String? = null,
    /**
     * The tools opened lately, newest first, at most six. Tool names only: never a file.
     */
    val recentTools: List<String> = emptyList(),
    /**
     * Mark up writes highlights, ink, shapes and notes as annotations another reader can
     * still edit or delete, instead of drawing them into the page. Off by default, because a
     * mark that someone else's app can quietly remove is not what most people sending a
     * marked-up document expect. Sign and Remove areas always flatten, whatever this says.
     */
    val editableMarks: Boolean = false
)

enum class ThemeChoice(val label: String) {
    SYSTEM("Match the system"),
    LIGHT("Light"),
    DARK("Dark")
}
