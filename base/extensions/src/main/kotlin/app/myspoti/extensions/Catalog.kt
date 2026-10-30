package app.myspoti.extensions

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A repository's `index.json`: the extensions it offers, and the ones it asks every Myspoti to
 * turn off. The official repository is reviewed (each extension is added by a pull request);
 * another one is added by the user, at their own risk.
 *
 * An entry's store fields (`icon_url`, `updated_at`, `category`, `tags`, `min_app_version`,
 * `changelog`) are optional: an index without them is read as before, and an older Myspoti
 * ignores them. Relative addresses (`url`, `icon_url`) are resolved against the index's own.
 */
@Serializable
data class Catalog(
    val format: Int = 1,
    val name: String,
    val extensions: List<Entry> = emptyList(),
    val blocked: List<Block> = emptyList(),
) {
    @Serializable
    data class Entry(
        val id: String,
        val name: String,
        val version: String,
        val versionCode: Int,
        val apiVersion: Int = API_VERSION,
        val author: String,
        val description: String = "",
        /** The manifest's actions, so the store shows their icons and the risk before downloading. */
        val actions: List<ActionRequest> = emptyList(),
        /** Old format, still read: the manifest's hooks. */
        val provides: List<String> = emptyList(),
        /** Old format, still read: the manifest's permissions. */
        val permissions: List<PermissionRequest> = emptyList(),
        /** The package, absolute or relative to the index. */
        val url: String,
        /** Of the package's bytes: checked before anything of it is read (empty: not known, GitHub discovery). */
        val sha256: String,
        val size: Long = 0,
        /** The older name of [iconUrl], still read. */
        val icon: String? = null,
        val homepage: String? = null,
        /** The icon shown in the store before download: a PNG, absolute or relative to the index. */
        @SerialName("icon_url") val iconUrl: String? = null,
        /** When this version was listed, ISO 8601 (`2026-10-23` or a full timestamp). */
        @SerialName("updated_at") val updatedAt: String? = null,
        /** A [Category] key; absent, derived from [actions]. */
        val category: String? = null,
        val tags: List<String> = emptyList(),
        /** The oldest Myspoti this version works on: see [Manifest.minAppVersion]. */
        @SerialName("min_app_version") val minAppVersion: String? = null,
        /** What changed in this version. */
        val changelog: String = "",
        /** The manifest's `replaces` and `conflictsWith`, so the store warns before download. */
        val replaces: List<String> = emptyList(),
        @SerialName("conflicts_with") val conflictsWith: List<String> = emptyList(),
        /**
         * `owner/name` on GitHub: installed from there ([GitHubRepo]: the release's package, else
         * the archive), kept on the phone, and updated from there. The index's [sha256], when
         * given (not empty), is checked against the package.
         */
        val repo: String? = null,
    ) {
        /** The manifest the entry describes, for the risk shown before download. */
        fun preview() = Manifest(
            id, name, version, versionCode, apiVersion, author, description, homepage, actions = actions, provides = provides, permissions = permissions,
            category = category, tags = tags, minAppVersion = minAppVersion, changelog = changelog,
            replaces = replaces, conflictsWith = conflictsWith,
        )

        /** The icon's address as written in the index ([iconUrl], else the older [icon]). */
        fun iconAddress(): String? = (iconUrl ?: icon)?.takeIf { it.isNotBlank() }

        fun category(): Category = Category.of(category, preview().actionSet())

        /** Whether this Myspoti ([appVersion]) can install it: its API and its minimum version. */
        fun compatible(appVersion: String): Boolean = apiVersion <= API_VERSION && AppVersion.atLeast(appVersion, minAppVersion)
    }

    /** An extension every Myspoti turns off: all of its versions, or the packages listed. */
    @Serializable
    data class Block(val id: String, val sha256: List<String> = emptyList(), val reason: String = "")

    /** Whether this repository asks to turn off [id] in the package [sha256]. */
    fun blocks(id: String, sha256: String?): Block? = blocked.firstOrNull { b -> b.id == id && (b.sha256.isEmpty() || sha256 in b.sha256) }

    companion object {
        fun parse(text: String): Catalog {
            val c = ExtJson.decodeFromString(serializer(), text)
            if (c.format != 1) throw ExtensionException("index format ${c.format}: this Myspoti reads format 1")
            return c
        }

        private val pretty = kotlinx.serialization.json.Json { prettyPrint = true; encodeDefaults = false; explicitNulls = false }

        fun encode(c: Catalog): String = pretty.encodeToString(serializer(), c)

        fun encodeEntry(e: Entry): String = pretty.encodeToString(Entry.serializer(), e)

        /** [url] of an entry, resolved against the index's own address. */
        fun resolve(indexUrl: String, url: String): String = java.net.URI(indexUrl).resolve(url).toString()
    }
}
