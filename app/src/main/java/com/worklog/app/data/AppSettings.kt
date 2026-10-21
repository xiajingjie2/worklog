package com.worklog.app.data

import android.content.Context

/**
 * 极简设置存储：导出模式等。
 * 用 SharedPreferences，卸载即清空（导出文件本身保存在公共下载目录，不受影响）。
 */
class AppSettings(ctx: Context) {

    private val prefs = ctx.getSharedPreferences("worklog", Context.MODE_PRIVATE)

    var exportMode: String
        get() = prefs.getString(KEY_MODE, MODE_LOG) ?: MODE_LOG
        set(value) {
            prefs.edit().putString(KEY_MODE, value).apply()
        }

    companion object {
        private const val KEY_MODE = "export_mode"

        const val MODE_LOG = "log"
        const val MODE_BRIEF = "brief"

        fun label(mode: String): String = when (mode) {
            MODE_BRIEF -> "简报"
            else -> "工作日志摘要"
        }
    }
}
