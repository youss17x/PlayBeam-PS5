package com.youss17x.playbeam

import kotlinx.coroutines.flow.MutableStateFlow

/** Shared upload progress, observed by the UI and updated by UploadService. */
data class Progress(
    val running: Boolean = false,
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
}

fun formatBytes(b: Long): String {
    if (b < 1024) return "$b B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = b / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return String.format("%.1f %s", v, units[i])
}
