package com.worklog.app.data

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * 极简持久化：一个 JSON 索引文件 + media/ 目录放原始媒体。
 * 刻意不引 Room —— 这个数据量用不上数据库，少一个依赖就少一处构建风险。
 */
class EntryStore(private val ctx: Context) {

    private val indexFile = File(ctx.filesDir, "entries.json")
    private val lock = Any()

    fun mediaDir(): File = File(ctx.filesDir, "media").apply { if (!exists()) mkdirs() }

    fun load(): List<Entry> = synchronized(lock) { readLocked() }

    fun save(list: List<Entry>) = synchronized(lock) { writeLocked(list) }

    fun add(entry: Entry) = synchronized(lock) {
        writeLocked(readLocked() + entry)
    }

    fun update(entry: Entry) = synchronized(lock) {
        writeLocked(readLocked().map { if (it.id == entry.id) entry else it })
    }

    fun remove(id: String) = synchronized(lock) {
        writeLocked(readLocked().filterNot { it.id == id })
    }

    private fun readLocked(): List<Entry> {
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(indexFile.readText())
            (0 until arr.length()).mapNotNull { i ->
                runCatching { Entry.from(arr.getJSONObject(i)) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    private fun writeLocked(list: List<Entry>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        val tmp = File(ctx.filesDir, "entries.json.tmp")
        tmp.writeText(arr.toString())
        if (indexFile.exists()) indexFile.delete()
        tmp.renameTo(indexFile)
    }
}
