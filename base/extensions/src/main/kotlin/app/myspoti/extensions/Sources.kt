package app.myspoti.extensions

import kotlinx.serialization.Serializable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.zip.ZipInputStream

/** The code hosts an extension can be installed from. */
@Serializable
enum class CodeHost(val key: String, val domain: String, val label: String) {
    GITHUB("github", "github.com", "GitHub"),
    GITLAB("gitlab", "gitlab.com", "GitLab"),
    CODEBERG("codeberg", "codeberg.org", "Codeberg");

    companion object {
        fun ofDomain(d: String): CodeHost? = entries.firstOrNull { it.domain == d.lowercase().removePrefix("www.") }
    }
}

/**
 * A repository an extension is installed from, on [host]: [owner] and [name] (GitLab's owner may
 * be a group path, `group/sub`). Everything is fetched from the host's file addresses (GitHub:
 * `raw.githubusercontent.com`, `codeload.github.com`, release downloads; GitLab: `/-/raw`,
 * `/-/archive`; Codeberg: `/raw`, `/archive`), never from an API, except the store's search (see
 * [Discovery]), once per visit and cached. The [slug] is `owner/name` on GitHub (as it always was),
 * `gitlab:group/name` or `codeberg:owner/name` elsewhere. (Named after GitHub, where it began.)
 */
@Serializable
data class GitHubRepo(val owner: String, val name: String, val host: CodeHost = CodeHost.GITHUB) {
    val slug: String get() = if (host == CodeHost.GITHUB) "$owner/$name" else "${host.key}:$owner/$name"

    private val base: String get() = "https://${host.domain}/$owner/$name"

    /** A file of the repository at [ref] (a tag, a branch, or `HEAD` for the default branch). */
    fun raw(ref: String, path: String) = when (host) {
        CodeHost.GITHUB -> "https://raw.githubusercontent.com/$owner/$name/$ref/$path"
        CodeHost.GITLAB -> "$base/-/raw/$ref/$path"
        CodeHost.CODEBERG -> "$base/raw/$ref/$path"
    }

    /** The whole repository at [ref], zipped: a tag when [tag], else a branch (`HEAD`: the default one). */
    fun archive(ref: String, tag: Boolean) = when (host) {
        CodeHost.GITHUB ->
            if (ref == HEAD) "https://codeload.github.com/$owner/$name/zip/HEAD"
            else "https://codeload.github.com/$owner/$name/zip/refs/${if (tag) "tags" else "heads"}/$ref"
        CodeHost.GITLAB -> "$base/-/archive/$ref/$name-${ref.replace('/', '-')}.zip"
        CodeHost.CODEBERG -> "$base/archive/$ref.zip"
    }

    /** Redirects to the newest release's page (`…/releases/tag/<tag>`); 404 without a release. */
    val latestRelease: String get() = when (host) {
        CodeHost.GITLAB -> "$base/-/releases/permalink/latest"
        else -> "$base/releases/latest"
    }

    /** The package attached to the release [tag], when the author attached one ([GitHub.ASSET]); GitLab's release links are not looked for. */
    fun releaseAsset(tag: String): String? = when (host) {
        CodeHost.GITLAB -> null
        else -> "$base/releases/download/$tag/${GitHub.ASSET}"
    }

    val page: String get() = base

    /** Codeberg's file addresses want a branch name: its API tells the default one. */
    val defaultBranchApi: String? get() = if (host == CodeHost.CODEBERG) "https://codeberg.org/api/v1/repos/$owner/$name" else null

    companion object {
        const val HEAD = "HEAD"
        private val PART = Regex("^[A-Za-z0-9_.-]{1,100}$")

        /** A repository by its host's path (`group/sub/name`); null when it is not one. */
        fun of(host: CodeHost, path: String): GitHubRepo? {
            val parts = path.trim('/').split('/').map { it.removeSuffix(".git") }
            if (parts.size < 2 || parts.any { !PART.matches(it) || it == "." || it == ".." }) return null
            val usable = if (host == CodeHost.GITLAB) parts else parts.take(2)
            return GitHubRepo(usable.dropLast(1).joinToString("/"), usable.last(), host)
        }

        /**
         * `owner/name` (GitHub), `gitlab:group/name`, `codeberg:owner/name`, `github.com/owner/name`,
         * `https://gitlab.com/group/sub/name(.git)(/-/…)`, `https://codeberg.org/owner/name(/…)`; null otherwise.
         */
        fun parse(text: String): GitHubRepo? {
            var s = text.trim().removeSuffix("/")
            CodeHost.entries.firstOrNull { s.startsWith(it.key + ":") }?.let { return of(it, s.removePrefix(it.key + ":")) }
            s = s.removePrefix("https://").removePrefix("http://").removePrefix("www.")
            val host = CodeHost.ofDomain(s.substringBefore('/'))
            if (host != null && s.contains('/')) {
                var path = s.substringAfter('/').substringBefore('?').substringBefore('#')
                if (host == CodeHost.GITLAB) path = path.substringBefore("/-/")
                return of(host, path)
            }
            if (s.contains("://") || s.contains('.') && s.substringBefore('/').contains('.')) return null
            val parts = s.split('/')
            if (parts.size < 2) return null
            return of(CodeHost.GITHUB, parts[0] + "/" + parts[1])
        }
    }
}

/**
 * Where an installed extension came from on GitHub: [repo] (`owner/name`), the [ref] read (a
 * release tag when [tag], else a branch or `HEAD`), the [commit] when GitHub gave it (in the
 * archive), and whether the package was the release's own [asset] or built from the archive.
 */
@Serializable
data class Origin(
    val repo: String,
    val ref: String = GitHubRepo.HEAD,
    val tag: Boolean = false,
    val commit: String? = null,
    val asset: Boolean = false,
    /** The host's numeric ids of the repository and its owner, pinned when installed (null: not read yet). */
    val repoId: String? = null,
    val ownerId: String? = null,
) {
    fun gitHub(): GitHubRepo? = GitHubRepo.parse(repo)

    /** The repository's page on its host. */
    fun page(): String? = gitHub()?.page

    /** "v1.2.0 · 3f2a9c1" for the details. */
    fun label(): String = listOfNotNull(ref.takeIf { it != GitHubRepo.HEAD }, commit?.take(7)).joinToString(" · ").ifEmpty { ref }
}

/** The constants shared by the app and the tools: the official repository, the package's name in a release. */
object GitHub {
    /** The official repository: its `blocked.json` turns extensions off everywhere, the store's reports go to it. */
    const val OFFICIAL_REPO = "kota-zo/myspoti-extensions"

    /** The kill switch, read once a day; a 404 (not created yet) is an empty list. */
    const val BLOCKED_URL = "https://raw.githubusercontent.com/$OFFICIAL_REPO/main/blocked.json"

    /** The package an author attaches to a release, when they do: else the release's archive is read. */
    const val ASSET = "extension.zip"

    /** At most this much of a repository's archive is read (it is cut down to the package after). */
    const val MAX_ARCHIVE = 8 * 1024 * 1024

    /** The tag a `releases/latest` redirect points to (`…/releases/tag/v1.2.0` → `v1.2.0`); null otherwise. */
    fun tagFromLocation(location: String?): String? {
        val l = location ?: return null
        // GitHub and Codeberg: …/releases/tag/<tag>; GitLab: …/-/releases/<tag>.
        val marker = listOf("/releases/tag/", "/-/releases/").firstOrNull { l.contains(it) } ?: return null
        val tag = java.net.URLDecoder.decode(l.substring(l.indexOf(marker) + marker.length).substringBefore('?').substringBefore('#'), "UTF-8")
        return tag.takeIf { it.isNotBlank() && it != "permalink" && !it.startsWith("permalink/") }
    }

    /**
     * The repository a GitHub address is in (`github.com/o/r/…`, `codeload.github.com/o/r/…`,
     * `raw.githubusercontent.com/o/r/…`), as `owner/name`: where a download really came from,
     * after GitHub's redirects (a repository renamed or moved to another owner); null otherwise.
     */
    fun repoOf(url: String?): String? {
        val u = runCatching { java.net.URI(url ?: return null) }.getOrNull() ?: return null
        if (u.host == "gitlab.com" || u.host == "codeberg.org") return GitHubRepo.parse("https://${u.host}${u.path.orEmpty()}")?.slug
        if (u.host !in setOf("github.com", "codeload.github.com", "raw.githubusercontent.com")) return null
        val parts = u.path.orEmpty().trim('/').split('/')
        if (parts.size < 2) return null
        return GitHubRepo.parse(parts[0] + "/" + parts[1])?.slug
    }

    /**
     * A prefilled "report an extension" form on the official repository, opened in the browser:
     * no account in the app, nothing about the user in it (the extension's id, version, source
     * and package hash only; the reporter writes the rest). The ids are those of the fields of
     * the repository's `report-extension.yml` issue form.
     */
    fun reportUrl(id: String, version: String, repo: String?, sha256: String?): String = buildString {
        append("https://github.com/").append(OFFICIAL_REPO).append("/issues/new?template=report-extension.yml&labels=report")
        append("&title=").append(enc("Report: $id"))
        append("&extension=").append(enc(id)).append("&version=").append(enc(version))
        repo?.let { append("&source=").append(enc(GitHubRepo.parse(it)?.page ?: "https://github.com/$it")) }
        sha256?.let { append("&sha256=").append(enc(it)) }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

/**
 * A repository's zipped archive (`codeload.github.com/…/zip/…`), as an extension package: the
 * files at the root of the archive's single top folder, cut down to what a package holds
 * ([ExtensionPackage.packFiles]). GitHub writes the commit in the archive's comment.
 */
object Archive {
    private const val MAX_ENTRIES = 3000

    /** A downloaded zip as a package: as is when it is one (manifest at its root), else cut from an archive. */
    fun toPackage(zip: ByteArray): ByteArray = if (rootNames(zip).contains("manifest.json")) zip else fromArchive(zip)

    /** The package built from a repository archive's root files. */
    fun fromArchive(zip: ByteArray): ByteArray {
        if (zip.size > GitHub.MAX_ARCHIVE) throw ExtensionException("repository archive larger than ${GitHub.MAX_ARCHIVE / 1024 / 1024} MB")
        val files = HashMap<String, ByteArray>()
        var top: String? = null
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            var count = 0
            while (true) {
                val e = z.nextEntry ?: break
                if (++count > MAX_ENTRIES) throw ExtensionException("repository archive has more than $MAX_ENTRIES files")
                val name = e.name
                if (name.contains("..") || name.startsWith("/") || name.contains('\\')) throw ExtensionException("archive path not allowed: $name")
                val first = name.substringBefore('/')
                if (top == null) top = first else if (top != first) throw ExtensionException("not a GitHub archive (several top folders)")
                val rest = name.substringAfter('/', "")
                if (e.isDirectory || rest.isEmpty() || rest.contains('/')) continue
                files[rest] = z.readUpTo(ExtensionPackage.MAX_SCRIPT + 1)
            }
        }
        return ExtensionPackage.packFiles(files)
    }

    /** The commit GitHub wrote in an archive's comment (40 hex digits), or null. */
    fun commit(zip: ByteArray): String? = comment(zip)?.trim()?.takeIf { COMMIT.matches(it) }

    /** The zip's comment, from its end-of-central-directory record. */
    fun comment(zip: ByteArray): String? {
        // The record is 22 bytes plus a comment of at most 65535: look for its signature from the end.
        val stop = maxOf(0, zip.size - 22 - 0xFFFF)
        for (i in zip.size - 22 downTo stop) {
            if (zip[i] == 0x50.toByte() && zip[i + 1] == 0x4b.toByte() && zip[i + 2] == 0x05.toByte() && zip[i + 3] == 0x06.toByte()) {
                val len = (zip[i + 20].toInt() and 0xff) or ((zip[i + 21].toInt() and 0xff) shl 8)
                if (i + 22 + len > zip.size) return null
                return String(zip, i + 22, len, Charsets.UTF_8)
            }
        }
        return null
    }

    private val COMMIT = Regex("^[0-9a-f]{40}$")

    private fun rootNames(zip: ByteArray): Set<String> = runCatching {
        buildSet { ZipInputStream(ByteArrayInputStream(zip)).use { z -> var n = 0; while (n++ < MAX_ENTRIES) add((z.nextEntry ?: break).name) } }
    }.getOrDefault(emptySet())

    private fun ZipInputStream.readUpTo(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > limit) break
        }
        return out.toByteArray()
    }
}
