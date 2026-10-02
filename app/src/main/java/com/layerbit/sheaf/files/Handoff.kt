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

    /** Returns what was waiting and forgets it. */
    fun take(): List<Uri> {
        val held = waiting
        waiting = emptyList()
        return held
    }
}
