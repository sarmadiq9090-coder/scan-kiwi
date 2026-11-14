// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.core

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * PDF Standard Security Handler, revision 6 (AES-256, ISO 32000-2 §7.6.4).
 * Opens with the user password in any modern reader; all permissions granted.
 */
class PdfCrypt private constructor(
    private val fileKey: ByteArray,
    private val u: ByteArray,
    private val ue: ByteArray,
    private val o: ByteArray,
    private val oe: ByteArray,
    private val perms: ByteArray,
    private val random: SecureRandom,
) {
    /** AES-256-CBC with a random IV prepended and PKCS#7 padding (AESV3). */
    fun encrypt(data: ByteArray): ByteArray {
        val iv = ByteArray(16).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(fileKey, "AES"), IvParameterSpec(iv))
        return iv + c.doFinal(data)
    }

    fun encryptDictionary(): String {
        val h = PdfWriter::hex
        return "<< /Filter /Standard /V 5 /R 6 /Length 256 " +
            "/CF << /StdCF << /Type /CryptFilter /AuthEvent /DocOpen /CFM /AESV3 /Length 32 >> >> " +
            "/StmF /StdCF /StrF /StdCF " +
            "/O <${h(o)}> /U <${h(u)}> /OE <${h(oe)}> /UE <${h(ue)}> /Perms <${h(perms)}> " +
            "/P $PERMISSIONS /EncryptMetadata true >>"
    }

    companion object {
        /** All permission bits set (print, copy, edit...) — the password only guards opening. */
        const val PERMISSIONS = -4

        fun create(userPassword: String, random: SecureRandom): PdfCrypt {
            val user = prep(userPassword)
            val owner = ByteArray(32).also { random.nextBytes(it) }.let { PdfWriter.hex(it).toByteArray() }.copyOf(32)
            val fileKey = ByteArray(32).also { random.nextBytes(it) }

            val uValSalt = ByteArray(8).also { random.nextBytes(it) }
            val uKeySalt = ByteArray(8).also { random.nextBytes(it) }
            val u = hash(user, uValSalt, ByteArray(0)) + uValSalt + uKeySalt
            val ue = aesNoPad(hash(user, uKeySalt, ByteArray(0)), fileKey)

            val oValSalt = ByteArray(8).also { random.nextBytes(it) }
            val oKeySalt = ByteArray(8).also { random.nextBytes(it) }
            val o = hash(owner, oValSalt, u) + oValSalt + oKeySalt
            val oe = aesNoPad(hash(owner, oKeySalt, u), fileKey)

            val p = PERMISSIONS
            val permsPlain = byteArrayOf(
                (p and 0xFF).toByte(), ((p shr 8) and 0xFF).toByte(), ((p shr 16) and 0xFF).toByte(), ((p shr 24) and 0xFF).toByte(),
                0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
                'T'.code.toByte(), 'a'.code.toByte(), 'd'.code.toByte(), 'b'.code.toByte(),
                0, 0, 0, 0,
            )
            for (i in 12 until 16) permsPlain[i] = random.nextInt(256).toByte()
            val ecb = Cipher.getInstance("AES/ECB/NoPadding")
            ecb.init(Cipher.ENCRYPT_MODE, SecretKeySpec(fileKey, "AES"))
            val perms = ecb.doFinal(permsPlain)
            return PdfCrypt(fileKey, u, ue, o, oe, perms, random)
        }

        /** UTF-8, truncated to 127 bytes (SASLprep is skipped; Arabic/Latin text is unaffected). */
        private fun prep(pw: String): ByteArray {
            val b = pw.toByteArray(Charsets.UTF_8)
            return if (b.size > 127) b.copyOf(127) else b
        }

        private fun aesNoPad(key: ByteArray, data: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/CBC/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16)))
            return c.doFinal(data)
        }

        /** Algorithm 2.B (ISO 32000-2): iterated SHA-256/384/512 + AES-128 hash. */
        internal fun hash(password: ByteArray, salt: ByteArray, udata: ByteArray): ByteArray {
            var k = MessageDigest.getInstance("SHA-256").digest(password + salt + udata)
            var e = ByteArray(0)
            var i = 0
            while (i < 64 || (e[e.size - 1].toInt() and 0xFF) > i - 32) {
                val block = password + k + udata
                val k1 = ByteArray(block.size * 64)
                for (r in 0 until 64) System.arraycopy(block, 0, k1, r * block.size, block.size)
                val c = Cipher.getInstance("AES/CBC/NoPadding")
                c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k.copyOfRange(0, 16), "AES"), IvParameterSpec(k.copyOfRange(16, 32)))
                e = c.doFinal(k1)
                var sum = 0
                for (j in 0 until 16) sum += e[j].toInt() and 0xFF
                k = when (sum % 3) {
                    0 -> MessageDigest.getInstance("SHA-256").digest(e)
                    1 -> MessageDigest.getInstance("SHA-384").digest(e)
                    else -> MessageDigest.getInstance("SHA-512").digest(e)
                }
                i++
            }
            return k.copyOf(32)
        }
    }
}
