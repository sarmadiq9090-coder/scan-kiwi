package com.fastkiwi.scan.ui

import android.content.Context
import com.fastkiwi.scan.core.Filter
import com.fastkiwi.scan.core.PageSize
import com.fastkiwi.scan.export.Quality
import com.fastkiwi.scan.ocr.OcrLanguage

/** Small preference store (SharedPreferences, private to the app). */
class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var autoCapture: Boolean
        get() = p.getBoolean("auto_capture", true)
        set(v) = p.edit().putBoolean("auto_capture", v).apply()

    var defaultFilter: Filter
        get() = enumOr(p.getString("default_filter", null), Filter.ENHANCED)
        set(v) = p.edit().putString("default_filter", v.name).apply()

    var pageSize: PageSize
        get() = enumOr(p.getString("page_size", null), PageSize.A4)
        set(v) = p.edit().putString("page_size", v.name).apply()

    var quality: Quality
        get() = enumOr(p.getString("quality", null), Quality.BALANCED)
        set(v) = p.edit().putString("quality", v.name).apply()

    var ocr: Boolean
        get() = p.getBoolean("ocr", false)
        set(v) = p.edit().putBoolean("ocr", v).apply()

    var ocrLanguage: OcrLanguage
        get() = enumOr(p.getString("ocr_language", null), OcrLanguage.ARABIC_ENGLISH)
        set(v) = p.edit().putString("ocr_language", v.name).apply()

    companion object {
        inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
            name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: default

        /** Language override used on Android < 13 ("" = follow the system). */
        fun languageTag(ctx: Context): String =
            ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("language", "") ?: ""

        fun setLanguageTag(ctx: Context, tag: String) {
            ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("language", tag).commit()
        }
    }
}
