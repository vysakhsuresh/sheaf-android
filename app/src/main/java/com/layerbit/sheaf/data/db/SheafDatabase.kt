package com.layerbit.sheaf.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Sheaf's only database.
 *
 * It holds a recents list, where the reader got to in each document, and the pages they
 * marked. It never holds document contents. Schemas are exported to app/schemas so a future
 * migration can be written against the real previous version rather than a remembered one.
 */
@Database(
    entities = [RecentEntity::class, BookmarkEntity::class],
    version = 2,
    exportSchema = true
)
abstract class SheafDatabase : RoomDatabase() {

    abstract fun recentDao(): RecentDao

    abstract fun bookmarkDao(): BookmarkDao

    companion object {
        @Volatile
        private var instance: SheafDatabase? = null

        /**
         * Reading positions and bookmarks, added together because they are the same feature
         * seen from two angles: both are the reader's place in a document rather than
         * anything out of it.
         *
         * Written by hand rather than left to a destructive fallback. A recents list is
         * survivable to lose; a shelf of marked pages is not, and the habit of allowing the
         * fallback is how that gets lost later without anyone noticing.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recents ADD COLUMN lastPage INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS bookmarks (" +
                        "uri TEXT NOT NULL, " +
                        "pageIndex INTEGER NOT NULL, " +
                        "label TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(uri, pageIndex))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bookmarks_uri ON bookmarks (uri)")
            }
        }

        fun get(context: Context): SheafDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SheafDatabase::class.java,
                    "sheaf.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build().also { instance = it }
            }
    }
}
