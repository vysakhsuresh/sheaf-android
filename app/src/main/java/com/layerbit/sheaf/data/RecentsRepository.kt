package com.layerbit.sheaf.data

import android.content.ContentResolver
import android.content.Intent
import android.content.UriPermission
import android.net.Uri
import com.layerbit.sheaf.data.db.RecentDao
import com.layerbit.sheaf.data.db.RecentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * The recents list, and the persisted Uri grants that let it reopen anything.
 *
 * The two belong together. A row only survives a reboot because a persistable grant was taken
 * for its Uri, and those grants are capped per package - 512 from API 30, 128 before it.
 * Nothing reclaims one on its own, so dropping rows without releasing their grants walks the
 * count towards the cap over months of use, and past the cap every further take fails: the
 * document opens today and refuses to reopen tomorrow, with no way for the user to tell why.
 * Every path that removes a row therefore releases its grant, and [reconcileGrants] clears up
 * whatever was stranded before this held true.
 */
class RecentsRepository(private val dao: RecentDao) {

    /**
     * The resolver these grants are held through.
     *
     * It arrives from the activity rather than from the object graph, because a grant can
     * only be released through the resolver that took it. Written once on the main thread and
     * read from background work, hence the volatile; until it arrives there is nothing to
     * release and every release is skipped.
     */
    @Volatile
    private var resolver: ContentResolver? = null

    fun useResolver(resolver: ContentResolver) {
        this.resolver = resolver
    }

    fun observe(): Flow<List<RecentEntity>> = dao.observeRecents()

    suspend fun record(uri: Uri, displayName: String, pageCount: Int, sizeBytes: Long) {
        // The existing reading position is carried over rather than reset: REPLACE writes a
        // whole row, so reopening a document would otherwise send the reader back to page one.
        val existing = dao.positionFor(uri.toString()) ?: 0
        dao.upsert(
            RecentEntity(
                uri = uri.toString(),
                displayName = displayName,
                pageCount = pageCount,
                sizeBytes = sizeBytes,
                lastOpenedAt = System.currentTimeMillis(),
                lastPage = existing.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            )
        )
        val dropped = dao.urisBeyond(MAX_RECENTS)
        dao.trimTo(MAX_RECENTS)
        if (dropped.isNotEmpty()) {
            // The read and the delete are not one transaction, so a document on the brink of
            // being trimmed can be reopened in between and keep its row. Only grants whose row
            // really went are released: a row left behind with its grant released is a recent
            // entry that will not open, which is worse than a grant held a little too long.
            val surviving = dao.allUris().toSet()
            release(dropped.filterNot { it in surviving })
        }
    }

    /** Where the reader got to in this document, or zero. */
    suspend fun positionFor(uri: String): Int = dao.positionFor(uri) ?: 0

    suspend fun rememberPosition(uri: String, pageIndex: Int) =
        dao.rememberPosition(uri, pageIndex)

    suspend fun forget(uri: String) {
        dao.delete(uri)
        release(listOf(uri))
    }

    suspend fun clear() {
        // Read before the delete for the same reason the trim does: the Uris are the only
        // record of which grants were being held for this list.
        val held = dao.allUris()
        dao.clear()
        release(held)
    }

    /**
     * Gives back persisted grants that no row needs any more.
     *
     * Grants outlive their rows whenever something removed a row without releasing one, and
     * the only way to find those is to compare what the system still holds against what the
     * table says. [keep] is for grants held for something that is not a recent document - the
     * folder results are saved into has no row and must survive this.
     *
     * A release cannot be taken back from here, and a grant released by mistake is a document
     * that never reopens again, so the comparison errs towards keeping: anything this cannot
     * be sure is an orphan stays held, and the next cold start gets another look at it.
     */
    suspend fun reconcileGrants(keep: Set<String> = emptySet()) {
        val resolver = resolver ?: return
        // A grant taken while this runs is the case to be careful of, and a share-sheet intent
        // during a cold start is exactly when that happens. Two things guard against it. The
        // system list is read before the rows and never after, so a grant taken between the two
        // reads is either missing from the list, and so never a candidate, or in the list and
        // also in the rows read afterwards, which keep it. And a take and the row recording it
        // are separate writes, so a grant taken shortly before this started can still have had
        // no row by the time the rows were read: recently persisted grants are left alone for
        // that reason, which costs nothing, because a grant stranded by an earlier version of
        // the app is months old and would be released on this pass or any later one.
        val cutoff = System.currentTimeMillis() - SETTLE_MILLIS
        withContext(Dispatchers.IO) {
            val held = resolver.persistedUriPermissions
            val wanted = dao.allUris().toSet() + keep
            held.filter { grant ->
                // A grant the system reports no persisted time for is not one to act on either.
                val persisted = grant.persistedTime
                grant.uri.toString() !in wanted &&
                    persisted != UriPermission.INVALID_TIME &&
                    persisted < cutoff
            }.forEach { grant ->
                // Released with the modes actually held rather than with read alone: a folder
                // grant carries write too, and half-releasing one would leave it counting
                // against the cap.
                var modes = 0
                if (grant.isReadPermission) {
                    modes = modes or Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                if (grant.isWritePermission) {
                    modes = modes or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                }
                releaseOne(resolver, grant.uri, modes)
            }
        }
    }

    private suspend fun release(uris: List<String>) {
        val resolver = resolver ?: return
        if (uris.isEmpty()) return
        withContext(Dispatchers.IO) {
            uris.forEach {
                releaseOne(resolver, Uri.parse(it), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    /**
     * One release, tolerating a grant that is already gone.
     *
     * The provider may have revoked it, the document may never have been persistable in the
     * first place, or the row may predate the app ever asking. A take that failed is the case
     * this list is built to survive, so a release that finds nothing is not news either.
     */
    private fun releaseOne(resolver: ContentResolver, uri: Uri, modeFlags: Int) {
        runCatching { resolver.releasePersistableUriPermission(uri, modeFlags) }
    }

    companion object {
        const val MAX_RECENTS = 40

        /** How recently a grant may have been taken for [reconcileGrants] to leave it alone. */
        private const val SETTLE_MILLIS = 5 * 60 * 1000L
    }
}
