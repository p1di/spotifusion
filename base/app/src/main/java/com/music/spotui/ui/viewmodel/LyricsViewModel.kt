package com.music.spotui.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.spotui.data.api.LyricTranslate
import com.music.spotui.data.api.LyricsApi
import com.music.spotui.data.entity.Lyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LyricsViewModel constructor() : ViewModel() {

    sealed class State {
        object Loading : State()
        data class Loaded(
            val lyrics: Lyrics,
            val showTranslation: Boolean = false,
            val translations: List<String>? = null,
            val translationLanguage: String? = null,
            val sourceLanguage: String? = null,
            val translating: Boolean = false,
            val translationChecked: Boolean = false,
            val translationRequested: Boolean = false,
        ) : State()
        object NotFound : State()
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state

    private var loadedKey: String? = null

    fun load(track: LyricsApi.Track, force: Boolean = false) {
        val key = track.key
        if (!force && loadedKey == key && _state.value !is State.NotFound) return
        loadedKey = key
        if (force) LyricsApi.forget(track)
        // Already resolved (prefetched while the track started): no loading flash.
        LyricsApi.cached(track)?.let { _state.value = loadedState(it); return }
        // Read again (the setting changed): the lyrics shown stay until the new ones replace them.
        if (!(force && _state.value is State.Loaded)) _state.value = State.Loading
        // Spotify's own lines shown as soon as they are there; the word-timed ones replace them.
        val earlyJob = viewModelScope.launch {
            LyricsApi.early.collect { e ->
                if (e != null && e.first == key && loadedKey == key && _state.value is State.Loading) _state.value = loadedState(e.second)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val lyrics = LyricsApi.fetch(track)
            earlyJob.cancel()
            withContext(Dispatchers.Main) {
                // A skip during the fetch: don't show the previous song's lyrics.
                if (loadedKey != key) return@withContext
                val shown = (_state.value as? State.Loaded)?.lyrics
                _state.value = when {
                    lyrics == null || lyrics.isEmpty -> if (shown != null) _state.value else State.NotFound
                    shown == lyrics -> _state.value
                    else -> loadedState(lyrics)
                }
            }
        }
    }

    private fun loadedState(lyrics: com.music.spotui.data.entity.Lyrics) = State.Loaded(
        lyrics = lyrics,
        sourceLanguage = lyrics.language,
        translationChecked = lyrics.language != null,
    )

    fun toggleTranslation(targetLang: String) {
        val cur = _state.value as? State.Loaded ?: return
        if (cur.showTranslation) {
            _state.value = cur.copy(showTranslation = false)
            return
        }
        if (cur.translationLanguage == targetLang) {
            _state.value = cur.copy(showTranslation = true)
            return
        }
        if (cur.translating) {
            _state.value = cur.copy(translationRequested = true)
            return
        }
        translate(cur, targetLang, showWhenDone = true)
    }

    fun prepareTranslation(targetLang: String) {
        val cur = _state.value as? State.Loaded ?: return
        if (cur.translating || cur.translationLanguage == targetLang) return
        if (sameLanguage(cur.sourceLanguage, targetLang)) return
        translate(cur, targetLang, showWhenDone = false)
    }

    private fun translate(cur: State.Loaded, targetLang: String, showWhenDone: Boolean) {
        _state.value = cur.copy(
            translating = true,
            translationRequested = showWhenDone,
        )
        viewModelScope.launch(Dispatchers.IO) {
            val lines = cur.lyrics.lines.map { it.text }
            val detectedLanguage = cur.sourceLanguage
                ?: LyricTranslate.detectLanguage(lines, targetLang)
            if (detectedLanguage != null) {
                val matchesTarget = sameLanguage(detectedLanguage, targetLang)
                withContext(Dispatchers.Main) {
                    val latest = _state.value as? State.Loaded ?: return@withContext
                    if (latest.lyrics == cur.lyrics) {
                        _state.value = latest.copy(
                            translationChecked = true,
                            sourceLanguage = detectedLanguage,
                            translating = !matchesTarget,
                            translationRequested = latest.translationRequested && !matchesTarget,
                        )
                    }
                }
                if (matchesTarget) return@launch
            }
            val result = LyricTranslate.translateLines(lines, targetLang)
            withContext(Dispatchers.Main) {
                val latest = _state.value as? State.Loaded ?: return@withContext
                if (latest.lyrics != cur.lyrics) return@withContext
                if (result == null) {
                    _state.value = latest.copy(
                        translating = false,
                        translationChecked = true,
                        showTranslation = false,
                        translationRequested = false,
                    )
                    return@withContext
                }
                _state.value = latest.copy(
                    translating = false,
                    translationChecked = true,
                    showTranslation = latest.translationRequested &&
                        !sameLanguage(result.sourceLanguage, targetLang),
                    translationRequested = false,
                    translations = result.lines,
                    translationLanguage = targetLang,
                    sourceLanguage = result.sourceLanguage,
                )
            }
        }
    }

    private fun sameLanguage(source: String?, target: String): Boolean =
        source?.substringBefore('-')
            ?.equals(target.substringBefore('-'), ignoreCase = true) == true
}
