package spike

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** QuickJS never calls a composable: Compose only observes the retained tree. */
@Composable
fun RenderNode(tree: UiTree, id: Int, runtime: JsRuntime) {
    val n = tree.nodes[id] ?: return
    fun num(k: String) = n.props[k]?.jsonPrimitive?.doubleOrNull?.toFloat() ?: 0f
    fun str(k: String) = n.props[k]?.jsonPrimitive?.content
    val tag = str("testTag")?.let { Modifier.testTag(it) } ?: Modifier

    @Composable
    fun children() = n.children.forEach { key(it) { RenderNode(tree, it, runtime) } }

    when (n.type) {
        "Root", "Column" -> Column(
            tag.padding(num("padding").dp),
            verticalArrangement = Arrangement.spacedBy(num("gap").dp),
        ) { children() }
        "Row" -> Row(tag, horizontalArrangement = Arrangement.spacedBy(8.dp)) { children() }
        "Text" -> BasicText(
            tree.textOf(n),
            tag,
            style = when (str("variant")) {
                "title" -> TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold)
                else -> TextStyle(color = if (str("tone") == "danger") Color.Red else Color.Black)
            },
        )
        "Button" -> BasicText(
            tree.textOf(n),
            tag.clickable(enabled = n.events["press"] == true) { runtime.dispatchAsync(id, "press") }
                .border(1.dp, Color.Gray)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
        else -> BasicText("Unsupported: ${n.type}", style = TextStyle(color = Color.Red))
    }
}

fun main(args: Array<String>) {
    val surface = args.firstOrNull() ?: "counter"
    val tree = UiTree()
    val runtime = JsRuntime(tree, DemoServices)
    val root = runBlocking {
        runtime.start(File("../js/dist/bundle.js").readText())
        runtime.mount(surface)
    }
    application {
        Window(onCloseRequest = ::exitApplication, title = "QuickJS + Preact → Compose ($surface)") {
            RenderNode(tree, root, runtime)
        }
    }
}

object DemoServices : Services {
    override suspend fun call(service: String, method: String, argsJson: String): String {
        kotlinx.coroutines.delay(30)
        return when ("$service.$method") {
            "orders.list" -> """[{"id":1,"title":"Борщ","total":350},{"id":2,"title":"Пельмени","total":420},{"id":3,"title":"Компот","total":90}]"""
            else -> throw RpcFailure("NO_SUCH_METHOD")
        }
    }
}
