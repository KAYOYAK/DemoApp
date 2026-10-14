package com.example.demoapp.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypting what this app writes to its own directory.
 *
 * ## What this adds, given the platform already encrypts
 *
 * Android encrypts app storage at rest, and for a device with a screen lock that
 * is the whole story: the key comes from the user's credential, and a phone that is
 * off is unreadable.
 *
 * It does nothing for a device **without** a screen lock. Credential-encrypted
 * storage is unlocked by default on those, so the app's files are readable by
 * anything that can read the filesystem -- which, on a rooted device, is anything
 * at all. That is the gap this closes, and it is the ordinary case rather than a
 * paranoid one: plenty of people run a phone with no lock.
 *
 * The key lives in the device's keystore, which is why it works here and would not
 * work as a file next to the data: root can read the app's directory and cannot use
 * the app's keystore entry, because the entry is bound to this app's identity and
 * the cryptographic work happens outside the process.
 *
 * ## What it does not do
 *
 * It does not stop the app itself, or anything running as this app's user id. It is
 * not a password: there is nothing for the user to type and nothing to lose. And it
 * does not protect a conversation that has been exported -- the share sheet, the
 * Markdown export, the backup file -- because those leave by design.
 *
 * ## Why the plaintext fallback
 *
 * Every file this app wrote before this existed is plaintext, and refusing to read
 * them would lose the user's conversations to keep a tidier format. A read tries the
 * cipher and falls back to the raw bytes; a write always produces ciphertext. The
 * cost is that an old file stays readable until it is next saved, which is stated
 * rather than hidden.
 */
object FileCipher {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "com.example.demoapp.storage"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** 128 bits, the recommended length for GCM. */
    private const val TAG_BITS = 128

    /** A magic byte, so a plaintext file is recognised without trying to decrypt it. */
    private val MARKER = byteArrayOf(0x4E, 0x55, 0x4C, 0x4C, 0x01) // "NULL", version 1

    private val keyLock = Any()
    private var cached: SecretKey? = null

    /**
     * The key, created on first use.
     *
     * `setUserAuthenticationRequired` is deliberately not set. Requiring the lock
     * to read the app's own storage would mean the conversations cannot be listed
     * on a locked screen and cannot be written by a background save -- and the app
     * already offers a lock that asks before showing anything.
     */
    private fun key(): SecretKey? {
        cached?.let { return it }
        synchronized(keyLock) {
            cached?.let { return it }
            return runCatching {
                val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
                (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
                    ?: generate()
            }.getOrNull()?.also { cached = it }
        }
    }

    private fun generate(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // A fresh IV per write. GCM is broken by a repeated IV under the same
                // key, and the platform generates it here rather than the caller.
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    /**
     * Encrypts [plain], or returns it unchanged when there is no key.
     *
     * Unchanged rather than failing: a device whose keystore is unavailable should
     * still let the user write a conversation down. Losing the data is a worse
     * outcome than storing it unencrypted, and the app says on the About screen what
     * protects the files.
     */
    fun encrypt(plain: ByteArray): ByteArray {
        val secret = key() ?: return plain
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, secret)
            }
            // The IV goes in front of the ciphertext. It is not secret -- it is
            // random and unique, which is the whole requirement -- and putting it
            // there means one file to write rather than two to keep in step.
            val iv = cipher.iv
            MARKER + byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(plain)
        }.getOrElse { plain }
    }

    /**
     * Decrypts [stored], or returns it unchanged when it is not ciphertext.
     *
     * Three cases, and only the first is the normal one:
     *
     * - Ciphertext from this app: the marker is there and the tag verifies.
     * - A file written before this existed: no marker, so the bytes are the data.
     * - A file whose key is gone -- the keystore was cleared, the app was restored
     *   onto another device: the marker is there and the tag will not verify. Null,
     *   because returning the ciphertext would put a wall of base64 on screen and
     *   returning an empty value would look like the conversation was empty.
     */
    fun decrypt(stored: ByteArray, onUnreadable: () -> Unit = {}): ByteArray? {
        if (!stored.startsWith(MARKER)) return stored

        val secret = key()
        if (secret == null) {
            onUnreadable()
            return null
        }
        return runCatching {
            val afterMarker = MARKER.size
            val ivSize = stored[afterMarker].toInt()
            val ivStart = afterMarker + 1
            val iv = stored.copyOfRange(ivStart, ivStart + ivSize)
            val body = stored.copyOfRange(ivStart + ivSize, stored.size)
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(TAG_BITS, iv))
            }.doFinal(body)
        }.getOrElse {
            onUnreadable()
            null
        }
    }

    /** Reads a file, decrypting it if it is ciphertext. */
    fun read(file: File, onUnreadable: () -> Unit = {}): ByteArray? =
        runCatching { decrypt(file.readBytes(), onUnreadable) }.getOrNull()

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        return prefix.indices.all { this[it] == prefix[it] }
    }
}
