package com.music.spotui.data.local

import com.music.spotui.data.entity.ArtistsModel
import com.music.spotui.data.entity.LibraryEntry

/**
 * The chips under "Your Library" (Playlists, Artists, Albums, Songs), like the official app's
 * filter row: one at a time, tapping the chosen one again shows everything.
 */
enum class LibraryChip { ALL, PLAYLISTS, ARTISTS, ALBUMS, SONGS }

object LibraryChips {
    /**
     * [songIds]: the library ids of the rows that hold songs rather than a playlist (Liked Songs,
     * Local Files, Downloaded). Folders count as playlists, as they sit among them in Spotify.
     * Artists are a separate list, so only the Artists chip keeps them.
     */
    fun apply(
        chip: LibraryChip,
        entries: List<LibraryEntry>,
        artists: List<ArtistsModel>,
        songIds: Set<String>,
    ): Pair<List<LibraryEntry>, List<ArtistsModel>> = when (chip) {
        LibraryChip.ALL -> entries to artists
        LibraryChip.ARTISTS -> emptyList<LibraryEntry>() to artists
        LibraryChip.SONGS -> entries.filter { it.spotifyId in songIds } to emptyList()
        LibraryChip.PLAYLISTS -> entries.filter { it.spotifyId !in songIds && (it.isPlaylist || it.isFolder) } to emptyList()
        LibraryChip.ALBUMS -> entries.filter { it.spotifyId !in songIds && !it.isPlaylist && !it.isFolder } to emptyList()
    }

    /** A tap on [tapped] while [current] is chosen. */
    fun toggle(current: LibraryChip, tapped: LibraryChip): LibraryChip = if (current == tapped) LibraryChip.ALL else tapped
}
