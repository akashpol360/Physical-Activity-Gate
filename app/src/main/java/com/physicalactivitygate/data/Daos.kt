package com.physicalactivitygate.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao interface ProtectedAppDao {
    @Query("SELECT * FROM protected_apps ORDER BY appName") fun observeAll(): Flow<List<ProtectedAppEntity>>
    @Query("SELECT * FROM protected_apps WHERE packageName=:pkg LIMIT 1") suspend fun find(pkg: String): ProtectedAppEntity?
    @Query("SELECT COUNT(*) FROM protected_apps WHERE isProtected=1") suspend fun countProtected(): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(app: ProtectedAppEntity): Long
    @Query("DELETE FROM protected_apps WHERE packageName=:pkg") suspend fun delete(pkg: String)
}

@Dao interface RuleDao {
    @Query("SELECT * FROM activity_rules WHERE protectedAppId=:appId LIMIT 1") suspend fun find(appId: Long): ActivityRuleEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(rule: ActivityRuleEntity): Long
}

@Dao interface SessionDao {
    @Insert suspend fun insert(s: ActivitySessionEntity): Long
    @Query("SELECT * FROM activity_sessions ORDER BY startedAt DESC LIMIT 200") fun observeHistory(): Flow<List<ActivitySessionEntity>>
    @Query("SELECT COUNT(*) FROM activity_sessions WHERE completed=1") suspend fun completedCount(): Int
    @Query("SELECT * FROM activity_sessions WHERE completedAt IS NOT NULL ORDER BY completedAt DESC LIMIT 1") suspend fun lastCompleted(): ActivitySessionEntity?
    @Query("SELECT * FROM activity_sessions WHERE protectedAppId=:appId AND completedAt IS NOT NULL ORDER BY completedAt DESC LIMIT 1") suspend fun lastCompletedForApp(appId: Long): ActivitySessionEntity?
    @Query("SELECT COALESCE(SUM(actualCount),0) FROM activity_sessions WHERE completed=1") suspend fun completedReps(): Int
}

@Dao interface AuthorizationDao {
    @Query("SELECT * FROM authorizations WHERE packageName=:pkg AND isActive=1 AND (expiresAt=9223372036854775807 OR expiresAt>:now) LIMIT 1") suspend fun valid(pkg: String, now: Long): AuthorizationEntity?
    // Most recent *active* grant for a package, regardless of its expiry
    // time. Used to find out what kind of session is actually running
    // (timed vs. "until app is closed") from the grant itself, rather than
    // from the app's current rule — the rule can be edited by the user
    // after the grant was made, which must never change how an
    // already-running session behaves.
    @Query("SELECT * FROM authorizations WHERE packageName=:pkg AND isActive=1 ORDER BY id DESC LIMIT 1") suspend fun current(pkg: String): AuthorizationEntity?
    @Query("SELECT * FROM authorizations WHERE isActive=1 AND expiresAt=9223372036854775807") suspend fun activeUnlimited(): List<AuthorizationEntity>
    @Insert suspend fun insert(a: AuthorizationEntity): Long
    @Query("UPDATE authorizations SET isActive=0 WHERE expiresAt<>9223372036854775807 AND expiresAt<=:now") suspend fun expire(now: Long)
    @Query("UPDATE authorizations SET isActive=0 WHERE packageName=:pkg") suspend fun revoke(pkg: String)
    // "Until app is closed" grants are stored with expiresAt=Long.MAX_VALUE
    // (see Repository.authorize) so the time-based expire() above can never
    // clean them up — they only ever end when the accessibility service
    // notices the user leave the app. If that service was killed/restarted
    // (battery optimization, OS memory pressure, or a device reboot) while
    // such a grant was active, that notice never happens and the row is
    // left valid forever. Call this once whenever the service (re)starts,
    // since at that point we've lost all memory of who was actually still
    // on-screen and can no longer trust any existing open-ended grant.
    @Query("UPDATE authorizations SET isActive=0 WHERE isActive=1 AND expiresAt=9223372036854775807") suspend fun revokeAllUnlimited(): Int
}

@Dao interface StatisticsDao {
    @Query("SELECT * FROM daily_statistics ORDER BY dateKey DESC LIMIT 365") fun observe(): Flow<List<DailyStatisticsEntity>>
    @Query("SELECT * FROM daily_statistics ORDER BY dateKey DESC LIMIT 365") suspend fun recent(): List<DailyStatisticsEntity>
    @Query("SELECT * FROM daily_statistics WHERE dateKey=:dateKey LIMIT 1") suspend fun find(dateKey: String): DailyStatisticsEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(s: DailyStatisticsEntity)
}

@Dao interface AchievementDao {
    @Query("SELECT * FROM achievements ORDER BY unlockedAt DESC") fun observe(): Flow<List<AchievementEntity>>
    @Query("SELECT * FROM achievements WHERE key=:key LIMIT 1") suspend fun find(key: String): AchievementEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(a: AchievementEntity)
}

@Dao interface UsageDao {
    @Query("SELECT * FROM app_usage ORDER BY dateKey DESC, minutes DESC LIMIT 500") fun observe(): Flow<List<AppUsageEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(u: AppUsageEntity)
}

@Dao interface SettingsDao {
    @Query("SELECT * FROM settings WHERE id=1") fun observe(): Flow<SettingsEntity?>
    @Query("SELECT * FROM settings WHERE id=1") suspend fun get(): SettingsEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(s: SettingsEntity)
}
