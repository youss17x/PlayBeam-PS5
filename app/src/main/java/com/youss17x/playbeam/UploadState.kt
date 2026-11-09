package com.youss17x.playbeam

import kotlinx.coroutines.flow.MutableStateFlow

/** Progress of the upload that is running now, observed by the UI and updated by UploadService. */
data class Progress(
    val running: Boolean = false,
    val jobLabel: String = "",
    val currentFile: String = "",
    val doneFiles: Int = 0,
    val totalFiles: Int = 0,
    val doneBytes: Long = 0,
    val totalBytes: Long = 0,
    val speedBytesPerSec: Long = 0,
    val message: String = "",
) {
    val fraction: Float
        get() = if (totalBytes <= 0) 0f else (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

object UploadState {
    val progress = MutableStateFlow(Progress())

    /** Uploads waiting after the current one. */
    val queue = MutableStateFlow<List<UploadJob>>(emptyList())

    /** Bumped every time a history entry is added, so the History screen reloads. */
    val historyVersion = MutableStateFlow(0)
}

fun formatBytes(b: Long): String {
    if (b < 1024) return "$b B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = b / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return String.format("%.1f %s", v, units[i])
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m ${s % 60}s"
        else -> "${s / 3600}h ${(s % 3600) / 60}m"
    }
}
