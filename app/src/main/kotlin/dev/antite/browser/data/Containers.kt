package dev.antite.browser.data

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.antite.browser.lang.catching
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class TabContainer(val id: ContainerId, name: String, color: Int) {
    var name by mutableStateOf(name)
    var color by mutableStateOf(color)
}

// Containers keep cookies and site data apart: tabs of one container share them only with each
// other. The id is what the engine separates by, so it never changes.
class Containers(private val prefs: Prefs) {
    companion object {
        const val PREF = "tabContainers"
    }

    val list = mutableStateListOf<TabContainer>()

    init {
        catching {
            val arr = JSONArray(prefs.string(PREF, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = ContainerId.parse(o.getString("id")) ?: continue
                list += TabContainer(id, o.optString("name"), o.optInt("color", Categories.COLORS[0]))
            }
        }.onFailure { Log.w("Antite", "containers unreadable", it) }
    }

    fun byId(id: ContainerId?) = list.firstOrNull { it.id == id }

    fun save() {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id.value).put("name", it.name).put("color", it.color)) }
        prefs.edit { putString(PREF, arr.toString()) }
    }

    fun add(name: String): TabContainer {
        val c = TabContainer(ContainerId(UUID.randomUUID().toString()), name, Categories.COLORS[list.size % Categories.COLORS.size])
        list += c
        save()
        return c
    }

    fun delete(c: TabContainer) {
        list.remove(c)
        save()
    }
}
