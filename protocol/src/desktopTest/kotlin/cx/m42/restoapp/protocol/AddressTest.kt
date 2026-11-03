package cx.m42.restoapp.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AddressTest {
    @Test
    fun whatAPersonTypesBecomesTheServersBase() {
        assertEquals("https://admin.cafe.ru", normalizeAddress("admin.cafe.ru"))
        assertEquals("https://admin.cafe.ru", normalizeAddress("  https://admin.cafe.ru/ "))
        assertEquals("https://admin.cafe.ru", normalizeAddress("https://admin.cafe.ru/api/mobile"))
        assertEquals("https://cafe.ru/resto", normalizeAddress("https://cafe.ru/resto/"))
        assertEquals("http://127.0.0.1:1337", normalizeAddress("http://127.0.0.1:1337"))
        assertNull(normalizeAddress(""))
        assertNull(normalizeAddress("ftp://cafe.ru"))
        assertNull(normalizeAddress("https://"))
    }
}
