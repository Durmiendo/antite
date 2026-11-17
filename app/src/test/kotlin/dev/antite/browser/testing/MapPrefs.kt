package dev.antite.browser.testing

import android.content.SharedPreferences

// SharedPreferences over a map, for code that keeps its settings there.
class MapPrefs(val map: MutableMap<String, Any?> = HashMap()) : SharedPreferences {
    override fun getAll(): Map<String, *> = HashMap(map)
    override fun getString(key: String, def: String?) = map[key] as String? ?: def
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, def: Set<String>?) = map[key] as Set<String>? ?: def
    override fun getInt(key: String, def: Int) = map[key] as Int? ?: def
    override fun getLong(key: String, def: Long) = map[key] as Long? ?: def
    override fun getFloat(key: String, def: Float) = map[key] as Float? ?: def
    override fun getBoolean(key: String, def: Boolean) = map[key] as Boolean? ?: def
    override fun contains(key: String) = key in map
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val puts = HashMap<String, Any?>()
        private val removes = HashSet<String>()
        private var clear = false
        override fun putString(k: String, v: String?) = apply { puts[k] = v }
        override fun putStringSet(k: String, v: Set<String>?) = apply { puts[k] = v }
        override fun putInt(k: String, v: Int) = apply { puts[k] = v }
        override fun putLong(k: String, v: Long) = apply { puts[k] = v }
        override fun putFloat(k: String, v: Float) = apply { puts[k] = v }
        override fun putBoolean(k: String, v: Boolean) = apply { puts[k] = v }
        override fun remove(k: String) = apply { removes += k }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            if (clear) map.clear()
            removes.forEach(map::remove)
            map.putAll(puts)
            return true
        }
        override fun apply() {
            commit()
        }
    }
}
