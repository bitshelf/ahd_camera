package io.github.ahdcamera

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList

/** Android 13+ uses the platform locale API; older supported releases restore local configuration. */
internal object AppLanguage {
    private fun preferences(context: Context) = context.getSharedPreferences("app-language", 0)
    fun current(context: Context): String = if (Build.VERSION.SDK_INT >= 33) {
        context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
    } else preferences(context).getString("tag", "").orEmpty()

    fun select(activity: Activity, tag: String) {
        preferences(activity).edit().putString("tag", tag).apply()
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
        } else {
            restoreLegacy(activity.applicationContext)
            activity.recreate()
        }
    }

    fun wrap(context: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return context
        val tag = preferences(context).getString("tag", "").orEmpty()
        val configuration = Configuration(context.resources.configuration).apply {
            setLocales(if (tag.isEmpty()) Resources.getSystem().configuration.locales else LocaleList.forLanguageTags(tag))
        }
        return context.createConfigurationContext(configuration)
    }

    @Suppress("DEPRECATION")
    fun restoreLegacy(context: Context) {
        if (Build.VERSION.SDK_INT < 33) {
            val localized = wrap(context)
            context.resources.updateConfiguration(localized.resources.configuration, context.resources.displayMetrics)
        }
    }
}
