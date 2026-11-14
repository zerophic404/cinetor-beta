package com.mythbrix.cinetor.core.download

enum class NetworkType { NONE, UNMETERED, METERED }

/** Device state the queue cares about. */
data class DeviceConditions(
    val network: NetworkType = NetworkType.UNMETERED,
    val charging: Boolean = true,
    val freeBytes: Long = Long.MAX_VALUE,
)

/** User download settings (Settings → Downloads). */
data class QueuePolicy(
    /** 1–5 parallel downloads. */
    val maxConcurrent: Int = 2,
    val wifiOnly: Boolean = false,
    val chargingOnly: Boolean = false,
    /** Stop when free space drops below this. */
    val minFreeBytes: Long = 300 * MIB,
    /** 0 = unlimited. */
    val speedLimitBps: Long = 0,
    /** 0 = auto ([defaultConnections]). */
    val connections: Int = 0,
) {
    val concurrency: Int get() = maxConcurrent.coerceIn(1, 5)
}

/** Why nothing may run right now (null = free to run). */
fun blockedReason(policy: QueuePolicy, c: DeviceConditions): String? = when {
    c.network == NetworkType.NONE -> "Waiting for network"
    policy.wifiOnly && c.network != NetworkType.UNMETERED -> "Waiting for Wi-Fi"
    policy.chargingOnly && !c.charging -> "Waiting for charging"
    c.freeBytes < policy.minFreeBytes -> "Low storage"
    else -> null
}

/** What the engine must change: start these ids, stop these ids (→ [stopStatus]). */
data class QueueDecision(
    val toStart: List<String>,
    val toStop: List<String>,
    val stopStatus: DownloadStatus,
    val waitingReason: String?,
)

/**
 * Pure scheduler. Runnable = QUEUED / DOWNLOADING / WAITING (user-paused, done and failed never run),
 * ordered by priority then age; the first [QueuePolicy.concurrency] should run. Blocked by a constraint
 * → every running download stops as WAITING.
 */
fun decideQueue(records: List<DownloadRecord>, running: Set<String>, policy: QueuePolicy, c: DeviceConditions): QueueDecision {
    val runnable = records
        .filter { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.WAITING }
        .sortedWith(compareBy<DownloadRecord>({ it.priority }, { it.createdAt }))
    val reason = blockedReason(policy, c)
    if (reason != null) {
        return QueueDecision(emptyList(), running.toList(), DownloadStatus.WAITING, reason)
    }
    val wanted = runnable.take(policy.concurrency).map { it.id }
    return QueueDecision(
        toStart = wanted.filter { it !in running },
        toStop = running.filter { it !in wanted },
        stopStatus = DownloadStatus.QUEUED,
        waitingReason = null,
    )
}

/** Adult downloads are only listed while the adult space is unlocked (Task 4.4 gate). */
fun visibleDownloads(all: List<DownloadInfo>, adultUnlocked: Boolean): List<DownloadInfo> =
    if (adultUnlocked) all else all.filter { !it.record.adult }

enum class DownloadSort { NEWEST, NAME, SIZE }

fun sortDownloads(list: List<DownloadInfo>, sort: DownloadSort): List<DownloadInfo> = when (sort) {
    DownloadSort.NEWEST -> list.sortedByDescending { it.record.createdAt }
    DownloadSort.NAME -> list.sortedBy { it.record.title.lowercase() }
    DownloadSort.SIZE -> list.sortedByDescending { maxOf(it.record.totalBytes, it.record.downloadedBytes) }
}
