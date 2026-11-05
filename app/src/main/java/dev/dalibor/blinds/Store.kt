package dev.dalibor.blinds

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import dev.dalibor.blinds.gateway.Device
import org.json.JSONArray
import org.json.JSONObject
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Credentials(val host: String, val login: String, val userKey: ByteArray)

/**
 * Settings and the last known blinds. Only the password's hash is kept, because that is what the
 * gateway checks. The hash still works as a login, so it is encrypted with a Keystore key.
 */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("blinds", Context.MODE_PRIVATE)

    var credentials: Credentials?
        get() {
            val host = prefs.getString("host", null) ?: return null
            val login = prefs.getString("login", null) ?: return null
            val sealed = prefs.getString("key", null) ?: return null
            val key = try {
                unseal(Base64.decode(sealed, Base64.NO_WRAP))
            } catch (e: GeneralSecurityException) {
                return null
            }
            return Credentials(host, login, key)
        }
        set(value) = prefs.edit {
            if (value == null) {
                clear()
            } else {
                putString("host", value.host)
                putString("login", value.login)
                putString("key", Base64.encodeToString(seal(value.userKey), Base64.NO_WRAP))
            }
        }

    var blinds: List<Device>
        get() {
            val array = JSONArray(prefs.getString("blinds", "[]"))
            return List(array.length()) { i ->
                val o = array.getJSONObject(i)
                Device(o.getLong("id"), o.getString("name"), o.getInt("type"), o.getBoolean("favourite"))
            }
        }
        set(value) = prefs.edit {
            val array = JSONArray()
            value.forEach {
                array.put(
                    JSONObject().put("id", it.id).put("name", it.name)
                        .put("type", it.type).put("favourite", it.favourite),
                )
            }
            putString("blinds", array.toString())
        }

    var openPercent: Map<Long, Int>
        get() {
            val o = JSONObject(prefs.getString("open", "{}")!!)
            return o.keys().asSequence().associate { it.toLong() to o.getInt(it) }
        }
        set(value) = prefs.edit {
            val o = JSONObject()
            value.forEach { (id, percent) -> o.put(id.toString(), percent) }
            putString("open", o.toString())
        }

    var notificationEnabled: Boolean
        get() = prefs.getBoolean("notification", false)
        set(value) = prefs.edit { putBoolean("notification", value) }

    fun widgetTarget(widgetId: Int): Long = prefs.getLong("widget_$widgetId", ALL_BLINDS)
    fun setWidgetTarget(widgetId: Int, target: Long) = prefs.edit { putLong("widget_$widgetId", target) }
    fun removeWidget(widgetId: Int) = prefs.edit { remove("widget_$widgetId") }

    private fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun unseal(sealed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = GCMParameterSpec(128, sealed, 0, GCM_IV_SIZE)
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), iv)
        return cipher.doFinal(sealed, GCM_IV_SIZE, sealed.size - GCM_IV_SIZE)
    }

    /** No unlock requirement on this key: buttons on the lock screen have to be able to use it. */
    private fun keystoreKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as SecretKey?)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "gateway"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_SIZE = 12
    }
}
