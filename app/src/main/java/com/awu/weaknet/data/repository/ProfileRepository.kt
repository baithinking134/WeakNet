/**
 * @author awu
 * @date 2026-05-26
 * @desc 配置文件仓库：基于 DataStore Preferences 实现用户自定义配置的持久化存储与读取
 */
package com.awu.weaknet.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.awu.weaknet.data.model.DnsFaultType
import com.awu.weaknet.data.model.NetworkCondition
import com.awu.weaknet.data.model.NetworkProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "profiles")

class ProfileRepository(private val context: Context) {

    companion object {
        private val PROFILES_KEY = stringPreferencesKey("custom_profiles")
    }

    val customProfiles: Flow<List<NetworkProfile>> = context.dataStore.data.map { prefs ->
        val json = prefs[PROFILES_KEY] ?: "[]"
        try { parseProfiles(json) } catch (_: Exception) { emptyList() }
    }

    suspend fun saveProfile(profile: NetworkProfile) {
        context.dataStore.edit { prefs ->
            val existing = prefs[PROFILES_KEY] ?: "[]"
            val array = JSONArray(existing)
            // Upsert: replace if same ID exists, otherwise append
            var replaced = false
            for (i in 0 until array.length()) {
                if (array.getJSONObject(i).getString("id") == profile.id) {
                    array.put(i, profileToJson(profile))
                    replaced = true
                    break
                }
            }
            if (!replaced) {
                array.put(profileToJson(profile))
            }
            prefs[PROFILES_KEY] = array.toString()
        }
    }

    suspend fun updateProfile(profile: NetworkProfile) {
        // saveProfile 已实现 upsert，直接委托
        saveProfile(profile)
    }

    suspend fun deleteProfile(profileId: String) {
        context.dataStore.edit { prefs ->
            val existing = prefs[PROFILES_KEY] ?: "[]"
            val array = JSONArray(existing)
            val newArray = JSONArray()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                if (obj.getString("id") != profileId) {
                    newArray.put(obj)
                }
            }
            prefs[PROFILES_KEY] = newArray.toString()
        }
    }

    private fun parseProfiles(json: String): List<NetworkProfile> {
        val array = JSONArray(json)
        val profiles = mutableListOf<NetworkProfile>()
        for (i in 0 until array.length()) {
            try {
                val obj = array.getJSONObject(i)
                profiles.add(jsonToProfile(obj))
            } catch (e: Exception) {
                android.util.Log.w("ProfileRepository", "Skipping corrupted profile at index $i", e)
            }
        }
        return profiles
    }

    private fun profileToJson(profile: NetworkProfile): JSONObject {
        val cond = profile.condition
        val condJson = JSONObject().apply {
            put("delayMs", cond.delayMs)
            put("jitterMs", cond.jitterMs)
            put("packetLossPercent", cond.packetLossPercent)
            put("uploadSpeedKbps", cond.uploadSpeedKbps)
            put("downloadSpeedKbps", cond.downloadSpeedKbps)
            put("duplicatePercent", cond.duplicatePercent)
            put("reorderBufferSize", cond.reorderBufferSize)
            put("tamperPercent", cond.tamperPercent)
            put("disconnectEnabled", cond.disconnectEnabled)
            put("disconnectIntervalMs", cond.disconnectIntervalMs)
            put("disconnectDurationMs", cond.disconnectDurationMs)
            put("dnsFaultType", cond.dnsFaultType.name)
            put("dnsHijackIp", cond.dnsHijackIp)
        }
        return JSONObject().apply {
            put("id", profile.id)
            put("name", profile.name)
            put("icon", profile.icon)
            put("isPreset", profile.isPreset)
            put("description", profile.description)
            put("condition", condJson)
        }
    }

    private fun jsonToProfile(obj: JSONObject): NetworkProfile {
        val condObj = obj.getJSONObject("condition")
        return NetworkProfile(
            id = obj.getString("id"),
            name = obj.getString("name"),
            icon = obj.optString("icon", "custom"),
            isPreset = obj.optBoolean("isPreset", false),
            description = obj.optString("description", ""),
            condition = NetworkCondition(
                delayMs = condObj.optInt("delayMs", 0),
                jitterMs = condObj.optInt("jitterMs", 0),
                packetLossPercent = condObj.optInt("packetLossPercent", 0),
                uploadSpeedKbps = condObj.optInt("uploadSpeedKbps", 0),
                downloadSpeedKbps = condObj.optInt("downloadSpeedKbps", 0),
                duplicatePercent = condObj.optInt("duplicatePercent", 0),
                reorderBufferSize = condObj.optInt("reorderBufferSize", 0),
                tamperPercent = condObj.optInt("tamperPercent", 0),
                disconnectEnabled = condObj.optBoolean("disconnectEnabled", false),
                disconnectIntervalMs = condObj.optInt("disconnectIntervalMs", 30000),
                disconnectDurationMs = condObj.optInt("disconnectDurationMs", 2000),
                dnsFaultType = try {
                    DnsFaultType.valueOf(condObj.optString("dnsFaultType", "NONE"))
                } catch (_: Exception) { DnsFaultType.NONE },
                dnsHijackIp = condObj.optString("dnsHijackIp", "1.2.3.4"),
            )
        )
    }
}
