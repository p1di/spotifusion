package com.music.spotui.ui.viewmodel

import com.music.spotui.di.Graph
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.SearchResults
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.CurrentSongState
import com.music.spotui.ui.repository.AppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SearchViewModel constructor(private val repository: AppRepository = Graph.repository, private val currentSongState: CurrentSongState = Graph.currentSongState) : ViewModel() {

    private val _songs : MutableStateFlow<Response<List<SongsModel>>> = MutableStateFlow(Response.Loading())
    val songs : StateFlow<Response<List<SongsModel>>> = _songs

    private val _results : MutableStateFlow<Response<SearchResults>> = MutableStateFlow(Response.Success(SearchResults()))
    val results : StateFlow<Response<SearchResults>> = _results

    val likeState = currentSongState.likeState

    val currentSongId: State<Int> get() = currentSongState.songId


    fun updateLikeState(likeState : Boolean){
        currentSongState.updateLikeState(likeState)
    }

    private var searchJob: Job? = null

    /** What is typed and the chip picked: kept here so they are still there back from a result. */
    var query by androidx.compose.runtime.mutableStateOf("")
    var filter by androidx.compose.runtime.mutableStateOf(com.music.spotui.ui.screens.SearchFilter.Top)

    /** Runs the last search again (after an error). */
    fun retry() = search(query)

    init {
        // Start empty — results come from live searches, not the rate-limited
        // personalized top-tracks feed (which returned 429 and blanked the screen).
        _songs.value = Response.Success(emptyList())
    }

    fun updateQueue(songs: List<SongsModel>) = currentSongState.updateQueue(songs)

    /**
     * Start playback of a single search result as a *radio*, the way Spotify does:
     * the queue becomes just this track, then Spotify-recommended tracks (seeded from
     * it) are appended as they load — instead of queuing the rest of the search list
     * (which made playback "just go down the search results"). The append is skipped
     * if the user has since started something else.
     */
    fun startRadioFromSong(song: SongsModel) {
        currentSongState.updateQueue(listOf(song))
        val seed = song.spotifyTrackId
        if (seed.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val recs = repository.provideRecommendations(listOf(seed))
            val current = currentSongState.queue.value
            // Only extend if this radio is still the active queue (user didn't tap away).
            if (current.size == 1 && current.first().id == song.id) {
                val fresh = recs.filter { it.id != song.id }
                if (fresh.isNotEmpty()) currentSongState.updateQueue(current + fresh)
            }
        }
    }

    fun updateSongState(coverUri: String, title: String, singer: String, playingState: Boolean, songId : Int, songIndex : Int = 0, album : String = "") {
        currentSongState.updateSongState(coverUri, title, singer, playingState, songId, songIndex, album)
    }

    fun search(query: String) {
        this.query = query
        searchJob?.cancel()
        // Typed again lately (back to a shorter text, a retyped word): the results of then, at once.
        RecentSearches.get(query)?.let { cached ->
            _results.value = Response.Success(cached)
            _songs.value = Response.Success(cached.songs)
            return
        }
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(300) // the debounce: a result per pause in typing, not per letter
            var last: SearchResults? = null
            repository.searchEverything(query).collect { result ->
                // While the next results load, the last ones stay on screen.
                val keep = result is Response.Loading && (_results.value as? Response.Success)?.data?.let { it != SearchResults() } == true
                if (!keep) _results.value = result
                if (result is Response.Success) last = result.data
                // Keep the legacy songs flow in sync for any remaining consumers.
                _songs.value = when (result) {
                    is Response.Success -> Response.Success(result.data.songs)
                    is Response.Error -> Response.Error(result.error)
                    is Response.Loading -> Response.Loading()
                }
            }
            // Only a search that ran to its end (not one cut by the next letter, nor an error) is remembered.
            last?.let { RecentSearches.put(query, it) }
        }
    }

    /** The last searches by their text, for a few minutes; shared by every instance. */
    private object RecentSearches {
        private const val KEEP = 20
        private const val FRESH_MS = 3 * 60_000L
        private val map = object : LinkedHashMap<String, Pair<Long, SearchResults>>(24, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, SearchResults>>) = size > KEEP
        }

        @Synchronized fun get(query: String): SearchResults? =
            map[key(query)]?.takeIf { System.currentTimeMillis() - it.first < FRESH_MS }?.second

        @Synchronized fun put(query: String, results: SearchResults) {
            if (results != SearchResults()) map[key(query)] = System.currentTimeMillis() to results
        }

        private fun key(query: String) = query.trim().lowercase()
    }
}
