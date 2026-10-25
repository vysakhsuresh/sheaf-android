package com.layerbit.sheaf.files

import android.net.Uri

/**
 * Documents another app shared, waiting for the tool that can take them.
 *
 * A share of one PDF opens in the viewer. A share of several has nowhere to go in a viewer
 * that shows one document at a time, and the obvious thing to want with five PDFs is to merge
 * them - so the activity drops the list here, sends the UI to a tool, and the tool's view
 * model takes the list when it starts.
 *
 * Deliberately a hand-off and not a queue: [take] empties it, so returning to that tool an
 * hour later does not reload what was shared this morning. Uris only - nothing is read here.
 */
class Handoff {

    private var waiting: List<Uri> = emptyList()

    fun offer(uris: List<Uri>) {
        waiting = uris
    }

    private var waitingFiles: List<SheafFile> = emptyList()

    /** Sheaf's own files - opened and looked at already - for the tool picked for them. */
    fun offerFiles(files: List<SheafFile>) {
        waitingFiles = files
    }

    fun takeFiles(): List<SheafFile> {
        val held = waitingFiles
        waitingFiles = emptyList()
        return held
    }

    private var waitingToOpen: List<Uri> = emptyList()

    /**
     * Files shared or opened from another app that are not a PDF - photos, a Word file, a
     * spreadsheet - for Open a file, which looks at them and suggests the tool that fits.
     */
    fun offerToOpen(uris: List<Uri>) {
        waitingToOpen = uris
    }

    fun takeToOpen(): List<Uri> {
        val held = waitingToOpen
        waitingToOpen = emptyList()
        return held
    }

    /** Returns what was waiting and forgets it. */
    fun take(): List<Uri> {
        val held = waiting
        waiting = emptyList()
        return held
    }
}
