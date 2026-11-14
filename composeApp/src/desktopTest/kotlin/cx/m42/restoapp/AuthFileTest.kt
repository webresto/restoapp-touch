package cx.m42.restoapp

import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import cx.m42.restoapp.protocol.ProtocolJson
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.ChaCha20ParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * The file of sign-ins ([AuthFile]): each piece of its cipher against the JDK's own, the file against a wrong
 * password and a changed byte, and the sign-ins of one app carried to another through it.
 */
class AuthFileTest {
    private lateinit var dir: File

    @BeforeTest
    fun isolateStorage() {
        dir = Files.createTempDirectory("restoapp-auth-test").toFile()
    }

    @Test
    fun sha256AndHmacAreTheJdks() {
        val random = Random(7)
        for (size in listOf(0, 1, 55, 56, 63, 64, 65, 119, 120, 1000)) {
            val bytes = random.nextBytes(size)
            assertContentEquals(MessageDigest.getInstance("SHA-256").digest(bytes), Sha256().update(bytes).digest(), "sha256 of $size")
            // In parts, as HMAC feeds it.
            val split = size / 3
            assertContentEquals(MessageDigest.getInstance("SHA-256").digest(bytes), Sha256().update(bytes, 0, split).update(bytes, split).digest())
            for (keySize in listOf(0, 16, 64, 100)) {
                val key = random.nextBytes(keySize).takeIf { it.isNotEmpty() } ?: byteArrayOf(0)
                val jdk = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(bytes)
                assertContentEquals(jdk, hmacSha256(key, bytes), "hmac of $size under $keySize")
            }
        }
    }

    @Test
    fun pbkdf2IsTheJdks() {
        for ((password, iterations, size) in listOf(Triple("password", 1, 32), Triple("пароль файла", 4096, 32), Triple("x", 3, 70))) {
            val salt = Random(iterations).nextBytes(16)
            val jdk = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, size * 8)).encoded
            assertContentEquals(jdk, pbkdf2Sha256(password.encodeToByteArray(), salt, iterations, size), "$password × $iterations")
        }
        // RFC 7914's vector, independent of the JDK.
        assertEquals(
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            pbkdf2Sha256("password".encodeToByteArray(), "salt".encodeToByteArray(), 1, 32).toHex(),
        )
    }

    @Test
    fun chacha20IsTheJdks() {
        val random = Random(11)
        for (size in listOf(0, 1, 63, 64, 65, 500)) {
            val key = random.nextBytes(32)
            val nonce = random.nextBytes(12)
            val plain = random.nextBytes(size)
            val jdk = Cipher.getInstance("ChaCha20").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), ChaCha20ParameterSpec(nonce, 1))
            }.doFinal(plain)
            assertContentEquals(jdk, chacha20(key, nonce, 1, plain), "chacha20 of $size")
            assertContentEquals(plain, chacha20(key, nonce, 1, chacha20(key, nonce, 1, plain)))
        }
    }

    private val bundle = AuthBundle(
        listOf(
            AuthEntry("cafe", "Кафе", "https://admin.cafe.ru", login = "admin", token = "rmt_secret"),
            AuthEntry("cloud", "Resto Cloud · user2", "https://dev.resto.cloud", cloud = true, login = "user2", token = "rcm_cloud"),
            AuthEntry("cloud--abcdef12", "Пиццерия", "https://pizza.dev.resto.cloud", parent = "cloud", cloudId = "abcdef12-0000", login = "admin", token = "rmt_pizza"),
        ),
    )

    @Test
    fun aFileOpensWithItsPasswordOnly() {
        val sealed = AuthFile.seal(bundle, "correct horse", iterations = 100_000)
        val text = sealed.decodeToString()
        // Nothing of the tokens or the addresses is readable in it.
        assertFalse("rcm_cloud" in text || "admin.cafe.ru" in text || "user2" in text)
        assertEquals(bundle, AuthFile.open(sealed, "correct horse"))

        assertEquals(AuthFileException.Reason.WrongPassword, assertFailsWith<AuthFileException> { AuthFile.open(sealed, "correct horsE") }.reason)

        // A field changed — the iterations, the data — is caught as a wrong password would be.
        val file = Json.decodeFromString(SealedAuthFile.serializer(), text)
        val data = java.util.Base64.getDecoder().decode(file.data).also { it[3] = (it[3].toInt() xor 1).toByte() }
        val changed = file.copy(data = java.util.Base64.getEncoder().encodeToString(data))
        assertEquals(
            AuthFileException.Reason.WrongPassword,
            assertFailsWith<AuthFileException> { AuthFile.open(Json.encodeToString(SealedAuthFile.serializer(), changed).encodeToByteArray(), "correct horse") }.reason,
        )
        val weaker = file.copy(iterations = 100_001)
        assertEquals(
            AuthFileException.Reason.WrongPassword,
            assertFailsWith<AuthFileException> { AuthFile.open(Json.encodeToString(SealedAuthFile.serializer(), weaker).encodeToByteArray(), "correct horse") }.reason,
        )
        val tooWeak = file.copy(iterations = 1)
        assertEquals(
            AuthFileException.Reason.Unsupported,
            assertFailsWith<AuthFileException> { AuthFile.open(Json.encodeToString(SealedAuthFile.serializer(), tooWeak).encodeToByteArray(), "correct horse") }.reason,
        )
        assertEquals(AuthFileException.Reason.NotAuthFile, assertFailsWith<AuthFileException> { AuthFile.open("{\"servers\":[]}".encodeToByteArray(), "x") }.reason)
        assertEquals(AuthFileException.Reason.NotAuthFile, assertFailsWith<AuthFileException> { AuthFile.open(byteArrayOf(1, 2, 3), "x") }.reason)
    }

    // ------------------------------------------------------------------ from one app to another

    private val listJson = Json { prettyPrint = true }

    /** An app whose list is [servers] on top of the demo it starts on, signed in by [sessions] (id → login to token). */
    private fun app(name: String, servers: List<ServerProfile>, sessions: Map<String, Pair<String, String>>): Pair<RestoAppHost, DesktopServerStore> {
        val store = DesktopServerStore(File(dir, name))
        store.save(listJson.encodeToString(ServerList.serializer(), ServerList(DemoServers.servers + servers, current = "demo-restaurant")))
        sessions.forEach { (id, session) ->
            store.write(id, ServerSession.SESSION, ProtocolJson.encodeToString(StoredSession.serializer(), StoredSession(session.second, session.first)), private = true)
        }
        val host = RestoAppHost(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            store = store,
            debug = true,
            clock = { 1_700_000_000_000 },
        )
        return host to store
    }

    private fun DesktopServerStore.session(id: String): StoredSession? =
        read(id, ServerSession.SESSION)?.let { ProtocolJson.decodeFromString(StoredSession.serializer(), it) }

    // Nothing listens there: the cloud's sync fails at once and changes nothing.
    private val cloud = "http://127.0.0.1:9"

    @Test
    fun theSignInsOfOneAppGoToAnotherThroughTheFile() = runBlocking {
        val (from, _) = app(
            "from",
            listOf(
                ServerProfile("cafe", "Кафе", "http://127.0.0.1:9/cafe"),
                ServerProfile("bar", "Бар", "http://127.0.0.1:9/bar"),
                ServerProfile("cloud", "Resto Cloud · user2", cloud, cloud = true),
                ServerProfile("cloud--abcdef12", "Пиццерия", "http://127.0.0.1:9/pizza", parent = "cloud", cloudId = "abcdef12-0000", status = "online"),
            ),
            mapOf(
                "cafe" to ("admin" to "rmt_cafe"),
                "cloud" to ("user2" to "rcm_cloud"),
                "cloud--abcdef12" to ("admin" to "rmt_pizza"),
            ),
        )
        assertEquals(listOf("cafe", "bar", "cloud", "cloud--abcdef12"), from.transferable().map { it.id })
        val file = from.exportAuth("пароль файла")

        // The other app has the bar already, signed out, and a server of its own at the cafe's id.
        val (to, store) = app(
            "to",
            listOf(
                ServerProfile("cafe", "Склад", "http://127.0.0.1:9/stock"),
                ServerProfile("bar", "Бар", "http://127.0.0.1:9/bar"),
            ),
            mapOf("cafe" to ("stock" to "rmt_stock")),
        )
        assertFailsWith<AuthFileException> { to.importAuth(file, "не тот") }

        val report = to.importAuth(file, "пароль файла")
        assertEquals(RestoAppHost.ImportReport(added = 3, signedIn = 0, unchanged = 1), report)

        val servers = to.servers.value
        assertEquals(listOf("demo-restaurant", "demo-point", "cafe", "bar", "127-0-0-1-9", "127-0-0-1-9-2", "127-0-0-1-9-2--abcdef12"), servers.map { it.id })
        val cafe = servers.first { it.name == "Кафе" }
        assertEquals(StoredSession("rmt_cafe", "admin"), store.session(cafe.id))
        val account = servers.first { it.cloud }
        assertEquals(StoredSession("rcm_cloud", "user2"), store.session(account.id))
        val pizza = servers.first { it.cloudId == "abcdef12-0000" }
        assertEquals(account.id, pizza.parent)
        assertEquals(StoredSession("rmt_pizza", "admin"), store.session(pizza.id))
        // Its own server keeps its own sign-in; the bar, signed out on both, stays signed out.
        assertEquals(StoredSession("rmt_stock", "stock"), store.session("cafe"))
        assertNull(store.session("bar"))

        // The same file again adds nothing.
        assertEquals(RestoAppHost.ImportReport(added = 0, signedIn = 0, unchanged = 4), to.importAuth(file, "пароль файла"))
        assertEquals(servers, to.servers.value)
    }

    /** The file dialogs, as the person would answer them: the file saved is the file opened. */
    private class FakeFiles : AuthFiles {
        var saved: ByteArray? = null
        var name: String? = null

        override suspend fun save(suggestedName: String, bytes: ByteArray): Boolean {
            name = suggestedName
            saved = bytes
            return true
        }

        override suspend fun open(): ByteArray? = saved
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theManageScreenSavesTheFileAndAnotherAppLoadsIt() = runComposeUiTest {
        val files = FakeFiles()
        val (from, _) = app("from", listOf(ServerProfile("cafe", "Кафе", "http://127.0.0.1:9/cafe")), mapOf("cafe" to ("admin" to "rmt_cafe")))
        val (to, store) = app("to", emptyList(), emptyMap())
        from.files = files
        to.files = files
        val showing = mutableStateOf(from)
        setContent {
            val host = showing.value
            host.active.value.superizer.settings.chooseLanguage("ru")
            key(host) { host.manageScreen.Content(host.active.value.superizer, {}, {}) }
        }
        waitForIdle()

        onNodeWithTag("servers:export").performScrollTo().performClick()
        onNodeWithTag("servers:export-warning").assertIsDisplayed()
        onNodeWithTag("servers:export-password").performScrollTo().performTextInput("short")
        // Too short: Superizer's button marks nothing disabled, it just does nothing.
        onNodeWithTag("servers:export-save").performScrollTo().performClick()
        waitForIdle()
        assertNull(files.saved)
        onNodeWithTag("servers:export-password").performScrollTo().performTextInput("-but-now-long")
        onNodeWithTag("servers:export-repeat").performScrollTo().performTextInput("short-but-now-long")
        onNodeWithTag("servers:export-save").performScrollTo().performClick()
        waitUntil(timeoutMillis = 10_000L) { files.saved != null }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag("servers:transfer-done").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("restoapp-sign-ins.restoapp-auth", files.name)

        showing.value = to
        waitForIdle()
        onNodeWithTag("servers:import").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag("servers:import-password").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("servers:import-password").performScrollTo().performTextInput("wrong password")
        onNodeWithTag("servers:import-load").performScrollTo().performClick()
        waitUntil(timeoutMillis = 10_000L) { onAllNodesWithTag("servers:problem").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Неверный пароль, или файл изменён").assertIsDisplayed()

        onNodeWithTag("servers:import-password").performScrollTo().performTextClearance()
        onNodeWithTag("servers:import-password").performScrollTo().performTextInput("short-but-now-long")
        onNodeWithTag("servers:import-load").performScrollTo().performClick()
        waitUntil(timeoutMillis = 10_000L) { onAllNodesWithTag("servers:transfer-done").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Добавлено серверов: 1, вход восстановлен: 0, уже были: 0").assertIsDisplayed()
        val cafe = to.servers.value.first { it.name == "Кафе" }
        assertEquals(StoredSession("rmt_cafe", "admin"), store.session(cafe.id))
    }

    @Test
    fun aServerSignedOutHereTakesTheFilesSignIn() = runBlocking {
        val cafe = ServerProfile("cafe", "Кафе", "http://127.0.0.1:9/cafe")
        val (from, _) = app("from", listOf(cafe), mapOf("cafe" to ("admin" to "rmt_cafe")))
        val (to, store) = app("to", listOf(cafe), emptyMap())
        assertEquals(RestoAppHost.ImportReport(added = 0, signedIn = 1, unchanged = 0), to.importAuth(from.exportAuth("12345678"), "12345678"))
        assertEquals(StoredSession("rmt_cafe", "admin"), store.session("cafe"))
        assertTrue(to.servers.value.count { it.address == cafe.address } == 1)
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
