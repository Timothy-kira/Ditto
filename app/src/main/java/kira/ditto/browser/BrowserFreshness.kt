package kira.ditto.browser

/**
 * Is a page we already read still the page that is there?
 *
 * Borrowed wholesale from how HTTP caches work, and from what Continuity does before a read: send a
 * conditional request whose 304 answer costs a round trip and no body, so the healthy path is fast
 * and the degraded path is still correct. Nothing here re-points a citation or rewrites a cache; it
 * only answers the question, and every caller decides what to do with the answer.
 *
 * The reason this is worth a file: revisiting research is the whole point of a durable ledger, and
 * revisiting means asking "did these forty sources change?" A full re-read answers that at forty
 * page loads. A validator comparison answers it at forty headers - or, inside the same turn, at
 * zero requests, because a page read ninety seconds ago is not in question.
 */
enum class PageFreshness {
    /** The cached version is still current, on evidence. Serve it. */
    Fresh,

    /** The page has changed since we read it. */
    Stale,

    /**
     * Cannot be established.
     *
     * Deliberately not a synonym for either of the others: a caller that must be right treats it as
     * [Stale] and re-reads, and a caller that only wants to annotate says nothing. Guessing [Fresh]
     * here is how a cache starts lying.
     */
    Unknown,
}

/** What a server gave us to check with later. Absent fields are normal, not an error. */
data class PageValidator(
    val etag: String = "",
    val lastModified: String = "",
) {
    val usable: Boolean get() = etag.isNotBlank() || lastModified.isNotBlank()
}

/**
 * How long a freshly read page is simply trusted, no request at all.
 *
 * Within one turn the agent reads a page, recalls it, follows a link back to it and recalls it
 * again. Revalidating each of those would spend network on a question nobody is asking - the page
 * was read moments ago by this same agent. This is HTTP's freshness lifetime, and it is what keeps
 * the check from becoming a tax on the common path.
 */
const val PageFreshnessLifetimeMillis = 15L * 60L * 1000L

/**
 * The freshness a cached read still has on age alone.
 *
 * Returns [PageFreshness.Unknown] rather than [PageFreshness.Stale] once the lifetime is up: age
 * proves a page *might* have changed, never that it did. Only a validator or a hash can say that.
 */
fun freshnessByAge(
    indexedAtMillis: Long,
    nowMillis: Long = System.currentTimeMillis(),
    lifetimeMillis: Long = PageFreshnessLifetimeMillis,
): PageFreshness {
    if (indexedAtMillis <= 0L) return PageFreshness.Unknown
    val age = nowMillis - indexedAtMillis
    if (age < 0L) return PageFreshness.Unknown
    return if (age <= lifetimeMillis) PageFreshness.Fresh else PageFreshness.Unknown
}

/**
 * Compare what the server said then with what it says now.
 *
 * ETag wins outright when both sides have one - it is the server's own statement about identity,
 * where Last-Modified is a timestamp with a one-second floor and a habit of being wrong on generated
 * pages. When neither side offers anything comparable the answer is [PageFreshness.Unknown]; plenty
 * of the news sites this agent reads serve no validators at all.
 */
fun freshnessByValidator(cached: PageValidator, current: PageValidator): PageFreshness {
    if (cached.etag.isNotBlank() && current.etag.isNotBlank()) {
        return if (normalizedETag(cached.etag) == normalizedETag(current.etag)) {
            PageFreshness.Fresh
        } else {
            PageFreshness.Stale
        }
    }
    if (cached.lastModified.isNotBlank() && current.lastModified.isNotBlank()) {
        return if (cached.lastModified == current.lastModified) PageFreshness.Fresh else PageFreshness.Stale
    }
    return PageFreshness.Unknown
}

/**
 * The definitive answer, once the body is in hand.
 *
 * This is the fallback the plan calls for when no validator exists: it is exact, and it costs a full
 * fetch, so it is what the other two functions exist to avoid paying for.
 */
fun freshnessByContentHash(cachedHash: String, currentHash: String): PageFreshness = when {
    cachedHash.isBlank() || currentHash.isBlank() -> PageFreshness.Unknown
    cachedHash == currentHash -> PageFreshness.Fresh
    else -> PageFreshness.Stale
}

/** A weak validator is still a validator here, so W/"abc" and "abc" name the same bytes. */
private fun normalizedETag(raw: String): String =
    raw.trim().removePrefix("W/").removePrefix("w/").trim().trim('"')

/**
 * The validator a page carried when we read it, remembered by the same key the graph uses.
 *
 * Task-scoped like [BrowserResearchGraph], because that is what it annotates. A validator that
 * outlived its page would only ever answer questions about text nobody kept.
 */
object BrowserPageValidators {
    private const val MaxValidators = 256
    private val lock = Any()
    private val validators = LinkedHashMap<String, PageValidator>()

    fun remember(url: String, validator: PageValidator) {
        if (!validator.usable) return
        val key = normalizeBrowsedUrl(url).ifBlank { url.trim() }
        if (key.isBlank()) return
        synchronized(lock) {
            validators.remove(key)
            validators[key] = validator
            while (validators.size > MaxValidators) {
                val oldest = validators.keys.firstOrNull() ?: break
                validators.remove(oldest)
            }
        }
    }

    fun of(url: String): PageValidator? {
        val key = normalizeBrowsedUrl(url).ifBlank { url.trim() }
        if (key.isBlank()) return null
        synchronized(lock) { return validators[key] }
    }

    fun clear() {
        synchronized(lock) { validators.clear() }
    }

    /**
     * How to ask an origin whether a page changed.
     *
     * Installed by the browser runtime so the probe goes out through Gecko - the same cookie jar,
     * HTTP cache and TLS session that read the page in the first place. A second HTTP stack would
     * be asking about a different page whenever a login is involved, and would answer "changed"
     * for the honest reason that it is looking at the signed-out version. Unset means every probe
     * answers [PageFreshness.Unknown], which is the correct answer when nobody can ask.
     */
    @Volatile
    var probe: ((url: String, cached: PageValidator) -> PageFreshness)? = null
}

/**
 * Ask the origin whether a page changed, without downloading it.
 *
 * A conditional GET rather than a HEAD: servers answer If-None-Match with 304 far more consistently
 * than they serve a useful HEAD, and a 304 carries no body either way. Every failure path lands on
 * [PageFreshness.Unknown], never on [PageFreshness.Fresh] - an unanswered question must not be able
 * to serve stale text.
 *
 * Blocking. Call it off the main thread.
 */
fun revalidatePage(
    url: String,
    cached: PageValidator? = BrowserPageValidators.of(url),
): PageFreshness {
    if (cached == null || !cached.usable) return PageFreshness.Unknown
    if (!looksLikeHttpUrl(url)) return PageFreshness.Unknown
    val probe = BrowserPageValidators.probe ?: return PageFreshness.Unknown
    return runCatching { probe(url, cached) }.getOrDefault(PageFreshness.Unknown)
}

/**
 * The whole ladder in one call: age first, then validators, and only then give up.
 *
 * Ordered by cost. The first rung answers most in-task questions for free, the second answers
 * cross-session ones for a round trip, and [PageFreshness.Unknown] leaves the caller to decide
 * whether the question is worth a full read.
 */
fun pageFreshness(
    page: IndexedBrowserPage,
    nowMillis: Long = System.currentTimeMillis(),
    revalidate: (String) -> PageFreshness = { revalidatePage(it) },
): PageFreshness {
    val byAge = freshnessByAge(page.indexedAtMillis, nowMillis)
    if (byAge == PageFreshness.Fresh) return byAge
    return revalidate(page.url)
}
