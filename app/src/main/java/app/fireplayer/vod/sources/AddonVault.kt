package app.fireplayer.vod.sources

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.KeyPairGeneratorSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.fireplayer.vod.Addon
import app.fireplayer.vod.AddonSave
import app.fireplayer.vod.AddonSettings
import app.fireplayer.vod.SecretUrl
import app.fireplayer.vod.parseLink
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.util.Calendar
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.security.auth.x500.X500Principal

/**
 * The add-ons' links (Settings › Add-ons), stored encrypted ([Sealer]) in a file of their own that holds ciphertext
 * only. Read once into memory as [SecretUrl]s; never logged, never shown in full ([summary]: host and last 4
 * characters). Any change calls [changed] (the engine drops what it kept). Main thread.
 */
class AddonVault(private val store: Store, private val sealer: Sealer, private val changed: () -> Unit) : AddonSettings {

    /** Text by name: the vault's own preference file. */
    interface Store {
        fun get(name: String): String?
        fun put(name: String, value: String?): Boolean
    }

    /** Encrypts and decrypts small values; null when it can't (no keystore). */
    interface Sealer {
        fun seal(plain: ByteArray): ByteArray?
        fun open(sealed: ByteArray): ByteArray?
    }

    private val links = HashMap<Addon, SecretUrl>()
    private var loaded = false

    /** The saved base link of [addon]: memory only, for the engine. Null when none (or it can't be read). */
    fun link(addon: Addon): SecretUrl? {
        load()
        return links[addon]
    }

    override fun isSet(addon: Addon): Boolean = link(addon) != null

    override fun summary(addon: Addon): String? = link(addon)?.let { summaryOf(it.reveal()) }

    override fun save(addon: Addon, link: String): AddonSave {
        val clean = normalise(link) ?: return AddonSave.NOT_A_LINK
        val url = SecretUrl.of(clean) ?: return AddonSave.NOT_A_LINK
        if (looksLikeOther(addon, clean)) return AddonSave.NOT_THIS_ADDON
        load()
        if (links[addon] == url) return AddonSave.SAVED
        val sealed = sealer.seal(clean.toByteArray(Charsets.UTF_8)) ?: return AddonSave.COULD_NOT_STORE
        if (!store.put(key(addon), hex(sealed))) return AddonSave.COULD_NOT_STORE
        links[addon] = url
        changed()
        return AddonSave.SAVED
    }

    override fun remove(addon: Addon) {
        load()
        val had = links.remove(addon) != null
        store.put(key(addon), null)
        if (had) changed()
    }

    private fun load() {
        if (loaded) return
        loaded = true
        for (addon in Addon.entries) {
            val text = store.get(key(addon)) ?: continue
            val plain = unhex(text)?.let { sealer.open(it) } ?: continue
            SecretUrl.of(String(plain, Charsets.UTF_8))?.let { links[addon] = it }
        }
    }

    private fun key(addon: Addon) = "link_${addon.name.lowercase(Locale.ROOT)}"

    companion object {
        const val FILE = "fireplayer_addons"

        /**
         * The link as the add-on is asked: "stremio://" read as "https://", a trailing "/manifest.json" or "/configure"
         * (and slash) dropped. Null when it isn't an http(s) link.
         */
        fun normalise(link: String): String? {
            var s = link.trim()
            if (s.isEmpty()) return null
            if (s.startsWith("stremio://", ignoreCase = true)) s = "https://" + s.substring("stremio://".length)
            if (!s.startsWith("https://", ignoreCase = true) && !s.startsWith("http://", ignoreCase = true)) return null
            s = s.substringBefore('#').substringBefore('?')
            for (tail in listOf("/manifest.json", "/configure")) {
                if (s.endsWith(tail, ignoreCase = true)) s = s.dropLast(tail.length)
            }
            s = s.trimEnd('/')
            return s.takeIf { it.length > "https://".length && SecretUrl.of(it)?.host != null }
        }

        /** "torrentio.strem.fun · …x7Qa": the host and the last 4 characters, never more. */
        fun summaryOf(link: String): String {
            val host = SecretUrl.of(link)?.host ?: "?"
            val tail = link.trimEnd('/').takeLast(4)
            return "$host · …$tail"
        }

        /**
         * A link that is plainly another add-on's: its name in the host, or StremThru Torz's path (".../stremio/torz/...").
         * Torz's link must have that path: StremThru serves other add-ons too.
         */
        private fun looksLikeOther(addon: Addon, link: String): Boolean {
            val host = SecretUrl.of(link)?.host?.lowercase(Locale.ROOT) ?: return false
            val torz = isTorzPath(parseLink(link)?.rawPath)
            return when (addon) {
                Addon.TORRENTIO -> "mediafusion" in host || torz
                Addon.MEDIAFUSION -> "torrentio" in host || torz
                Addon.TORZ -> !torz || "torrentio" in host || "mediafusion" in host
            }
        }

        /** Whether [path] is StremThru Torz's: ".../stremio/torz" followed by its settings, or nothing. */
        fun isTorzPath(path: String?): Boolean {
            val parts = path?.lowercase(Locale.ROOT)?.split('/')?.filter { it.isNotEmpty() } ?: return false
            return (0 until parts.size - 1).any { parts[it] == "stremio" && parts[it + 1] == "torz" }
        }

        fun hex(bytes: ByteArray): String {
            val out = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                val v = b.toInt() and 0xff
                out.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
            }
            return out.toString()
        }

        fun unhex(text: String): ByteArray? {
            if (text.length % 2 != 0) return null
            val out = ByteArray(text.length / 2)
            for (i in out.indices) {
                val hi = Character.digit(text[2 * i], 16)
                val lo = Character.digit(text[2 * i + 1], 16)
                if (hi < 0 || lo < 0) return null
                out[i] = ((hi shl 4) or lo).toByte()
            }
            return out
        }

        private val HEX = "0123456789abcdef".toCharArray()

        fun of(context: Context, changed: () -> Unit): AddonVault {
            val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            return AddonVault(PrefsStore(prefs), KeystoreSealer(context.applicationContext, prefs), changed)
        }
    }
}

/** The vault's preference file; written at once (commit), like [app.fireplayer.data.KeyStore]. */
private class PrefsStore(private val prefs: SharedPreferences) : AddonVault.Store {
    override fun get(name: String): String? = prefs.getString(name, null)

    @SuppressLint("ApplySharedPref")
    override fun put(name: String, value: String?): Boolean =
        if (value == null) prefs.edit().remove(name).commit() else prefs.edit().putString(name, value).commit()
}

/**
 * AES-GCM with a key that never leaves the Android Keystore (API 23+). On API 22, where the Keystore has no AES, an
 * RSA key pair in the Keystore wraps a random AES key kept (wrapped) in [prefs]. Any failure gives null: the link
 * isn't saved rather than saved in the clear.
 */
private class KeystoreSealer(private val app: Context, private val prefs: SharedPreferences) : AddonVault.Sealer {

    override fun seal(plain: ByteArray): ByteArray? = runCatching {
        val cipher = Cipher.getInstance(AES)
        cipher.init(Cipher.ENCRYPT_MODE, aesKey())
        cipher.iv + cipher.doFinal(plain)
    }.getOrNull()

    override fun open(sealed: ByteArray): ByteArray? = runCatching {
        if (sealed.size <= IV) return null
        val cipher = Cipher.getInstance(AES)
        cipher.init(Cipher.DECRYPT_MODE, aesKey(), GCMParameterSpec(128, sealed, 0, IV))
        cipher.doFinal(sealed, IV, sealed.size - IV)
    }.getOrNull()

    private fun keystore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun aesKey(): SecretKey =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) keystoreAes() else wrappedAes()

    @SuppressLint("NewApi")
    private fun keystoreAes(): SecretKey {
        (keystore().getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    /** API 22: a software AES key, wrapped by a Keystore RSA key and kept wrapped. */
    @Suppress("DEPRECATION")
    @SuppressLint("ApplySharedPref", "TrulyRandom")
    private fun wrappedAes(): SecretKey {
        val ks = keystore()
        if (!ks.containsAlias(RSA_ALIAS)) {
            val start = Calendar.getInstance()
            val end = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }
            val gen = KeyPairGenerator.getInstance("RSA", PROVIDER)
            gen.initialize(
                KeyPairGeneratorSpec.Builder(app)
                    .setAlias(RSA_ALIAS)
                    .setSubject(X500Principal("CN=FirePlayer add-ons"))
                    .setSerialNumber(BigInteger.ONE)
                    .setStartDate(start.time)
                    .setEndDate(end.time)
                    .build(),
            )
            gen.generateKeyPair()
        }
        val entry = ks.getEntry(RSA_ALIAS, null) as KeyStore.PrivateKeyEntry
        prefs.getString(WRAPPED, null)?.let { text ->
            val wrapped = AddonVault.unhex(text) ?: error("bad key")
            val rsa = Cipher.getInstance(RSA)
            rsa.init(Cipher.DECRYPT_MODE, entry.privateKey as PrivateKey)
            return SecretKeySpec(rsa.doFinal(wrapped), "AES")
        }
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val rsa = Cipher.getInstance(RSA)
        rsa.init(Cipher.ENCRYPT_MODE, entry.certificate.publicKey)
        prefs.edit().putString(WRAPPED, AddonVault.hex(rsa.doFinal(raw))).commit()
        return SecretKeySpec(raw, "AES")
    }

    companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "fireplayer_addons_aes"
        const val RSA_ALIAS = "fireplayer_addons_rsa"
        const val WRAPPED = "wrapped_key"
        const val AES = "AES/GCM/NoPadding"
        const val RSA = "RSA/ECB/PKCS1Padding"
        const val IV = 12

    }
}
