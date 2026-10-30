package app.myspoti.extensions

import kotlinx.serialization.Serializable

/**
 * What the user accepted of an extension, kept with it: its actions with their reasons and
 * hosts, its exclusive roles, what it replaces or conflicts with, its author and source. An
 * update is always weighed against this, the last snapshot the user accepted (at install, or by
 * tapping Accept), never against the version running: an update that silently drops an action
 * and a later one that adds it back with another reason are both seen for what they ask.
 */
@Serializable
data class Snapshot(
    val id: String,
    val version: String,
    val versionCode: Int,
    val author: String,
    /** `owner/name` on GitHub; null for a package from a file or an address. */
    val repo: String? = null,
    /** [Manifest.declared]: each action with its why and, for network, its hosts. */
    val actions: List<ActionRequest> = emptyList(),
    /** The [Mode.EXCLUSIVE] actions it takes (one extension at a time acts for each). */
    val exclusive: List<String> = emptyList(),
    val replaces: List<String> = emptyList(),
    val conflictsWith: List<String> = emptyList(),
    /** sha256 of the main script the user accepted (null: not recorded yet, pinned on the next check). */
    val scriptSha: String? = null,
    /** The host's numeric id of the repository and of its owner, pinned at install (null: not pinned yet). */
    val repoId: String? = null,
    val ownerId: String? = null,
) {
    /**
     * Whether code run under these rights is dangerous enough that new code needs the user's OK:
     * any critical (or unknown) action, or network access to any host / a wildcard of hosts.
     */
    fun sensitive(): Boolean = actions.any { r ->
        val a = Action.of(r.name)
        a == null || a.level == Level.CRITICAL || (a == Action.NETWORK && r.hosts.any { it == "*" || it.startsWith("*.") })
    }

    fun hosts(): List<String> = actions.firstOrNull { it.name == Action.NETWORK.key }?.hosts.orEmpty()

    /** The Spotify writes accepted, as grants ([Manifest.declaredWrites]). */
    fun writes(): List<String> = toManifest().declaredWrites().map { it.toString() }

    /** A manifest holding what was accepted, for [app.myspoti.extensions.Manifest.asks]-based comparisons. */
    fun toManifest() = Manifest(id, id, version, versionCode, author = author, actions = actions, replaces = replaces, conflictsWith = conflictsWith)

    companion object {
        fun of(m: Manifest, repo: String?, script: String? = null, origin: Origin? = null) = Snapshot(
            id = m.id, version = m.version, versionCode = m.versionCode, author = m.author, repo = repo,
            actions = m.declared(), exclusive = m.actionSet().filter { it.mode == Mode.EXCLUSIVE }.map { it.key },
            replaces = m.replaces, conflictsWith = m.conflictsWith,
            scriptSha = script?.let(::scriptHash), repoId = origin?.repoId, ownerId = origin?.ownerId,
        )

        fun scriptHash(script: String): String = ExtensionPackage.sha256(script.toByteArray(Charsets.UTF_8))
    }
}

/**
 * One thing an update asks that the user has not accepted, as the "Update needs your OK" card
 * lists it: [key] is the action key (or the host, or the new repo/author/id), [why] the
 * author's new reason, [before] what the user accepted (the old reason, repo or author).
 */
@Serializable
data class Change(
    val kind: Kind, val key: String, val why: String = "", val before: String = "", val critical: Boolean = false,
    /** [Kind.CODE]: lines added and removed ([key] lists the files changed). */
    val added: Int = 0, val removed: Int = 0,
) {
    enum class Kind {
        /** The code changed, asking nothing new, in a package that holds critical or broad network rights. */
        CODE,
        /** An action not accepted before (a critical one, `webview.page` and exclusive roles included). */
        ACTION,
        /** A network host not accepted before (`*`: any host, critical). */
        HOST,
        /** A Spotify write declared under spotify.raw not accepted before (allowed with the update: critical). */
        WRITE,
        /** An exclusive role taken by an action already accepted (the app made it exclusive since). */
        EXCLUSIVE,
        /** An accepted action whose reason changed: any change counts, the user judges the meaning. */
        WHY,
        /** It now comes from another repository. */
        REPO,
        /** Another author. */
        AUTHOR,
        /** It now turns off another extension when installed. */
        REPLACES,
        /** It now refuses to run with another extension. */
        CONFLICTS,
    }

    /** The action concerned, for its icon. */
    val action: Action? get() = when (kind) {
        Kind.ACTION, Kind.EXCLUSIVE, Kind.WHY -> Action.of(key)
        Kind.HOST -> Action.NETWORK
        Kind.WRITE -> Action.SPOTIFY_RAW
        else -> null
    }
}

/** How much of the code changed between two packages: lines added / removed and the files concerned. */
data class CodeDiff(val added: Int, val removed: Int, val files: List<String>) {
    val lines: Int get() = added + removed

    companion object {
        /** [old] and [new]: file name to text. Lines are compared as multisets (cheap, order-blind: a count, not a patch). */
        fun of(old: Map<String, String>, new: Map<String, String>): CodeDiff {
            var added = 0
            var removed = 0
            val files = (old.keys + new.keys).sorted().filter { old[it] != new[it] }
            files.forEach { f ->
                val a = HashMap<String, Int>()
                old[f]?.lines().orEmpty().forEach { a.merge(it, 1, Int::plus) }
                new[f]?.lines().orEmpty().forEach { l ->
                    val n = a[l] ?: 0
                    if (n > 0) a[l] = n - 1 else added++
                }
                removed += a.values.sum()
            }
            return CodeDiff(added, removed, files)
        }
    }
}

/**
 * Whether an update found on a code host is applied by itself, waits, waits for the user's OK, or
 * is refused: a repository whose host id changed (re-registered or transferred under the same
 * name) is never updated from; a code change in a package holding critical or broad network rights
 * asks; anything else applies after [DELAY_MS] from when it was first seen (the kill switch can
 * act meanwhile) unless the user asked for it.
 */
object UpdateGate {
    const val DELAY_MS = 24 * 3600 * 1000L

    sealed interface Decision {
        /** It asks nothing new: applied, the previous version kept to revert to. */
        data object Apply : Decision
        /** It asks [changes]: the old version keeps running, the user reads the card. */
        data class Ask(val changes: List<Change>) : Decision
        /** The repository is not the one installed from ([reason] says how): never applied, the user is warned. */
        data class Block(val reason: String) : Decision
        /** Harmless, but seen less than a day ago: applied at [until] (epoch ms). */
        data class Wait(val until: Long) : Decision
    }

    /**
     * [code]: how the code differs (null: not known). [firstSeen]: when this very package was first
     * seen (null: no delay, the user asked for the update); [now] in ms.
     */
    fun decide(accepted: Snapshot, next: Snapshot, code: CodeDiff? = null, firstSeen: Long? = null, now: Long = 0): Decision {
        identityChange(accepted, next)?.let { return Decision.Block(it) }
        val changes = diff(accepted, next).toMutableList()
        val codeChanged = accepted.scriptSha != null && next.scriptSha != null && accepted.scriptSha != next.scriptSha
        if (codeChanged && (accepted.sensitive() || next.sensitive()))
            changes += Change(Change.Kind.CODE, code?.files.orEmpty().joinToString(", "), critical = true, added = code?.added ?: 0, removed = code?.removed ?: 0)
        if (changes.isNotEmpty()) return Decision.Ask(changes)
        if (firstSeen != null && now - firstSeen < DELAY_MS) return Decision.Wait(firstSeen + DELAY_MS)
        return Decision.Apply
    }

    /** Why [next] cannot come from the repository [accepted] was installed from (different host ids), or null. */
    fun identityChange(accepted: Snapshot, next: Snapshot): String? = when {
        accepted.repoId != null && next.repoId != null && accepted.repoId != next.repoId -> "repository id ${accepted.repoId} → ${next.repoId}"
        accepted.ownerId != null && next.ownerId != null && accepted.ownerId != next.ownerId -> "owner id ${accepted.ownerId} → ${next.ownerId}"
        else -> null
    }

    /**
     * Everything [next] asks beyond [accepted]: new actions (critical, page and exclusive ones
     * included), new hosts, exclusive roles, reasons that changed, another repository or author,
     * new replacements and conflicts. What it drops is not a change: it asks less.
     */
    fun diff(accepted: Snapshot, next: Snapshot): List<Change> = buildList {
        if (next.id != accepted.id) add(Change(Change.Kind.REPO, next.id, before = accepted.id, critical = true))
        if (next.repo != accepted.repo && accepted.repo != null) add(Change(Change.Kind.REPO, next.repo.orEmpty(), before = accepted.repo, critical = true))
        if (next.author.trim() != accepted.author.trim()) add(Change(Change.Kind.AUTHOR, next.author, before = accepted.author, critical = true))
        val was = accepted.actions.associateBy { it.name }
        next.actions.forEach { r ->
            val a = Action.of(r.name)
            val old = was[r.name]
            when {
                old == null -> add(Change(Change.Kind.ACTION, r.name, r.why, critical = a == null || a.level == Level.CRITICAL || (a == Action.NETWORK && "*" in r.hosts)))
                old.why.trim() != r.why.trim() -> add(Change(Change.Kind.WHY, r.name, r.why, before = old.why, critical = a?.level == Level.CRITICAL))
            }
        }
        val hostsBefore = accepted.hosts().toSet()
        next.hosts().filter { it !in hostsBefore }.forEach { add(Change(Change.Kind.HOST, it, critical = it == "*")) }
        val writesBefore = accepted.writes().toSet()
        next.writes().filter { it !in writesBefore }.forEach { add(Change(Change.Kind.WRITE, it, critical = true)) }
        val newActions = next.actions.map { it.name }.filter { it !in was }.toSet()
        next.exclusive.filter { it !in accepted.exclusive && it !in newActions }.forEach { add(Change(Change.Kind.EXCLUSIVE, it, critical = true)) }
        next.replaces.filter { it !in accepted.replaces }.forEach { add(Change(Change.Kind.REPLACES, it)) }
        next.conflictsWith.filter { it !in accepted.conflictsWith }.forEach { add(Change(Change.Kind.CONFLICTS, it)) }
    }
}

/**
 * The official repository's `blocked.json` ([GitHub.BLOCKED_URL]), read once a day: [blocked]
 * extensions (all versions, or the packages listed) are turned off on every phone with a notice;
 * [flagged] ones (reported and confirmed) stay installed but are hidden from the store behind
 * "Show all". A missing file is an empty list.
 *
 * `{ "blocked": [{ "id": "…", "sha256": ["…"], "reason": "…" }], "flagged": [{ "id": "…", "reason": "…" }] }`
 */
@Serializable
data class BlockList(val blocked: List<Catalog.Block> = emptyList(), val flagged: List<Flag> = emptyList()) {
    @Serializable
    data class Flag(val id: String, val reason: String = "")

    /** Whether [id] in the package [sha256] is to be turned off (an entry without hashes: every package). */
    fun blocks(id: String, sha256: String?): Catalog.Block? =
        blocked.firstOrNull { b -> b.id == id && (b.sha256.isEmpty() || b.sha256.any { it.equals(sha256, ignoreCase = true) }) }

    fun flags(id: String): Flag? = flagged.firstOrNull { it.id == id }

    companion object {
        val EMPTY = BlockList()

        fun parse(text: String): BlockList = if (text.isBlank()) EMPTY else ExtJson.decodeFromString(serializer(), text)

        /** The body of an answer: a 404 (the file does not exist yet) is an empty list, other failures are thrown by the caller. */
        fun of(status: Int, text: String?): BlockList = if (status == 404) EMPTY else parse(text.orEmpty())
    }
}
