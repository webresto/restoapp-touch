@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package cx.m42.restoapp

/**
 * The servers in a browser: the list and each server's files in `localStorage`, under `restoapp:`. As on the
 * iPhone, [useStorageOf] does nothing — Superizer's prefs in a browser are one store for the whole origin.
 * A device token in `localStorage` is readable by any script of this origin: the web build is for trying the
 * app, not a device to keep signed in.
 */
public class WebServerStore : ServerStore {
    override fun load(): String? = get("restoapp:servers.json")

    override fun save(text: String) = put("restoapp:servers.json", text)

    override fun useStorageOf(serverId: String) = Unit

    override fun erase(serverId: String) = removePrefix("restoapp:servers/$serverId/")

    override fun read(serverId: String, name: String): String? = get("restoapp:servers/$serverId/$name")

    override fun write(serverId: String, name: String, text: String, private: Boolean) = put("restoapp:servers/$serverId/$name", text)

    override fun delete(serverId: String, name: String) = remove("restoapp:servers/$serverId/$name")
}

private fun get(key: String): String? = js("window.localStorage.getItem(key)")

private fun put(key: String, value: String): Unit = js("{ window.localStorage.setItem(key, value); }")

private fun remove(key: String): Unit = js("{ window.localStorage.removeItem(key); }")

private fun removePrefix(prefix: String): Unit = js(
    "{ const s = window.localStorage; Object.keys(s).filter(k => k.startsWith(prefix)).forEach(k => s.removeItem(k)); }",
)
