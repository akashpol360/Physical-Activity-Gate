package com.physicalactivitygate.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ProtectedAppEntity::class, ActivityRuleEntity::class, ActivitySessionEntity::class,
        AuthorizationEntity::class, DailyStatisticsEntity::class, AchievementEntity::class,
        AppUsageEntity::class, SettingsEntity::class],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun protectedApps(): ProtectedAppDao
    abstract fun rules(): RuleDao
    abstract fun sessions(): SessionDao
    abstract fun authorizations(): AuthorizationDao
    abstract fun statistics(): StatisticsDao
    abstract fun achievements(): AchievementDao
    abstract fun usage(): UsageDao
    abstract fun settings(): SettingsDao

    companion object {
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS activity_rules_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, protectedAppId INTEGER NOT NULL, exerciseType TEXT NOT NULL, targetCount INTEGER NOT NULL, durationSeconds INTEGER NOT NULL, authorizationMinutes INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(protectedAppId) REFERENCES protected_apps(id) ON DELETE CASCADE)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_activity_rules_new_protectedAppId ON activity_rules_new(protectedAppId)")
                db.execSQL("INSERT INTO activity_rules_new(id, protectedAppId, exerciseType, targetCount, durationSeconds, authorizationMinutes, createdAt, updatedAt) SELECT id, protectedAppId, exerciseType, targetCount, durationSeconds, authorizationMinutes, createdAt, updatedAt FROM activity_rules")
                db.execSQL("DROP TABLE activity_rules")
                db.execSQL("ALTER TABLE activity_rules_new RENAME TO activity_rules")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_activity_rules_protectedAppId ON activity_rules(protectedAppId)")

                db.execSQL("CREATE TABLE IF NOT EXISTS activity_sessions_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, protectedAppId INTEGER NOT NULL, exerciseType TEXT NOT NULL, target INTEGER NOT NULL, completed INTEGER NOT NULL, verified INTEGER NOT NULL, actualCount INTEGER NOT NULL, startedAt INTEGER NOT NULL, completedAt INTEGER, FOREIGN KEY(protectedAppId) REFERENCES protected_apps(id) ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_activity_sessions_new_protectedAppId ON activity_sessions_new(protectedAppId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_activity_sessions_new_startedAt ON activity_sessions_new(startedAt)")
                db.execSQL("INSERT INTO activity_sessions_new(id, protectedAppId, exerciseType, target, completed, verified, actualCount, startedAt, completedAt) SELECT id, protectedAppId, exerciseType, target, completed, verified, actualCount, startedAt, completedAt FROM activity_sessions")
                db.execSQL("DROP TABLE activity_sessions")
                db.execSQL("ALTER TABLE activity_sessions_new RENAME TO activity_sessions")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_activity_sessions_protectedAppId ON activity_sessions(protectedAppId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_activity_sessions_startedAt ON activity_sessions(startedAt)")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE settings ADD COLUMN isPro INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE settings ADD COLUMN proPlan TEXT")
                db.execSQL("ALTER TABLE settings ADD COLUMN proSince INTEGER")
                db.execSQL("ALTER TABLE settings ADD COLUMN devProOverride INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Activity history must never be silently destroyed as a side
                // effect of a protected app's row being replaced/recreated
                // internally. Recreate the sessions table WITHOUT a cascading
                // foreign key, so recorded history is permanent and fully
                // independent of the protected_apps table going forward.
                db.execSQL("CREATE TABLE IF NOT EXISTS activity_sessions_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, protectedAppId INTEGER NOT NULL, exerciseType TEXT NOT NULL, target INTEGER NOT NULL, completed INTEGER NOT NULL, verified INTEGER NOT NULL, actualCount INTEGER NOT NULL, startedAt INTEGER NOT NULL, completedAt INTEGER)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_activity_sessions_new_protectedAppId ON activity_sessions_new(protectedAppId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_activity_sessions_new_startedAt ON activity_sessions_new(startedAt)")
                db.execSQL("INSERT INTO activity_sessions_new(id, protectedAppId, exerciseType, target, completed, verified, actualCount, startedAt, completedAt) SELECT id, protectedAppId, exerciseType, target, completed, verified, actualCount, startedAt, completedAt FROM activity_sessions")
                db.execSQL("DROP TABLE activity_sessions")
                db.execSQL("ALTER TABLE activity_sessions_new RENAME TO activity_sessions")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_activity_sessions_protectedAppId ON activity_sessions(protectedAppId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_activity_sessions_startedAt ON activity_sessions(startedAt)")
            }
        }

        @Volatile private var INSTANCE: AppDatabase? = null
        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "physical_activity_gate.db")
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build().also { INSTANCE = it }
        }
    }
}
