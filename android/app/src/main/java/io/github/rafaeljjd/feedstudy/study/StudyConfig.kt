package io.github.rafaeljjd.feedstudy.study

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

@OptIn(ExperimentalSerializationApi::class)
val StudyJson = Json {
    namingStrategy = JsonNamingStrategy.SnakeCase
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** Sent by the server at enrolment, so open design decisions need no app release. */
@Serializable
data class StudyConfig(
    val configVersion: String,
    val consentVersion: String,
    val k: Int,
    val ratingSampleSize: Int,
    val lookbackDays: Int,
    val minWatchSeconds: Int,
    val controlArm: String,
    val controlDraw: String,
    val hideCreator: Boolean,
    val priceList: List<Double>,
    val currencySymbol: String,
    val deactivationDays: Int,
    val trackingDays: Int,
    val daySeconds: Long,
    val reminderIntervalSeconds: Long,
    val syncIntervalMinutes: Long,
    val usageSafetyMarginSeconds: Long,
    val complianceReadDelaySeconds: Long,
    val trackedPackages: List<String>,
    val handoffPackages: List<String>,
    val deepLinkTemplate: String,
    val devMode: Boolean,
    val syntheticPlayer: Boolean,
) {
    val isCompressedClock: Boolean get() = daySeconds < 86_400
}

object KnownPackages {
    const val TIKTOK = "com.zhiliaoapp.musically"
    const val TIKTOK_ASIA = "com.ss.android.ugc.trill"
    const val INSTAGRAM = "com.instagram.android"
    const val CHROME = "com.android.chrome"

    val TIKTOK_APPS = listOf(TIKTOK, TIKTOK_ASIA)
    val ALL = listOf(TIKTOK, TIKTOK_ASIA, INSTAGRAM, CHROME)
}
