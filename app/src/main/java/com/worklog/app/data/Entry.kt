package com.worklog.app.data

import org.json.JSONObject

/**
 * 一条工作记录：一张照片 / 一段视频 + 自动抓取的时间地点 + 一句人工备注。
 */
data class Entry(
    val id: String,
    val time: Long,
    val kind: String,          // "photo" | "video"
    val path: String,          // 媒体文件的绝对路径
    val thumb: String? = null, // 视频封面（照片为 null，直接用原图）
    val note: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
    val place: String? = null
) {
    val isVideo: Boolean get() = kind == "video"

    /** 列表缩略图用的路径 */
    val previewPath: String get() = thumb ?: path

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("time", time)
        put("kind", kind)
        put("path", path)
        put("note", note)
        if (thumb != null) put("thumb", thumb)
        if (lat != null) put("lat", lat)
        if (lng != null) put("lng", lng)
        if (place != null) put("place", place)
    }

    companion object {
        fun from(o: JSONObject): Entry = Entry(
            id = o.getString("id"),
            time = o.getLong("time"),
            kind = o.optString("kind", "photo"),
            path = o.getString("path"),
            thumb = if (o.has("thumb")) o.getString("thumb") else null,
            note = o.optString("note", ""),
            lat = if (o.has("lat")) o.getDouble("lat") else null,
            lng = if (o.has("lng")) o.getDouble("lng") else null,
            place = if (o.has("place")) o.getString("place") else null
        )
    }
}
