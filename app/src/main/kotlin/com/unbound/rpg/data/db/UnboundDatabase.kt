package com.unbound.rpg.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL

@Database(
    entities = [
        GameEntity::class, WorldEntity::class, PlayerEntity::class, NpcEntity::class,
        LocationEntity::class, FactionEntity::class, ItemEntity::class, ThreadEntity::class,
        RelationshipEntity::class, KnowledgeEntity::class, KnowledgeSubjectRef::class,
        RumorEntity::class, MemoryEntity::class, MemoryEntityRef::class, SummaryEntity::class,
        EventEntity::class, EventEntityRef::class, TurnEntity::class, SnapshotEntity::class,
        ImageEntity::class, UsageEntity::class, CommitmentEntity::class,
    ],
    version = 2,
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
    abstract fun commitments(): CommitmentDao

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
        /**
         * v1 -> v2: promises, debts and deals become durable state, and usage learns whose it is.
         *
         * Purely additive. Everything a running campaign already holds — its world, people,
         * events, memories, knowledge, images and settings — is untouched, and the new continuity
         * fields added alongside this (a turn's world-notes, its provider, an event's cause) live
         * in existing JSON payload columns and default to empty on rows written before the
         * upgrade. So an old save opens, plays, and simply has no commitments recorded before the
         * turn on which one is first made.
         */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            // Written against SQLiteConnection rather than SupportSQLiteDatabase so the same
            // migration runs under both Room's driver-based path and the legacy one — which is
            // what lets MigrationTest exercise it directly.
            override fun migrate(connection: androidx.sqlite.SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `commitments` (
                        `id` TEXT NOT NULL,
                        `gameId` TEXT NOT NULL,
                        `fromEntityId` TEXT NOT NULL,
                        `toEntityId` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `createdWorldMinutes` INTEGER NOT NULL,
                        `payload` TEXT NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`gameId`) REFERENCES `games`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_commitments_gameId_status` ON `commitments` (`gameId`, `status`)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_commitments_gameId_fromEntityId` ON `commitments` (`gameId`, `fromEntityId`)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_commitments_gameId_toEntityId` ON `commitments` (`gameId`, `toEntityId`)")

                // Costs are per vendor. Rows written before there was a choice were all OpenAI,
                // which is what the default records — not a guess, the fact.
                connection.execSQL(
                    "ALTER TABLE `usage_records` ADD COLUMN `providerId` TEXT NOT NULL DEFAULT 'openai'",
                )
            }
        }

        val MIGRATIONS: Array<androidx.room.migration.Migration> = arrayOf(MIGRATION_1_2)

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
