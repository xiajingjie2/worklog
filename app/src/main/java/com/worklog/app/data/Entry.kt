package com.worklog.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条工作记录：最多两张照片（或一段视频）+ 自动抓取的时间地点 + 一句人工备注。
 */
data class Entry(
    val id: String,
    val time: Long,
    val kind: String,           // "photo" | "video"
    val paths: List<String> = emptyList(),
    val thumb: String? = null,  // 视频封面
    val note: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
    val place: String? = null
) {
    val isVideo: Boolean get() = kind == "video"

    val primaryPath: String get() = paths.firstOrNull().orEmpty()

    /** 列表缩略图用的路径 */
    val previewPath: String get() = thumb ?: primaryPath

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("time", time)
        put("kind", kind)
        put("paths", JSONArray(paths))
        put("note", note)
        if (thumb != null) put("thumb", thumb)
        if (lat != null) put("lat", lat)
        if (lng != null) put("lng", lng)
        if (place != null) put("place", place)
    }

    companion object {
        fun from(o: JSONObject): Entry {
            val list = mutableListOf<String>()
            val arr = o.optJSONArray("paths")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val p = arr.optString(i, "")
                    if (p.isNotBlank()) list.add(p)
                }
            }
            // 兼容旧版本：只有单个 path 字段
            if (list.isEmpty()) {
                val single = o.optString("path", "")
                if (single.isNotBlank()) list.add(single)
            }
            return Entry(
                id = o.getString("id"),
                time = o.getLong("time"),
                kind = o.optString("kind", "photo"),
                paths = list,
                thumb = if (o.has("thumb")) o.getString("thumb") else null,
                note = o.optString("note", ""),
                lat = if (o.has("lat")) o.getDouble("lat") else null,
                lng = if (o.has("lng")) o.getDouble("lng") else null,
                place = if (o.has("place")) o.getString("place") else null
            )
        }
    }
}
