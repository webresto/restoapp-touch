package spike

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ProtocolError(message: String) : Exception(message)

/** One host node. Every field is snapshot state, so Compose recomposes only what changed. */
class UiNode(val id: Int, val type: String) {
    val props = mutableStateMapOf<String, JsonElement>()
    val events = mutableStateMapOf<String, Boolean>()
    val children = mutableStateListOf<Int>()
    var text by mutableStateOf("")
    var parent: Int? by mutableStateOf(null)
}

/**
 * Kotlin owns the retained host tree; JS owns the component tree. A batch is applied inside a
 * mutable snapshot: either the whole batch becomes visible to Compose at once, or (on any protocol
 * error) none of it does.
 */
class UiTree {
    val nodes = mutableStateMapOf<Int, UiNode>()
    var commits by mutableStateOf(0)
        private set
    var lastError: String? by mutableStateOf(null)
        private set
    var mutationsApplied = 0L
        private set

    fun apply(batchJson: String) {
        val batch = Json.parseToJsonElement(batchJson).jsonObject
        val ops = batch["mutations"]!!.jsonArray
        try {
            Snapshot.withMutableSnapshot {
                for (raw in ops) applyOne(raw.jsonObject)
                commits++
            }
            mutationsApplied += ops.size
        } catch (e: ProtocolError) {
            lastError = "commit ${batch["commitId"]}: ${e.message}"
        }
    }

    private fun node(id: Int) = nodes[id] ?: throw ProtocolError("unknown node $id")

    private fun applyOne(m: Map<String, JsonElement>) {
        fun int(k: String) = m[k]!!.jsonPrimitive.int
        when (val op = m["op"]!!.jsonPrimitive.content) {
            "create" -> {
                val id = int("id")
                if (id in nodes) throw ProtocolError("node $id exists")
                nodes[id] = UiNode(id, m["type"]!!.jsonPrimitive.content)
            }
            "text" -> nodes.getOrPut(int("id")) { UiNode(int("id"), "#text") }.text = m["value"]!!.jsonPrimitive.content
            "set" -> {
                val n = node(int("id"))
                val v = m["value"] ?: JsonNull
                if (v is JsonNull) n.props.remove(m["name"]!!.jsonPrimitive.content) else n.props[m["name"]!!.jsonPrimitive.content] = v
            }
            "listen" -> node(int("id")).events[m["event"]!!.jsonPrimitive.content] = m["on"]!!.jsonPrimitive.boolean
            "insert" -> {
                val parent = node(int("parent"))
                val child = node(int("child"))
                child.parent?.let { nodes[it]?.children?.remove(child.id) }
                val before = m["before"]?.jsonPrimitive?.intOrNull
                val at = if (before == null) -1 else parent.children.indexOf(before)
                if (before != null && at < 0) throw ProtocolError("node $before is not a child of ${parent.id}")
                if (at < 0) parent.children.add(child.id) else parent.children.add(at, child.id)
                child.parent = parent.id
            }
            "remove" -> {
                val parent = node(int("parent"))
                val child = int("child")
                if (!parent.children.remove(child)) throw ProtocolError("node $child is not a child of ${parent.id}")
                drop(child)
            }
            else -> throw ProtocolError("unknown op $op")
        }
    }

    private fun drop(id: Int) {
        val n = nodes.remove(id) ?: return
        n.children.toList().forEach(::drop)
    }

    /** Text of a node's raw-text children, as RN does for <Text>. */
    fun textOf(n: UiNode): String =
        n.children.mapNotNull { nodes[it] }.filter { it.type == "#text" }.joinToString("") { it.text }

    /** A readable dump for tests. */
    fun dump(id: Int, indent: String = ""): String {
        val n = nodes[id] ?: return "$indent<missing $id>"
        if (n.type == "#text") return "$indent\"${n.text}\""
        val props = n.props.entries.sortedBy { it.key }.joinToString("") { " ${it.key}=${(it.value as? JsonPrimitive)?.content ?: it.value}" }
        val ev = n.events.filterValues { it }.keys.sorted().joinToString("") { " on:$it" }
        return buildString {
            append("$indent<${n.type}$props$ev>")
            n.children.forEach { append("\n").append(dump(it, "$indent  ")) }
        }
    }
}
