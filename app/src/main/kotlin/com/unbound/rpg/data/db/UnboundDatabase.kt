package com.unbound.rpg.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        GameEntity::class, WorldEntity::class, PlayerEntity::class, NpcEntity::class,
        LocationEntity::class, FactionEntity::class, ItemEntity::class, ThreadEntity::class,
        RelationshipEntity::class, KnowledgeEntity::class, KnowledgeSubjectRef::class,
        RumorEntity::class, MemoryEntity::class, MemoryEntityRef::class, SummaryEntity::class,
        EventEntity::class, EventEntityRef::class, TurnEntity::class, SnapshotEntity::class,
        ImageEntity::class, UsageEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class UnboundDatabase : RoomDatabase() {
    abstract fun games(): GameDao
    abstract fun worlds(): WorldDao
    abstract fun players(): PlayerDao
    abstract fun npcs(): NpcDao
    abstract fun locations(): LocationDao
    abstract fun factions(): FactionDao
    abstract fun items(): ItemDao
    abstract fun threads(): ThreadDao
    abstract fun relationships(): RelationshipDao
    abstract fun knowledge(): KnowledgeDao
    abstract fun rumors(): RumorDao
    abstract fun memories(): MemoryDao
    abstract fun summaries(): SummaryDao
    abstract fun events(): EventDao
    abstract fun turns(): TurnDao
    abstract fun snapshots(): SnapshotDao
    abstract fun images(): ImageDao
    abstract fun usage(): UsageDao

    companion object {
        private const val NAME = "unbound.db"

        /**
         * Real, versioned migrations only (§99).
         *
         * `fallbackToDestructiveMigration` is deliberately **not** called anywhere in this file.
         * A player's campaign is the product; silently wiping it on a schema change would be worse
         * than crashing, because at least a crash is noticed. When the schema changes, a migration
         * is added to [MIGRATIONS] and the exported schema JSON in `app/schemas` is committed so
         * the change is reviewable in the diff.
         */
        val MIGRATIONS: Array<androidx.room.migration.Migration> = arrayOf()

        @Volatile private var instance: UnboundDatabase? = null

        fun get(context: Context): UnboundDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, UnboundDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        // Room disables these by default; the schema relies on them to keep a
                        // deleted save from leaving orphaned rows behind.
                        db.execSQL("PRAGMA foreign_keys = ON")
                    }
                })
                .build()
                .also { instance = it }
        }
    }
}
