package com.portal.calendar

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File

/**
 * Whole-Portal config export/import — a one-paste clone of this device onto
 * another. Captures the single "config" SharedPreferences (feeds, members'
 * settings, features, layout, AND credentials: Gemini key, iCloud password,
 * Google refresh token) plus every family-data JSON file.
 *
 * The code therefore contains secrets. User backups are passphrase-encrypted
 * ([BackupCrypto], "PORTALHUB2:"); only the hub-to-spoke mirror still uses the
 * plain "PORTALHUB1:" form, and old plain backups can still be imported. Volatile bits (the short-lived Google access token, the
 * weather + feed caches) are skipped; they regenerate on the new device.
 */
object ConfigBundle {
    private const val MAGIC = "PORTALHUB1:"
    private const val MAGIC2 = "PORTALHUB2:"
    private const val PREFS = "config"

    // Prefs that are caches/short-lived — re-derived on the target device.
    private val SKIP_PREFS = setOf("g_access", "g_token_exp", "server_token")

    private val DATA_FILES = listOf(
        "members.json", "lists.json", "chores.json", "chore_done.json",
        "star_goals.json", "chore_history.json", "mealplan.json",
        "recipes.json", "magic_done.json", "routines.json", "routine_done.json", "chore_points.json", "potty_log.json", "shopping.json", "chore_rewards.json", "chore_profiles.json", "potty_bonus.json")

    /** Passphrase-encrypted backup for the user to keep or clone with. */
    fun export(ctx: Context, passphrase: String): String =
        MAGIC2 + BackupCrypto.encrypt(bundleJson(ctx).toByteArray(Charsets.UTF_8), passphrase)

    /** Unencrypted form, only for the hub-to-spoke mirror, which is behind the pairing token. */
    fun exportPlain(ctx: Context): String = MAGIC + Base64.encodeToString(
        bundleJson(ctx).toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun bundleJson(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pj = JSONObject()
        for ((k, v) in prefs.all) {
            if (k in SKIP_PREFS || v == null) continue
            // Type-tagged so import restores the exact SharedPreferences type.
            val (t, value) = when (v) {
                is Boolean -> "b" to v
                is Int -> "i" to v
                is Long -> "l" to v
                is Float -> "f" to v.toDouble()
                else -> "s" to v.toString()
            }
            // Secrets are stored sealed with this device's Keystore key; the bundle carries them readable.
            val shown = if (k in Secrets.KEYS) Secrets.open(value.toString()) ?: continue else value
            pj.put(k, JSONObject().put("t", t).put("v", shown))
        }
        val fj = JSONObject()
        for (name in DATA_FILES) {
            val f = File(ctx.filesDir, name)
            if (f.exists()) fj.put(name, f.readText())
        }
        return JSONObject().put("v", 1).put("prefs", pj).put("files", fj).toString()
    }

    /**
     * Factory reset: erases every setting, member, chore, list, feed, credential and cached feed, leaving a fresh install.
     * The pairing token is kept so the phone that asked for the reset stays signed in; the unpacked voice model stays too.
     */
    fun resetAll(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val token = prefs.getString("server_token", null)
        val e = prefs.edit().clear()
        token?.let { e.putString("server_token", it) }
        e.commit()
        ctx.filesDir.listFiles()?.forEach { if (it.isFile) it.delete() }
        FamilySync.applyRole(ctx) // back to solo
    }

    /** Replaces this device's config with the bundle. Throws on a bad code or wrong passphrase. */
    fun import(ctx: Context, code: String, passphrase: String? = null) {
        val trimmed = code.trim()
        val json = when {
            trimmed.startsWith(MAGIC2) -> {
                if (passphrase.isNullOrEmpty())
                    throw IllegalArgumentException("enter the passphrase this backup was made with")
                String(BackupCrypto.decrypt(trimmed.removePrefix(MAGIC2), passphrase), Charsets.UTF_8)
            }
            trimmed.startsWith(MAGIC) -> try {
                String(Base64.decode(trimmed.removePrefix(MAGIC), Base64.NO_WRAP), Charsets.UTF_8)
            } catch (e: Exception) {
                throw IllegalArgumentException("the backup code is corrupted or incomplete")
            }
            else -> throw IllegalArgumentException("that doesn't look like a PortalHub backup code")
        }
        val root = JSONObject(json)
        val prefsIn = root.optJSONObject("prefs")
            ?: throw IllegalArgumentException("the backup code is missing its settings")

        // VALIDATE EVERYTHING before touching the device — a malformed bundle
        // must not clear prefs and then throw mid-restore, half-bricking the
        // setup. Parse all prefs + file payloads up front; only apply if clean.
        val parsedPrefs = ArrayList<Triple<String, String, Any>>() // key, type, value
        try {
            for (k in prefsIn.keys()) {
                val e = prefsIn.getJSONObject(k)
                val t = e.optString("t")
                val v: Any = when (t) {
                    "b" -> e.getBoolean("v")
                    "i" -> e.getInt("v")
                    "l" -> e.getLong("v")
                    "f" -> e.getDouble("v")
                    else -> e.getString("v")
                }
                parsedPrefs.add(Triple(k, t, v))
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("the backup code's settings are malformed — nothing was changed")
        }
        val parsedFiles = ArrayList<Pair<String, String>>()
        root.optJSONObject("files")?.let { files ->
            try {
                for (name in files.keys()) {
                    if (name !in DATA_FILES) continue // ignore anything unexpected
                    parsedFiles.add(name to files.getString(name))
                }
            } catch (e: Exception) {
                throw IllegalArgumentException("the backup code's data is malformed — nothing was changed")
            }
        }

        // Apply (validated): replace config prefs wholesale, then data files.
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // The pairing token is this device's identity: wiping it would desync the running server.
        val pairingToken = prefs.getString("server_token", null)
        val editor = prefs.edit().clear()
        pairingToken?.let { editor.putString("server_token", it) }
        for ((k, t, v) in parsedPrefs) when (t) {
            "b" -> editor.putBoolean(k, v as Boolean)
            "i" -> editor.putInt(k, v as Int)
            "l" -> editor.putLong(k, v as Long)
            "f" -> editor.putFloat(k, (v as Double).toFloat())
            else -> editor.putString(k, if (k in Secrets.KEYS && v is String && v.isNotEmpty()) Secrets.seal(v) else v as String)
        }
        editor.commit()
        for ((name, text) in parsedFiles) Data.writeRaw(ctx, name, text)
    }
}
