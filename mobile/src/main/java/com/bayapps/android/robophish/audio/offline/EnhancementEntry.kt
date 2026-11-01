package com.bayapps.android.robophish.audio.offline

enum class EnhancementStatus { QUEUED, ANALYZING, PROCESSING, READY, UNCHANGED, FAILED, CANCELLED }

/** The original is never replaced. Each render has a new generation to reject stale workers. */
data class EnhancementEntry(
    val trackKey: String,
    val title: String,
    val generation: String,
    val sourceUri: String,
    val sourceSize: Long,
    val sourceModified: Long,
    val algorithmVersion: String,
    val sourceSha256: String? = null,
    val status: EnhancementStatus = EnhancementStatus.QUEUED,
    val progress: Int = 0,
    val fileUri: String? = null,
    val outputBytes: Long = 0,
    val error: String? = null,
    val preferEnhanced: Boolean = false,
    val profileSummary: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    val isActive: Boolean get() = status in setOf(EnhancementStatus.QUEUED,
        EnhancementStatus.ANALYZING, EnhancementStatus.PROCESSING)
}
