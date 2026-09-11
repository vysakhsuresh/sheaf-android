package com.layerbit.sheaf.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Sheaf's only database.
 *
 * It holds a recents list and, from P4, saved operation presets. It never holds document
 * contents. Schemas are exported to app/schemas so a future migration can be written against
 * the real previous version rather than a remembered one.
 */
@Database(
    entities = [RecentEntity::class],
    version = 1,
    exportSchema = true
)
abstract class SheafDatabase : RoomDatabase() {

    abstract fun recentDao(): RecentDao

    companion object {
        @Volatile
        private var instance: SheafDatabase? = null

        fun get(context: Context): SheafDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SheafDatabase::class.java,
                    "sheaf.db"
                )
                    // No fallbackToDestructiveMigration. Losing a recents list is survivable
                    // today, but presets a user has built are not, and the habit of allowing a
                    // destructive fallback is how that gets lost later without anyone noticing.
                    .build().also { instance = it }
            }
    }
}
