package com.physicalactivitygate.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "protected_apps", indices = [Index(value = ["packageName"], unique = true)])
data class ProtectedAppEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appName: String,
    val isProtected: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "activity_rules",
    indices = [Index(value = ["protectedAppId"], unique = true)],
    foreignKeys = [ForeignKey(entity = ProtectedAppEntity::class, parentColumns = ["id"], childColumns = ["protectedAppId"], onDelete = ForeignKey.CASCADE)]
)
data class ActivityRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val protectedAppId: Long,
    val exerciseType: String = "SQUATS",
    val targetCount: Int = 10,
    val durationSeconds: Int = 0,
    val authorizationMinutes: Int = 15,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "activity_sessions",
    indices = [Index("protectedAppId"), Index("startedAt")]
)
data class ActivitySessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val protectedAppId: Long,
    val exerciseType: String,
    val target: Int,
    val completed: Boolean = false,
    val verified: Boolean = true,
    val actualCount: Int = 0,
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
)

@Entity(tableName = "authorizations", indices = [Index("packageName"), Index("expiresAt")])
data class AuthorizationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val authorizedAt: Long,
    val expiresAt: Long,
    val isActive: Boolean = true
)

@Entity(tableName = "daily_statistics", indices = [Index(value = ["dateKey"], unique = true)])
data class DailyStatisticsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateKey: String,
    val exercisesCompleted: Int = 0,
    val repetitions: Int = 0,
    val sessions: Int = 0,
    val unlocks: Int = 0,
    val bypasses: Int = 0,
    val timeAvoidedMinutes: Int = 0
)

@Entity(tableName = "achievements", indices = [Index(value = ["key"], unique = true)])
data class AchievementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val unlockedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "app_usage", indices = [Index(value = ["packageName", "dateKey"], unique = true)])
data class AppUsageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val dateKey: String,
    val minutes: Int,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 1,
    val defaultExercise: String = "SQUATS",
    val defaultTarget: Int = 10,
    val authorizationMinutes: Int = 15,
    val requireEveryTime: Boolean = false,
    val maxUnlocksPerDay: Int = 0,
    val cooldownMinutes: Int = 0,
    val cameraVerification: Boolean = true,
    val exerciseSensitivity: Float = 1f,
    val hapticFeedback: Boolean = true,
    val sound: Boolean = true,
    val usageStats: Boolean = false,
    val notifications: Boolean = true,
    val protectSettings: Boolean = false,
    val pinHash: String? = null,
    val bypassMode: String = "ONE_PER_DAY",
    val isPro: Boolean = false,
    val proPlan: String? = null,
    val proSince: Long? = null,
    // Local-only testing switch (double-tap a plan button on the Subscription
    // screen to toggle). Never touches real billing — purely unlocks Pro
    // features locally so you can test the gated UI on your own device before
    // publishing. Remove the toggle affordance once you're done testing.
    val devProOverride: Boolean = false
)
