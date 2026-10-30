package app.myspoti.extensions

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The store's discovery on GitHub, GitLab and Codeberg: every public repository with the topic
 * [TOPIC] is offered, along with the repositories' indexes. One search call per host and store
 * visit, cached 6 hours (GitHub's API allows 10 searches a minute without an account); the
 * manifests and packages then come from the hosts' file addresses, which have no such limit.
 * Ratings (issues' reactions) and download counts exist on GitHub only.
 */
object Discovery {
    const val TOPIC = "myspoti-extension"

    /** The label of the issue in an author's repository whose 👍 and 👎 are the extension's rating. */
    const val RATINGS_LABEL = "myspoti-ratings"

    /** The pseudo-address the discovered extensions are listed under in the store. */
    const val SOURCE = "github:topic/$TOPIC"

    const val CACHE_MS = 6 * 3600 * 1000L

    /** Up to 100 repositories with the topic, the most recently pushed first. */
    const val SEARCH_URL = "https://api.github.com/search/repositories?q=topic:$TOPIC+fork:true+archived:false&sort=updated&order=desc&per_page=100"

    /** Up to 100 public GitLab projects with the topic, the most recently active first. */
    const val GITLAB_SEARCH_URL = "https://gitlab.com/api/v4/projects?topic=$TOPIC&visibility=public&archived=false&order_by=last_activity_at&sort=desc&per_page=100"

    /** Up to 50 Codeberg repositories with the topic (`topic=true`: the query is matched against topics), the most recently updated first. */
    const val CODEBERG_SEARCH_URL = "https://codeberg.org/api/v1/repos/search?q=$TOPIC&topic=true&archived=false&sort=updated&order=desc&limit=50"

    /** The search address of [host]. */
    fun searchUrl(host: CodeHost) = when (host) {
        CodeHost.GITHUB -> SEARCH_URL
        CodeHost.GITLAB -> GITLAB_SEARCH_URL
        CodeHost.CODEBERG -> CODEBERG_SEARCH_URL
    }

    /** Every open ratings issue (one search for all the store, filtered to the discovered repositories' owners). */
    const val RATINGS_URL = "https://api.github.com/search/issues?q=label:$RATINGS_LABEL+is:issue+is:open&per_page=100"

    /** The newest release of [repo], for its packages' download counts (the store page only). */
    fun releaseUrl(repo: String) = "https://api.github.com/repos/$repo/releases/latest"

    @Serializable
    data class Owner(val login: String = "", val id: Long = 0)

    /** A repository as the search answers it (the fields the store uses). */
    @Serializable
    data class Found(
        @SerialName("full_name") val fullName: String,
        val owner: Owner,
        val description: String? = null,
        @SerialName("html_url") val htmlUrl: String = "",
        @SerialName("default_branch") val defaultBranch: String = GitHubRepo.HEAD,
        @SerialName("pushed_at") val pushedAt: String? = null,
        @SerialName("updated_at") val updatedAt: String? = null,
        @SerialName("stargazers_count") val stars: Int = 0,
        val archived: Boolean = false,
        val topics: List<String> = emptyList(),
        @kotlinx.serialization.Transient val host: CodeHost = CodeHost.GITHUB,
        /** The host's numeric id of the repository (0: unknown); [owner]'s is its `id`. */
        val id: Long = 0,
    ) {
        /** The ids to pin, when the host gave both. */
        val ids: RepoIds? get() = if (id > 0 && owner.id > 0) RepoIds(id.toString(), owner.id.toString()) else null

        val repo: GitHubRepo? get() = GitHubRepo.of(host, fullName)

        /** Where its manifest is read, on the default branch. */
        fun manifestUrl(): String? = repo?.raw(defaultBranch, "manifest.json")
    }

    @Serializable
    private data class SearchAnswer(val items: List<Found> = emptyList())

    fun parseSearch(text: String): List<Found> = ExtJson.decodeFromString(SearchAnswer.serializer(), text).items.filter { !it.archived && it.repo != null }

    @Serializable
    private data class GitLabProject(
        @SerialName("path_with_namespace") val path: String,
        val description: String? = null,
        @SerialName("web_url") val webUrl: String = "",
        @SerialName("default_branch") val defaultBranch: String? = null,
        @SerialName("last_activity_at") val lastActivityAt: String? = null,
        @SerialName("star_count") val stars: Int = 0,
        val archived: Boolean = false,
        val topics: List<String> = emptyList(),
        val id: Long = 0,
        val namespace: Owner? = null,
    )

    /** GitLab's `/api/v4/projects` answer (a JSON array). */
    fun parseGitLab(text: String): List<Found> =
        ExtJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(GitLabProject.serializer()), text).map {
            Found(it.path, Owner(it.path.substringBeforeLast('/'), it.namespace?.id ?: 0), it.description, it.webUrl, it.defaultBranch ?: GitHubRepo.HEAD,
                it.lastActivityAt, it.lastActivityAt, it.stars, it.archived, it.topics, CodeHost.GITLAB, it.id)
        }.filter { !it.archived && it.repo != null }

    @Serializable
    private data class ForgejoRepo(
        @SerialName("full_name") val fullName: String,
        val description: String? = null,
        @SerialName("html_url") val htmlUrl: String = "",
        @SerialName("default_branch") val defaultBranch: String? = null,
        @SerialName("updated_at") val updatedAt: String? = null,
        @SerialName("stars_count") val stars: Int = 0,
        val archived: Boolean = false,
        val empty: Boolean = false,
        val id: Long = 0,
        val owner: Owner? = null,
    )

    @Serializable
    private data class ForgejoAnswer(val data: List<ForgejoRepo> = emptyList())

    /** Codeberg's (Forgejo/Gitea) `/api/v1/repos/search` answer. */
    fun parseCodeberg(text: String): List<Found> =
        ExtJson.decodeFromString(ForgejoAnswer.serializer(), text).data.filter { !it.empty }.map {
            Found(it.fullName, Owner(it.fullName.substringBefore('/'), it.owner?.id ?: 0), it.description, it.htmlUrl, it.defaultBranch ?: GitHubRepo.HEAD,
                it.updatedAt, it.updatedAt, it.stars, it.archived, emptyList(), CodeHost.CODEBERG, it.id)
        }.filter { !it.archived && it.repo != null }

    /** [parseSearch], [parseGitLab] or [parseCodeberg] by [host]. */
    fun parse(host: CodeHost, text: String): List<Found> = when (host) {
        CodeHost.GITHUB -> parseSearch(text)
        CodeHost.GITLAB -> parseGitLab(text)
        CodeHost.CODEBERG -> parseCodeberg(text)
    }

    /** The default branch name out of Codeberg's repository API answer (null if absent). */
    fun parseDefaultBranch(text: String): String? = runCatching {
        (ExtJson.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject)["default_branch"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Pinning a repository: the numeric ids the host gives it, read from its API (one call at install, one per update check). */
    fun identityUrl(repo: GitHubRepo): String = when (repo.host) {
        CodeHost.GITHUB -> "https://api.github.com/repos/${repo.owner}/${repo.name}"
        CodeHost.GITLAB -> "https://gitlab.com/api/v4/projects/" + java.net.URLEncoder.encode("${repo.owner}/${repo.name}", "UTF-8")
        CodeHost.CODEBERG -> "https://codeberg.org/api/v1/repos/${repo.owner}/${repo.name}"
    }

    /**
     * The ids out of one repository's API answer: `id` and `owner.id` (GitHub, Codeberg), `id`
     * and `namespace.id` (GitLab: the user or group, stable when the project moves inside it);
     * null when either is missing.
     */
    fun parseIdentity(host: CodeHost, text: String): RepoIds? = runCatching {
        val o = ExtJson.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
        fun idOf(e: kotlinx.serialization.json.JsonElement?): String? =
            ((e as? kotlinx.serialization.json.JsonObject)?.get("id") as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.toLongOrNull()?.let { n -> n > 0 } == true }
        val repo = (o["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.toLongOrNull()?.let { n -> n > 0 } == true }
        val owner = if (host == CodeHost.GITLAB) idOf(o["namespace"]) else idOf(o["owner"])
        if (repo != null && owner != null) RepoIds(repo, owner) else null
    }.getOrNull()

    /**
     * The store entry of a discovered repository, from its [manifest]: installed from GitHub
     * ([Catalog.Entry.repo]), its package built when the user taps Install. No sha256: GitHub
     * builds the archive, the app keeps the hash of what it installed.
     */
    fun entry(found: Found, manifest: Manifest): Catalog.Entry {
        val repo = found.repo!!
        return Catalog.Entry(
            id = manifest.id, name = manifest.name, version = manifest.version, versionCode = manifest.versionCode,
            apiVersion = manifest.apiVersion, author = manifest.author, description = manifest.description.ifBlank { found.description.orEmpty() },
            actions = manifest.actions, provides = manifest.provides, permissions = manifest.permissions,
            url = repo.archive(found.defaultBranch, tag = false), sha256 = "",
            homepage = manifest.homepage ?: found.htmlUrl.ifBlank { repo.page },
            iconUrl = repo.raw(found.defaultBranch, manifest.icon ?: "icon.png"),
            updatedAt = found.pushedAt ?: found.updatedAt, category = manifest.category, tags = manifest.tags,
            minAppVersion = manifest.minAppVersion, changelog = manifest.changelog,
            replaces = manifest.replaces, conflictsWith = manifest.conflictsWith, repo = repo.slug,
        )
    }

    // ── Ratings ──

    @Serializable
    private data class Reactions(@SerialName("+1") val up: Int = 0, @SerialName("-1") val down: Int = 0)

    @Serializable
    private data class Issue(
        val number: Int,
        val user: Owner,
        @SerialName("repository_url") val repositoryUrl: String = "",
        val reactions: Reactions = Reactions(),
    )

    @Serializable
    private data class IssueAnswer(val items: List<Issue> = emptyList())

    /**
     * The ratings of [repos] (`owner/name`) from the ratings issues search: only an issue opened
     * by the repository's owner counts (anyone can open one elsewhere); the first one wins.
     */
    fun parseRatings(text: String, repos: Set<String>): Map<String, Rating> {
        val out = LinkedHashMap<String, Rating>()
        ExtJson.decodeFromString(IssueAnswer.serializer(), text).items.forEach { i ->
            val repo = i.repositoryUrl.substringAfter("/repos/", "").takeIf { it.count { c -> c == '/' } == 1 } ?: return@forEach
            if (repo !in repos || repo in out || !repo.substringBefore('/').equals(i.user.login, ignoreCase = true)) return@forEach
            out[repo] = Rating(i.reactions.up, i.reactions.down, i.number)
        }
        return out
    }

    @Serializable
    private data class Asset(val name: String = "", @SerialName("download_count") val downloads: Long = 0)

    @Serializable
    private data class Release(val assets: List<Asset> = emptyList())

    /** The downloads of the release's package ([GitHub.ASSET], else all its zips); null without one. */
    fun parseDownloads(text: String): Long? {
        val assets = ExtJson.decodeFromString(Release.serializer(), text).assets
        val pkg = assets.filter { it.name == GitHub.ASSET }.ifEmpty { assets.filter { it.name.endsWith(".zip") } }
        return if (pkg.isEmpty()) null else pkg.sumOf { it.downloads }
    }
}

/** A repository's and its owner's numeric ids on its host (kept as text). */
@Serializable
data class RepoIds(val repo: String, val owner: String)

/** 👍 and 👎 on an extension's ratings issue ([issue] in its repository). */
@Serializable
data class Rating(val up: Int = 0, val down: Int = 0, val issue: Int = 0) {
    val votes: Int get() = up + down
}

/**
 * The store's order and what it hides. Each extension's score is a Bayesian average of its
 * 👍 share: [PRIOR] votes at [PRIOR_MEAN] are added to its own, so three 👍 do not outrank a
 * hundred votes at 95 %. Then the most recently updated first.
 */
object Ranking {
    const val PRIOR_MEAN = 0.7
    const val PRIOR = 5.0

    /** Below this score, with at least [MIN_VOTES] votes, an extension is hidden behind "Show all". */
    const val LOW_SCORE = 0.4
    const val MIN_VOTES = 10

    /** Not updated for 18 months: hidden behind "Show all". */
    const val STALE_MS = 548L * 24 * 3600 * 1000

    fun score(r: Rating?): Double = ((r?.up ?: 0) + PRIOR_MEAN * PRIOR) / ((r?.votes ?: 0) + PRIOR)

    /** The score as 1 to 5 stars, to one decimal. */
    fun stars(r: Rating?): Double = Math.round((1 + 4 * score(r)) * 10) / 10.0

    enum class Hidden { BLOCKED, FLAGGED, LOW_SCORE, STALE }

    /** One store entry, ranked: [updatedMs] from its `updated_at` (0: unknown). */
    data class Ranked<T>(val item: T, val id: String, val sha256: String?, val rating: Rating?, val updatedMs: Long) {
        val score: Double get() = Ranking.score(rating)
    }

    /** Why [r] is hidden behind "Show all" ([now] in ms), or null when it is shown. */
    fun hidden(r: Ranked<*>, blocks: BlockList, now: Long): Hidden? = when {
        blocks.blocks(r.id, r.sha256) != null -> Hidden.BLOCKED
        blocks.flags(r.id) != null -> Hidden.FLAGGED
        (r.rating?.votes ?: 0) >= MIN_VOTES && r.score < LOW_SCORE -> Hidden.LOW_SCORE
        r.updatedMs > 0 && now - r.updatedMs > STALE_MS -> Hidden.STALE
        else -> null
    }

    /** [items] in the store's order: score, then the most recently updated. */
    fun <T> order(items: List<Ranked<T>>): List<Ranked<T>> =
        items.sortedWith(compareByDescending<Ranked<T>> { it.score }.thenByDescending { it.updatedMs })

    /** What the store lists: in order, without the hidden ones unless [showAll]. */
    fun <T> listed(items: List<Ranked<T>>, blocks: BlockList, now: Long, showAll: Boolean): List<Ranked<T>> =
        order(items).filter { showAll || hidden(it, blocks, now) == null }

    /** `2026-10-23` or `2026-10-23T10:00:00Z` in ms; 0 when it cannot be read. */
    fun time(iso: String?): Long {
        val s = iso?.trim().orEmpty()
        if (s.isEmpty()) return 0
        return runCatching { java.time.Instant.parse(s).toEpochMilli() }.recoverCatching {
            java.time.LocalDate.parse(s.take(10)).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        }.getOrDefault(0)
    }
}

/**
 * Rating with 👍 / 👎 needs a GitHub account: the app signs in with GitHub's device flow (the
 * user types a short code on github.com, no password in the app) and reacts on the ratings
 * issue. The tokens of the Myspoti Extensions OAuth app expire (8 hours): a reaction answered
 * 401 simply runs the device flow again. While [GITHUB_CLIENT_ID] is empty the rate buttons
 * are hidden; reading the counts never needs an account.
 */
object GitHubAuth {
    const val GITHUB_CLIENT_ID = "Ov23liFBqyO4elV0s5gq"

    val enabled: Boolean get() = GITHUB_CLIENT_ID.isNotEmpty()

    const val DEVICE_CODE_URL = "https://github.com/login/device/code"
    const val TOKEN_URL = "https://github.com/login/oauth/access_token"

    /** Reacting on public issues: the narrowest scope that allows it. */
    const val SCOPE = "public_repo"

    fun deviceCodeBody(): String = "client_id=$GITHUB_CLIENT_ID&scope=$SCOPE"

    fun tokenBody(deviceCode: String): String =
        "client_id=$GITHUB_CLIENT_ID&device_code=$deviceCode&grant_type=urn:ietf:params:oauth:grant-type:device_code"

    /** What GitHub answers the first call: the code the user types at [verificationUri]. */
    @Serializable
    data class DeviceCode(
        @SerialName("device_code") val deviceCode: String,
        @SerialName("user_code") val userCode: String,
        @SerialName("verification_uri") val verificationUri: String,
        @SerialName("expires_in") val expiresIn: Int = 900,
        val interval: Int = 5,
    )

    fun parseDeviceCode(text: String): DeviceCode = ExtJson.decodeFromString(DeviceCode.serializer(), text)

    /** One poll of [TOKEN_URL] while the user types the code. */
    sealed interface Poll {
        data class Token(val token: String) : Poll
        data object Pending : Poll
        /** GitHub asks to wait 5 more seconds between polls. */
        data object SlowDown : Poll
        data class Failed(val reason: String) : Poll
    }

    @Serializable
    private data class TokenAnswer(
        @SerialName("access_token") val token: String? = null,
        val error: String? = null,
        @SerialName("error_description") val description: String? = null,
    )

    fun parsePoll(text: String): Poll {
        val a = runCatching { ExtJson.decodeFromString(TokenAnswer.serializer(), text) }.getOrElse { return Poll.Failed("unreadable answer") }
        return when {
            !a.token.isNullOrBlank() -> Poll.Token(a.token)
            a.error == "authorization_pending" -> Poll.Pending
            a.error == "slow_down" -> Poll.SlowDown
            else -> Poll.Failed(a.description ?: a.error ?: "no token")
        }
    }

    /** Where a 👍 or 👎 is posted (and read back) on [repo]'s ratings issue [issue]. */
    fun reactionsUrl(repo: String, issue: Int) = "https://api.github.com/repos/$repo/issues/$issue/reactions"

    fun reactionBody(up: Boolean) = """{"content":"${if (up) "+1" else "-1"}"}"""
}
