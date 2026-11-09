package com.youss17x.playbeam

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** A saved PS5. */
data class Console(val name: String, val host: String, val port: Int)

/** One upload waiting in (or taken from) the queue. */
data class UploadJob(
    val id: Long,
    val label: String,
    val host: String,
    val port: Int,
    val remote: String,
    val isTree: Boolean,
    val resume: Boolean,
    val connections: Int,
    val extractZips: Boolean,
    val uris: List<Uri>,
)

data class HistoryEntry(
    val time: Long,
    val label: String,
    val destination: String,
    val ok: Boolean,
    val message: String,
    val bytes: Long,
    val files: Int,
    val durationMs: Long,
)

data class RecentPayload(val name: String, val uri: String)

/** Small JSON lists kept in SharedPreferences. */
object Store {
    private const val MAX_HISTORY = 50
    private const val MAX_PAYLOADS = 8

    fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun <T> read(ctx: Context, key: String, f: (JSONObject) -> T): List<T> = runCatching {
        val a = JSONArray(prefs(ctx).getString(key, "[]"))
        (0 until a.length()).map { f(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun write(ctx: Context, key: String, items: List<JSONObject>) {
        prefs(ctx).edit().putString(key, JSONArray(items).toString()).apply()
    }

    // ---- consoles ----
    fun consoles(ctx: Context): List<Console> =
        read(ctx, "consoles") { Console(it.getString("name"), it.getString("host"), it.getInt("port")) }

    fun saveConsoles(ctx: Context, list: List<Console>) = write(ctx, "consoles",
        list.map { JSONObject().put("name", it.name).put("host", it.host).put("port", it.port) })

    // ---- history ----
    fun history(ctx: Context): List<HistoryEntry> = read(ctx, "history") {
        HistoryEntry(
            it.getLong("time"), it.getString("label"), it.getString("dest"), it.getBoolean("ok"),
            it.getString("msg"), it.getLong("bytes"), it.getInt("files"), it.getLong("ms"),
        )
    }

    @Synchronized
    fun addHistory(ctx: Context, e: HistoryEntry) {
        val list = (listOf(e) + history(ctx)).take(MAX_HISTORY)
        write(ctx, "history", list.map {
            JSONObject().put("time", it.time).put("label", it.label).put("dest", it.destination)
                .put("ok", it.ok).put("msg", it.message).put("bytes", it.bytes)
                .put("files", it.files).put("ms", it.durationMs)
        })
    }

    fun clearHistory(ctx: Context) = write(ctx, "history", emptyList())

    // ---- recent payloads ----
    fun payloads(ctx: Context): List<RecentPayload> =
        read(ctx, "payloads") { RecentPayload(it.getString("name"), it.getString("uri")) }

    fun savePayloads(ctx: Context, list: List<RecentPayload>) = write(ctx, "payloads",
        list.take(MAX_PAYLOADS).map { JSONObject().put("name", it.name).put("uri", it.uri) })
}
