package com.timelock.blocker

import android.content.Context
import android.content.SharedPreferences
import java.net.URI
import java.util.Locale

class BlockedWebsitesRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "timelock_blocked_sites_pref"
        private const val KEY_BLOCKED_DOMAINS = "blocked_domains_set"

        fun normalizeDomain(rawInput: String): String {
            var trimmed = rawInput.trim().lowercase(Locale.ROOT)
            if (trimmed.isEmpty()) return ""

            if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                trimmed = "http://$trimmed"
            }

            return try {
                val uri = URI(trimmed)
                var host = uri.host ?: trimmed
                if (host.startsWith("www.")) {
                    host = host.substring(4)
                }
                host.trimEnd('.')
            } catch (e: Exception) {
                trimmed.replace("http://", "")
                    .replace("https://", "")
                    .split("/")[0]
                    .replace("www.", "")
                    .trimEnd('.')
            }
        }
    }

    fun getBlockedDomains(): Set<String> {
        return prefs.getStringSet(KEY_BLOCKED_DOMAINS, emptySet()) ?: emptySet()
    }

    fun addDomain(rawInput: String): String? {
        val domain = normalizeDomain(rawInput)
        if (domain.isBlank() || !domain.contains(".")) {
            return null
        }
        val current = getBlockedDomains().toMutableSet()
        current.add(domain)
        prefs.edit().putStringSet(KEY_BLOCKED_DOMAINS, current).apply()
        return domain
    }

    fun removeDomain(domain: String) {
        val current = getBlockedDomains().toMutableSet()
        current.remove(domain)
        prefs.edit().putStringSet(KEY_BLOCKED_DOMAINS, current).apply()
    }

    fun isDomainBlocked(queriedDomain: String): Boolean {
        val cleanQueried = queriedDomain.trim().lowercase(Locale.ROOT).trimEnd('.')
        val blocked = getBlockedDomains()

        for (b in blocked) {
            val cleanBlocked = b.trim().lowercase(Locale.ROOT).trimEnd('.')
            if (cleanQueried == cleanBlocked || cleanQueried.endsWith(".$cleanBlocked")) {
                return true
            }
        }
        return false
    }
}