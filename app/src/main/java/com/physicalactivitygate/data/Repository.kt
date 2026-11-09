package com.physicalactivitygate.data

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class Repository(private val db: AppDatabase) {
    fun apps(): Flow<List<ProtectedAppEntity>> = db.protectedApps().observeAll()
    suspend fun protectedCount(): Int = db.protectedApps().countProtected()
    fun history(): Flow<List<ActivitySessionEntity>> = db.sessions().observeHistory()
    fun statistics(): Flow<List<DailyStatisticsEntity>> = db.statistics().observe()
    fun achievements(): Flow<List<AchievementEntity>> = db.achievements().observe()
    fun usage(): Flow<List<AppUsageEntity>> = db.usage().observe()
    fun settings(): Flow<SettingsEntity> = db.settings().observe().map { it ?: SettingsEntity() }
    suspend fun app(pkg: String) = db.protectedApps().find(pkg)
    suspend fun rule(appId: Long) = db.rules().find(appId)
    suspend fun saveApp(a: ProtectedAppEntity) = db.protectedApps().upsert(a)
    suspend fun saveRule(r: ActivityRuleEntity) = db.rules().upsert(r)
    suspend fun recordSession(s: ActivitySessionEntity) { db.sessions().insert(s); updateDailyStats(s) }
    suspend fun authorize(pkg: String, minutes: Int): Long {
        val now = System.currentTimeMillis(); db.authorizations().expire(now)
        val expiresAt = if (minutes < 0) Long.MAX_VALUE else now + minutes.coerceAtLeast(1) * 60_000L
        // Clear any stale rows for this package so an old grant can never linger beside the new one.
        db.authorizations().revoke(pkg)
        db.authorizations().insert(AuthorizationEntity(packageName = pkg, authorizedAt = now, expiresAt = expiresAt))
        updateUnlocks()
        return expiresAt
    }
    suspend fun authorized(pkg: String): Boolean {
        val now = System.currentTimeMillis(); db.authorizations().expire(now)
        return db.authorizations().valid(pkg, now) != null
    }
    suspend fun revoke(pkg: String) = db.authorizations().revoke(pkg)
    // The actual grant currently in force for pkg (or null), independent of
    // whatever the app's rule says right now. See AuthorizationDao.current.
    suspend fun currentAuthorization(pkg: String) = db.authorizations().current(pkg)
    // See AuthorizationDao.revokeAllUnlimited — safety net run at service
    // (re)start so a stale "until app is closed" grant can never survive a
    // service restart or device reboot and let a protected app open freely.
    suspend fun revokeAllUnlimitedSessions(): Int = db.authorizations().revokeAllUnlimited()
    suspend fun activeUnlimitedSessions() = db.authorizations().activeUnlimited()
    suspend fun getSettings() = db.settings().get() ?: SettingsEntity()
    suspend fun saveSettings(s: SettingsEntity) = db.settings().save(s)
    suspend fun recordUsage(packageName: String, minutes: Int) {
        val key = today(); db.usage().upsert(AppUsageEntity(packageName = packageName, dateKey = key, minutes = minutes.coerceAtLeast(0)))
    }
    suspend fun setPin(pin: String?) = saveSettings(getSettings().copy(pinHash = pin?.let(::hash)))
    suspend fun checkPin(pin: String): Boolean = getSettings().pinHash == hash(pin)
    suspend fun todayUnlocks(): Int = db.statistics().find(today())?.unlocks ?: 0
    suspend fun todayBypasses(): Int = db.statistics().find(today())?.bypasses ?: 0
    suspend fun recordBypass() { val key=today(); val old=db.statistics().find(key) ?: DailyStatisticsEntity(dateKey=key); db.statistics().upsert(old.copy(bypasses=old.bypasses+1)) }
    suspend fun inCooldown(appId: Long? = null): Boolean { val mins = getSettings().cooldownMinutes; if (mins <= 0) return false; val last = if (appId == null) db.sessions().lastCompleted() else db.sessions().lastCompletedForApp(appId); val at = last?.completedAt ?: return false; return System.currentTimeMillis() - at < mins * 60_000L }

    private suspend fun updateDailyStats(s: ActivitySessionEntity) {
        val key = today(); val old = db.statistics().find(key) ?: DailyStatisticsEntity(dateKey = key)
        db.statistics().upsert(old.copy(
            exercisesCompleted = old.exercisesCompleted + if (s.completed) 1 else 0,
            repetitions = old.repetitions + s.actualCount,
            sessions = old.sessions + 1
        ))
        if (s.completed) {
            val thresholds = listOf(1 to "FIRST_SESSION", 10 to "TEN_SESSIONS", 100 to "HUNDRED_REPS", 1000 to "THOUSAND_REPS")
            val reps = db.sessions().completedReps(); val sessions = db.sessions().completedCount()
            thresholds.forEach { (n, keyName) -> if ((keyName == "FIRST_SESSION" && sessions >= 1) || (keyName == "TEN_SESSIONS" && sessions >= n) || (keyName == "HUNDRED_REPS" && reps >= n) || (keyName == "THOUSAND_REPS" && reps >= n)) db.achievements().insert(AchievementEntity(key = keyName)) }
            val activeDates = db.statistics().recent().filter { it.exercisesCompleted > 0 }.map { it.dateKey }.toSet()
            var cursor = java.time.LocalDate.now()
            var streak = 0
            while (activeDates.contains(cursor.toString())) { streak++; cursor = cursor.minusDays(1) }
            if (streak >= 7) db.achievements().insert(AchievementEntity(key = "SEVEN_DAY_STREAK"))
        }
    }
    private suspend fun updateUnlocks() { val key = today(); val old = db.statistics().find(key) ?: DailyStatisticsEntity(dateKey = key); db.statistics().upsert(old.copy(unlocks = old.unlocks + 1)) }
    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
