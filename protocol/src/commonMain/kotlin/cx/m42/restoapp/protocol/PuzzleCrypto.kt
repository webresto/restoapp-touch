package cx.m42.restoapp.protocol

/**
 * The crypto of tiny-encryptor v1 in plain Kotlin, for [KotlinPuzzleSolver]: SHA-256, HMAC, PBKDF2 for
 * one block, AES (encryption only, which is all GCM needs) and AES-GCM decryption with a 128-bit tag,
 * no additional data. Written for a few blocks per sign-in, not for speed; checked against the JDK in
 * the desktop tests.
 */
@OptIn(ExperimentalUnsignedTypes::class)
internal object PuzzleCrypto {

    // ------------------------------------------------------------------ SHA-256

    private val K: IntArray = uintArrayOf(
        0x428a2f98u, 0x71374491u, 0xb5c0fbcfu, 0xe9b5dba5u, 0x3956c25bu, 0x59f111f1u, 0x923f82a4u, 0xab1c5ed5u,
        0xd807aa98u, 0x12835b01u, 0x243185beu, 0x550c7dc3u, 0x72be5d74u, 0x80deb1feu, 0x9bdc06a7u, 0xc19bf174u,
        0xe49b69c1u, 0xefbe4786u, 0x0fc19dc6u, 0x240ca1ccu, 0x2de92c6fu, 0x4a7484aau, 0x5cb0a9dcu, 0x76f988dau,
        0x983e5152u, 0xa831c66du, 0xb00327c8u, 0xbf597fc7u, 0xc6e00bf3u, 0xd5a79147u, 0x06ca6351u, 0x14292967u,
        0x27b70a85u, 0x2e1b2138u, 0x4d2c6dfcu, 0x53380d13u, 0x650a7354u, 0x766a0abbu, 0x81c2c92eu, 0x92722c85u,
        0xa2bfe8a1u, 0xa81a664bu, 0xc24b8b70u, 0xc76c51a3u, 0xd192e819u, 0xd6990624u, 0xf40e3585u, 0x106aa070u,
        0x19a4c116u, 0x1e376c08u, 0x2748774cu, 0x34b0bcb5u, 0x391c0cb3u, 0x4ed8aa4au, 0x5b9cca4fu, 0x682e6ff3u,
        0x748f82eeu, 0x78a5636fu, 0x84c87814u, 0x8cc70208u, 0x90befffau, 0xa4506cebu, 0xbef9a3f7u, 0xc67178f2u,
    ).let { k -> IntArray(k.size) { k[it].toInt() } }

    fun sha256(message: ByteArray): ByteArray {
        val h = uintArrayOf(0x6a09e667u, 0xbb67ae85u, 0x3c6ef372u, 0xa54ff53au, 0x510e527fu, 0x9b05688cu, 0x1f83d9abu, 0x5be0cd19u)
            .let { v -> IntArray(v.size) { v[it].toInt() } }
        val bitLength = message.size.toLong() * 8
        val padded = ByteArray(((message.size + 9 + 63) / 64) * 64)
        message.copyInto(padded)
        padded[message.size] = 0x80.toByte()
        for (i in 0 until 8) padded[padded.size - 1 - i] = (bitLength ushr (8 * i)).toByte()
        val w = IntArray(64)
        for (block in 0 until padded.size / 64) {
            for (i in 0 until 16) {
                val o = block * 64 + i * 4
                w[i] = ((padded[o].toInt() and 0xFF) shl 24) or ((padded[o + 1].toInt() and 0xFF) shl 16) or
                    ((padded[o + 2].toInt() and 0xFF) shl 8) or (padded[o + 3].toInt() and 0xFF)
            }
            for (i in 16 until 64) {
                val s0 = w[i - 15].rotateRight(7) xor w[i - 15].rotateRight(18) xor (w[i - 15] ushr 3)
                val s1 = w[i - 2].rotateRight(17) xor w[i - 2].rotateRight(19) xor (w[i - 2] ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }
            var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
            var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
            for (i in 0 until 64) {
                val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
                val ch = (e and f) xor (e.inv() and g)
                val t1 = hh + s1 + ch + K[i] + w[i]
                val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val t2 = s0 + maj
                hh = g; g = f; f = e; e = d + t1
                d = c; c = b; b = a; a = t1 + t2
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d
            h[4] += e; h[5] += f; h[6] += g; h[7] += hh
        }
        val out = ByteArray(32)
        for (i in 0 until 8) for (k in 0 until 4) out[i * 4 + k] = (h[i] ushr (24 - 8 * k)).toByte()
        return out
    }

    /** HMAC-SHA256; a short key is padded with zeros on the right, as the JDK and WebCrypto do. */
    fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val block = ByteArray(64)
        (if (key.size > 64) sha256(key) else key).copyInto(block)
        val inner = ByteArray(64 + message.size)
        val outer = ByteArray(64 + 32)
        for (i in 0 until 64) {
            inner[i] = (block[i].toInt() xor 0x36).toByte()
            outer[i] = (block[i].toInt() xor 0x5c).toByte()
        }
        message.copyInto(inner, 64)
        sha256(inner).copyInto(outer, 64)
        return sha256(outer)
    }

    /** PBKDF2-HMAC-SHA256 for one 32-byte block, over a *byte* password. */
    fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, rounds: Int): ByteArray {
        var u = hmacSha256(password, salt + byteArrayOf(0, 0, 0, 1))
        val result = u.copyOf()
        repeat(rounds - 1) {
            u = hmacSha256(password, u)
            for (i in result.indices) result[i] = (result[i].toInt() xor u[i].toInt()).toByte()
        }
        return result
    }

    // ------------------------------------------------------------------ AES

    private val SBOX: IntArray = IntArray(256).also { box ->
        // The S-box from its definition: the inverse in GF(2^8) and the affine map (FIPS 197, 5.1.1).
        var p = 1
        var q = 1
        do {
            p = p xor ((p shl 1) and 0xFF) xor (if (p and 0x80 != 0) 0x1B else 0)
            q = q xor (q shl 1)
            q = q xor (q shl 2)
            q = q xor (q shl 4)
            q = q and 0xFF
            if (q and 0x80 != 0) q = q xor 0x09
            val x = q xor rotl8(q, 1) xor rotl8(q, 2) xor rotl8(q, 3) xor rotl8(q, 4)
            box[p] = (x xor 0x63) and 0xFF
        } while (p != 1)
        box[0] = 0x63
    }

    private fun rotl8(x: Int, shift: Int): Int = ((x shl shift) or (x ushr (8 - shift))) and 0xFF

    private fun xtime(x: Int): Int = ((x shl 1) xor (if (x and 0x80 != 0) 0x1B else 0)) and 0xFF

    /** The round keys of AES-128/192/256 as bytes, 16 per round. */
    fun aesExpandKey(key: ByteArray): ByteArray {
        val nk = key.size / 4
        require(key.size == 16 || key.size == 24 || key.size == 32) { "AES key of ${key.size} bytes" }
        val rounds = nk + 6
        val w = ByteArray(16 * (rounds + 1))
        key.copyInto(w)
        var rcon = 1
        val temp = ByteArray(4)
        for (i in nk until 4 * (rounds + 1)) {
            w.copyInto(temp, 0, (i - 1) * 4, i * 4)
            if (i % nk == 0) {
                val first = temp[0]
                temp[0] = (SBOX[temp[1].toInt() and 0xFF] xor rcon).toByte()
                temp[1] = SBOX[temp[2].toInt() and 0xFF].toByte()
                temp[2] = SBOX[temp[3].toInt() and 0xFF].toByte()
                temp[3] = SBOX[first.toInt() and 0xFF].toByte()
                rcon = xtime(rcon)
            } else if (nk > 6 && i % nk == 4) {
                for (k in 0 until 4) temp[k] = SBOX[temp[k].toInt() and 0xFF].toByte()
            }
            for (k in 0 until 4) w[i * 4 + k] = (w[(i - nk) * 4 + k].toInt() xor temp[k].toInt()).toByte()
        }
        return w
    }

    /** One block, FIPS 197 as written: SubBytes, ShiftRows, MixColumns, AddRoundKey. */
    fun aesEncryptBlock(roundKeys: ByteArray, input: ByteArray): ByteArray {
        val rounds = roundKeys.size / 16 - 1
        val s = IntArray(16) { (input[it].toInt() xor roundKeys[it].toInt()) and 0xFF }
        val t = IntArray(16)
        for (round in 1..rounds) {
            // SubBytes and ShiftRows: the state is column-major, byte r of column c at 4c + r.
            for (c in 0 until 4) for (r in 0 until 4) t[4 * c + r] = SBOX[s[4 * ((c + r) % 4) + r]]
            if (round != rounds) {
                for (c in 0 until 4) {
                    val a0 = t[4 * c]; val a1 = t[4 * c + 1]; val a2 = t[4 * c + 2]; val a3 = t[4 * c + 3]
                    val all = a0 xor a1 xor a2 xor a3
                    s[4 * c] = a0 xor all xor xtime(a0 xor a1)
                    s[4 * c + 1] = a1 xor all xor xtime(a1 xor a2)
                    s[4 * c + 2] = a2 xor all xor xtime(a2 xor a3)
                    s[4 * c + 3] = a3 xor all xor xtime(a3 xor a0)
                }
            } else {
                t.copyInto(s)
            }
            for (i in 0 until 16) s[i] = s[i] xor (roundKeys[round * 16 + i].toInt() and 0xFF)
        }
        return ByteArray(16) { s[it].toByte() }
    }

    // ------------------------------------------------------------------ GCM

    /** x·y in GF(2^128) of GCM (NIST SP 800-38D, 6.3), blocks as two big-endian longs. */
    private fun gfMultiply(xHi: Long, xLo: Long, yHi: Long, yLo: Long): LongArray {
        var zHi = 0L
        var zLo = 0L
        var vHi = yHi
        var vLo = yLo
        for (i in 0 until 128) {
            val bit = if (i < 64) (xHi ushr (63 - i)) and 1L else (xLo ushr (127 - i)) and 1L
            if (bit != 0L) {
                zHi = zHi xor vHi
                zLo = zLo xor vLo
            }
            val lsb = vLo and 1L
            vLo = (vLo ushr 1) or (vHi shl 63)
            vHi = vHi ushr 1
            if (lsb != 0L) vHi = vHi xor (0xE1L shl 56)
        }
        return longArrayOf(zHi, zLo)
    }

    private fun longAt(bytes: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return v
    }

    /** GHASH over [data] zero-padded to blocks, then the block of the lengths in bits (no AAD here when [aadBits] is 0). */
    private fun ghash(hHi: Long, hLo: Long, data: ByteArray, aadBits: Long, dataBits: Long): ByteArray {
        var yHi = 0L
        var yLo = 0L
        val blocks = (data.size + 15) / 16
        val block = ByteArray(16)
        for (b in 0 until blocks) {
            block.fill(0)
            data.copyInto(block, 0, b * 16, minOf(data.size, b * 16 + 16))
            val z = gfMultiply(yHi xor longAt(block, 0), yLo xor longAt(block, 8), hHi, hLo)
            yHi = z[0]
            yLo = z[1]
        }
        val z = gfMultiply(yHi xor aadBits, yLo xor dataBits, hHi, hLo)
        return ByteArray(16) { i -> (if (i < 8) z[0] ushr (56 - 8 * i) else z[1] ushr (120 - 8 * i)).toByte() }
    }

    /** AES-GCM decryption, 128-bit tag at the end of [encrypted], no additional data; null when the tag does not match. */
    fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, encrypted: ByteArray): ByteArray? {
        if (encrypted.size < 16) return null
        val roundKeys = aesExpandKey(key)
        val h = aesEncryptBlock(roundKeys, ByteArray(16))
        val hHi = longAt(h, 0)
        val hLo = longAt(h, 8)
        val j0 = if (iv.size == 12) {
            iv + byteArrayOf(0, 0, 0, 1)
        } else {
            ghash(hHi, hLo, iv, 0L, iv.size.toLong() * 8)
        }
        val cipher = encrypted.copyOfRange(0, encrypted.size - 16)
        val tag = encrypted.copyOfRange(encrypted.size - 16, encrypted.size)

        val s = ghash(hHi, hLo, cipher, 0L, cipher.size.toLong() * 8)
        val ek = aesEncryptBlock(roundKeys, j0)
        var diff = 0
        for (i in 0 until 16) diff = diff or ((s[i].toInt() xor ek[i].toInt() xor tag[i].toInt()) and 0xFF)
        if (diff != 0) return null

        val counter = j0.copyOf()
        val plain = ByteArray(cipher.size)
        var offset = 0
        while (offset < cipher.size) {
            // inc32: the last four bytes, big-endian, modulo 2^32.
            for (i in 15 downTo 12) {
                counter[i] = (counter[i] + 1).toByte()
                if (counter[i] != 0.toByte()) break
            }
            val stream = aesEncryptBlock(roundKeys, counter)
            for (i in 0 until minOf(16, cipher.size - offset)) plain[offset + i] = (cipher[offset + i].toInt() xor stream[i].toInt()).toByte()
            offset += 16
        }
        return plain
    }
}
