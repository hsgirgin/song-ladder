package com.songladder.android.data.preview

import java.util.concurrent.ConcurrentHashMap

/**
 * Caches resolved preview URLs with separate TTLs for a match ([positiveTtlMillis], long-lived
 * since a found preview won't disappear) and a confirmed no-match ([negativeTtlMillis], short,
 * so a song isn't stuck "unavailable" if it becomes resolvable later). Callers are expected to
 * skip caching entirely on transient failures (network errors, non-2xx responses) by not calling
 * [put] for those cases.
 */
internal class PreviewUrlCache(
    private val positiveTtlMillis: Long,
    private val negativeTtlMillis: Long
) {
    private class Entry(val url: String?, val expiresAtMillis: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    fun getIfFresh(key: String): CachedResult? {
        val entry = entries[key] ?: return null
        return if (entry.expiresAtMillis > System.currentTimeMillis()) CachedResult(entry.url) else null
    }

    fun put(key: String, url: String?) {
        val ttlMillis = if (url != null) positiveTtlMillis else negativeTtlMillis
        entries[key] = Entry(url, System.currentTimeMillis() + ttlMillis)
    }

    fun invalidate(key: String) {
        entries.remove(key)
    }

    data class CachedResult(val url: String?)
}
