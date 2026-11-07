package dev.antite.browser.data

import android.content.Context
import dev.antite.engine.Engine
import dev.antite.engine.PermissionDefault
import dev.antite.engine.PermissionValue
import dev.antite.engine.SitePermission

class SitePerms(context: Context, private val prefs: Prefs, private val engine: Engine) {
    class Cat(
        val key: String,
        val title: String,
        val engineKinds: List<SitePermission>,
        val labels: List<String>,
        val values: List<Int>,
        val def: Int,
        val request: String?,
        val note: String?,
    ) {
        val perSite: Boolean get() = note == null
    }

    companion object {
        const val ASK = 0
        const val ALLOW = 1
        const val BLOCK = 2
        const val MUTED = 3

        private val ASK_BLOCK = listOf("Спрашивать", "Блокировать") to listOf(ASK, BLOCK)
        private val ALLOW_BLOCK = listOf("Разрешать", "Блокировать") to listOf(ALLOW, BLOCK)
        private val ALLOW_ASK_BLOCK = listOf("Разрешать", "Спрашивать", "Блокировать") to listOf(ALLOW, ASK, BLOCK)

        private fun cat(
            key: String, title: String, kinds: List<SitePermission>, opts: Pair<List<String>, List<Int>>,
            def: Int, request: String?, note: String? = null,
        ) = Cat(key, title, kinds, opts.first, opts.second, def, request, note)

        val LOCATION = cat("location", "Местоположение", listOf(SitePermission.LOCATION), ASK_BLOCK, 0, "ваше местоположение")
        val CAMERA = cat("camera", "Камера", emptyList(), ASK_BLOCK, 0, "доступ к камере")
        val MIC = cat("mic", "Микрофон", emptyList(), ASK_BLOCK, 0, "доступ к микрофону")
        val NOTIFICATIONS = cat("notifications", "Уведомления", listOf(SitePermission.NOTIFICATIONS), ASK_BLOCK, 0,
            "разрешение показывать уведомления")
        val JS = cat("js", "JavaScript", emptyList(), ALLOW_BLOCK, 0, null)
        val POPUPS = cat("popups", "Всплывающие окна", emptyList(), ALLOW_BLOCK, 1, null)
        val AUTOPLAY = Cat("autoplay", "Автовоспроизведение",
            listOf(SitePermission.AUTOPLAY_AUDIBLE, SitePermission.AUTOPLAY_INAUDIBLE),
            listOf("Разрешать", "Только без звука", "Блокировать"), listOf(ALLOW, MUTED, BLOCK), 1, null, null)
        val DRM = cat("drm", "Защищённый контент", listOf(SitePermission.DRM), ALLOW_ASK_BLOCK, 0,
            "воспроизведение защищённого контента (DRM)")
        val STORAGE = cat("storage", "Постоянное хранилище", listOf(SitePermission.PERSISTENT_STORAGE), ALLOW_ASK_BLOCK, 0,
            "постоянное хранилище данных")
        val LNA = cat("lna", "Доступ к локальной сети", listOf(SitePermission.LOCAL_NETWORK, SitePermission.LOCAL_DEVICE),
            ASK_BLOCK, 0, "доступ к устройствам в локальной сети")
        val XR = cat("xr", "Виртуальная реальность", listOf(SitePermission.XR), ASK_BLOCK, 0, "доступ к VR-устройствам")
        val SENSORS = cat("sensors", "Датчики движения", emptyList(), ALLOW_BLOCK, 0, null,
            "Движок применяет это сразу ко всем сайтам, исключений нет")

        val ALL = listOf(LOCATION, CAMERA, MIC, NOTIFICATIONS, JS, POPUPS, AUTOPLAY, DRM, STORAGE, LNA, XR, SENSORS)

        fun forEngine(p: SitePermission): Cat? = when (p) {
            SitePermission.LOCATION -> LOCATION
            SitePermission.NOTIFICATIONS -> NOTIFICATIONS
            SitePermission.PERSISTENT_STORAGE -> STORAGE
            SitePermission.XR -> XR
            SitePermission.AUTOPLAY_AUDIBLE, SitePermission.AUTOPLAY_INAUDIBLE -> AUTOPLAY
            SitePermission.DRM -> DRM
            SitePermission.LOCAL_NETWORK, SitePermission.LOCAL_DEVICE -> LNA
            SitePermission.TRACKING_EXCEPTION, SitePermission.STORAGE_ACCESS -> null
        }
    }

    private val sites = context.getSharedPreferences("siteperms", Context.MODE_PRIVATE)

    fun wipeLegacyOnce() {
        if (prefs.sp.getBoolean("permsWiped", false)) return
        engine.permissions.list { list ->
            list.filter { it.permission != SitePermission.TRACKING_EXCEPTION }.forEach(engine.permissions::remove)
            prefs.edit { putBoolean("permsWiped", true) }
        }
    }

    fun defaultIndex(c: Cat): Int = prefs.int("cat_${c.key}", c.def).coerceIn(0, c.values.lastIndex)

    fun defaultValue(c: Cat): Int = c.values[defaultIndex(c)]

    fun setDefaultIndex(c: Cat, index: Int) = prefs.edit { putInt("cat_${c.key}", index) }

    fun engineDefaults(): Map<SitePermission, PermissionDefault> = buildMap {
        for (c in ALL) {
            if (c == AUTOPLAY) continue
            val d = when (defaultValue(c)) {
                ALLOW -> PermissionDefault.ALLOW
                BLOCK -> PermissionDefault.BLOCK
                else -> PermissionDefault.ASK
            }
            c.engineKinds.forEach { put(it, d) }
        }
    }

    fun exception(c: Cat, host: String?): Int {
        prefs.revision.intValue
        if (host.isNullOrEmpty()) return -1
        return sites.getInt("${c.key}|$host", -1)
    }

    fun decide(c: Cat, host: String?): Int = exception(c, host).takeIf { it >= 0 } ?: defaultValue(c)

    fun setException(c: Cat, host: String, value: Int) {
        if (host.isEmpty()) return
        sites.edit().apply {
            if (value < 0) remove("${c.key}|$host") else putInt("${c.key}|$host", value)
        }.apply()
        prefs.revision.intValue++
        if (c.engineKinds.isEmpty() || c == AUTOPLAY) return
        val v = when (value) {
            ALLOW -> PermissionValue.ALLOW
            BLOCK -> PermissionValue.BLOCK
            else -> PermissionValue.ASK
        }
        engine.permissions.list { list ->
            list.filter { !it.privateMode && it.permission in c.engineKinds && Vault.hostOf(it.origin) == host }
                .forEach { engine.permissions.set(it.origin, it.permission, v) }
            if (v == PermissionValue.ASK) return@list
            for (kind in c.engineKinds) {
                engine.permissions.set("https://$host", kind, v)
                engine.permissions.set("http://$host", kind, v)
            }
        }
    }

    fun exceptions(c: Cat): Map<String, Int> {
        prefs.revision.intValue
        val prefix = "${c.key}|"
        return sites.all.entries.filter { it.key.startsWith(prefix) && it.value is Int }
            .associate { it.key.removePrefix(prefix) to it.value as Int }.toSortedMap()
    }

    fun hosts(): List<String> {
        prefs.revision.intValue
        return sites.all.keys.mapNotNull { k -> k.indexOf('|').takeIf { it > 0 }?.let { k.substring(it + 1) } }
            .distinct().sorted()
    }

    fun forgetSite(host: String) {
        sites.edit().apply { ALL.forEach { remove("${it.key}|$host") } }.apply()
        prefs.revision.intValue++
        engine.permissions.list { list ->
            list.filter { Vault.hostOf(it.origin) == host }.forEach(engine.permissions::remove)
        }
    }

    fun autoplayAllowed(host: String, audible: Boolean): Boolean = when (decide(AUTOPLAY, host)) {
        ALLOW -> true
        BLOCK -> false
        else -> !audible
    }

    fun label(c: Cat, value: Int): String {
        val i = c.values.indexOf(value)
        return if (i >= 0) c.labels[i] else when (value) {
            ALLOW -> "Разрешать"
            BLOCK -> "Блокировать"
            else -> "Спрашивать"
        }
    }
}
