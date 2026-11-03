package spike

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import java.io.File
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalTestApi::class)
class SpikeTest {
    private val bundle = File("../js/dist/bundle.js").readText()

    private fun boot(surface: String): Triple<UiTree, JsRuntime, Int> {
        val tree = UiTree()
        val rt = JsRuntime(tree, DemoServices)
        val t0 = System.nanoTime()
        val root = runBlocking { rt.start(bundle); rt.mount(surface) }
        println("[$surface] start+mount ${(System.nanoTime() - t0) / 1_000_000} ms, commits=${tree.commits}, mutations=${tree.mutationsApplied}")
        return Triple(tree, rt, root)
    }

    @Test
    fun composeClickGoesThroughPreactInQuickJsAndBack() = runComposeUiTest {
        val (tree, rt, root) = boot("counter")
        println(tree.dump(root))
        setContent { RenderNode(tree, root, rt) }

        onNodeWithText("Count: 0").assertIsDisplayed()
        val before = tree.mutationsApplied
        onNodeWithText("Increase").performClick()
        waitUntil(timeoutMillis = 2_000) { tree.textOf(tree.nodes.values.first { it.type == "Text" }) == "Count: 1" }
        onNodeWithText("Count: 1").assertIsDisplayed()
        println("[counter] click -> ${tree.mutationsApplied - before} mutation(s): only the changed text node")
        assertEquals(1, tree.mutationsApplied - before)
        assertTrue(rt.errors.isEmpty(), rt.errors.toString())
    }

    @Test
    fun rpcTimersKeyedMovesAndARunawayHandler() = runComposeUiTest {
        val (tree, rt, root) = boot("orders")
        assertTrue("Loading…" in tree.dump(root), tree.dump(root)) // first commit, before the RPC answers
        setContent { RenderNode(tree, root, rt) }

        runCatching { waitUntil(timeoutMillis = 2_000) { tree.nodes.values.any { it.type == "#text" && it.text == "Борщ" } } }
            .onFailure { println("errors=${rt.errors} log=${rt.log}\n" + tree.dump(root)); throw it }
        onNodeWithText("Борщ").assertIsDisplayed()
        // useEffect + setTimeout: ticks come from Kotlin-owned timers and stop at 4
        runCatching { waitUntil(timeoutMillis = 3_000) { tree.nodes.values.any { it.type == "#text" && it.text == "4" } } }
            .onFailure { println("errors=${rt.errors} log=${rt.log}\n" + tree.dump(root)); throw it }
        println(tree.dump(root))

        fun rows() = tree.nodes.values.filter { it.type == "Row" }
            .sortedBy { r -> tree.nodes[r.parent!!]!!.children.indexOf(r.id) }
            .map { it.id to it.props["testTag"].toString() }
        val rowsBefore = rows()
        val m0 = tree.mutationsApplied
        onNodeWithText("Reverse").performClick()
        waitUntil(timeoutMillis = 2_000) { rows() == rowsBefore.reversed() }
        println("[orders] reverse: rows ${rowsBefore.map { it.first }} -> ${rows().map { it.first }} (same node ids = moved, not recreated), ${tree.mutationsApplied - m0} mutations")

        onNodeWithText("Drop first").performClick()
        waitUntil(timeoutMillis = 2_000) { rows().size == 2 }

        // A handler that never returns: the interrupt handler stops it, the runtime survives.
        val t = measureNanoTime { runBlocking { rt.dispatch(tree.nodes.values.first { it.type == "Button" && tree.textOf(it) == "Hang" }.id, "press") } }
        println("[orders] runaway handler interrupted after ${t / 1_000_000} ms: ${rt.errors}")
        assertTrue(rt.errors.any { "Interrupt" in it })
        val beforeReverse = rows()
        assertEquals(2, beforeReverse.size)
        onNodeWithText("Reverse").performClick()
        waitUntil(timeoutMillis = 2_000) { rows() == beforeReverse.reversed() }
        assertEquals(null, tree.lastError)
        println("[orders] after interrupt the runtime still renders; protocol errors: ${tree.lastError}")
    }

    /**
     * Without Preact: JS returns the whole screen as plain data on every update, Kotlin parses it,
     * and Compose (keys + equality) does the diffing. Is a full tree cheap enough for an admin list?
     */
    @Test
    fun fullTreeRerenderCost() = runBlocking {
        val qjs = com.dokar.quickjs.QuickJs.create(kotlinx.coroutines.Dispatchers.Default)
        qjs.evaluate<Any?>(
            """
            globalThis.items = Array.from({ length: 500 }, (_, i) => ({
              id: i, name: "Блюдо " + i, group: "Группа " + (i % 12), balance: (i % 7) - 1, enabled: i % 5 != 0 }));
            globalThis.view = (q) => ({ type: "Column", children: [
              { type: "Search", value: q, onChange: 1 },
              { type: "List", children: items.filter(d => d.name.includes(q)).map(d => ({
                type: "Row", key: d.id, title: d.name, subtitle: d.group,
                value: d.balance < 0 ? "∞" : String(d.balance), tone: d.balance == 0 ? "danger" : null,
                enabled: d.enabled, onPress: 1000 + d.id })) } ] });
            """.trimIndent(),
        )
        repeat(50) { kotlinx.serialization.json.Json.parseToJsonElement(qjs.evaluate<String>("JSON.stringify(view(''))")) }
        val n = 200
        var bytes = 0
        val ns = measureNanoTime {
            repeat(n) {
                val json = qjs.evaluate<String>("JSON.stringify(view(''))")
                bytes = json.length
                kotlinx.serialization.json.Json.parseToJsonElement(json)
            }
        }
        println("[full tree] 500 rows: ${bytes / 1024} KB JSON, view+stringify+parse ${"%.2f".format(ns / n / 1e6)} ms per update")
        qjs.close()
    }

    @Test
    fun roundTripCost() {
        val (tree, rt, root) = boot("counter")
        val button = tree.nodes.values.first { it.type == "Button" }.id
        runBlocking {
            repeat(200) { rt.dispatch(button, "press") } // warm-up
            val n = 2_000
            val ns = measureNanoTime { repeat(n) { rt.dispatch(button, "press") } }
            println("[perf] event -> Preact -> commit -> tree: ${ns / n / 1000} µs per click (n=$n), JS heap ${rt.memoryUsedBytes() / 1024} KB")
        }
        assertEquals("Count: 2200", tree.textOf(tree.nodes.values.first { it.type == "Text" }))
    }
}
