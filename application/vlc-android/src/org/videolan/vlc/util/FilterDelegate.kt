package org.videolan.vlc.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.medialibrary.media.MediaLibraryItem
import org.videolan.resources.AppContextProvider
import org.videolan.tools.livedata.LiveDataset
import org.videolan.vlc.media.MediaUtils
import java.util.Locale
import kotlin.text.RegexOption.IGNORE_CASE

enum class FilterMode {
    CONTAINS,
    WILDCARD
}

open class FilterDelegate<T : MediaLibraryItem>(val dataset: LiveDataset<T>) {
    var sourceSet: List<T>? = null

    protected fun initSource() : List<T>? {
        if (sourceSet === null) sourceSet = (dataset.value)
        return sourceSet
    }

    suspend fun filter(charSequence: CharSequence?, mode: FilterMode = FilterMode.CONTAINS) = publish(filteringJob(charSequence, mode))

    protected open suspend fun filteringJob(charSequence: CharSequence?, mode: FilterMode) : MutableList<T>? {
        val query = charSequence?.trim()?.toString()?.takeIf { it.isNotEmpty() } ?: return null
        if (mode == FilterMode.WILDCARD) initSource()?.let { source ->
            val matcher = query.toWildcardRegex()
            return withContext(Dispatchers.Default) {
                source.filterTo(mutableListOf()) { item -> matcher.matches(item.title) }
            }
        }
        initSource()?.let {
            return withContext(Dispatchers.Default) {
                mutableListOf<T>().apply {
                    val queryStrings = query.split(" ")
                    for (item in it) for (query in queryStrings)
                        if (item.title.contains(query, true)) {
                            this.add(item)
                            break
                        }
                }
            }
        }
        return null
    }

    private fun publish(list: MutableList<T>?) {
        sourceSet?.let {
            list?.let {
                dataset.value = it
            } ?: run {
                dataset.value = it.toMutableList()
                sourceSet = null
            }
        }
    }
}

class PlaylistFilterDelegate(dataset: LiveDataset<MediaWrapper>) : FilterDelegate<MediaWrapper>(dataset) {

    override suspend fun filteringJob(charSequence: CharSequence?, mode: FilterMode): MutableList<MediaWrapper>? {
        val query = charSequence?.trim()?.toString()?.takeIf { it.isNotEmpty() } ?: return null
        if (mode == FilterMode.WILDCARD) return super.filteringJob(query, mode)
        initSource()?.let { list ->
            return withContext(Dispatchers.Default) { mutableListOf<MediaWrapper>().apply {
                val queryStrings = query.split(" ").asSequence().filter { it.isNotEmpty() }.map { it.lowercase(Locale.getDefault()) }.toList()
                for (media in list) {
                    val title = MediaUtils.getMediaTitle(media).lowercase(Locale.getDefault())
                    val location = media.location.lowercase(Locale.getDefault())
                    val artist = MediaUtils.getMediaArtist(AppContextProvider.appContext, media).lowercase(Locale.getDefault())
                    val albumArtist = MediaUtils.getMediaAlbumArtist(AppContextProvider.appContext, media).lowercase(Locale.getDefault())
                    val album = MediaUtils.getMediaAlbum(AppContextProvider.appContext, media).lowercase(Locale.getDefault())
                    val genre = MediaUtils.getMediaGenre(AppContextProvider.appContext, media).lowercase(Locale.getDefault())
                    for (queryString in queryStrings) {
                        if (title.contains(queryString) ||
                            location.contains(queryString) ||
                            artist.contains(queryString) ||
                            albumArtist.contains(queryString) ||
                            album.contains(queryString) ||
                            genre.contains(queryString)) {
                            this.add(media)
                            break
                        }
                    }
                }
            }
            }
        }
        return null
    }
}

private fun String.toWildcardRegex(): Regex {
    val hasWildcard = any { it == '*' || it == '?' }
    val pattern = buildString(length * 2 + 2) {
        if (hasWildcard) append('^') else append(".*")
        for (character in this@toWildcardRegex) {
            when (character) {
                '*' -> append(".*")
                '?' -> append('.')
                '\\', '.', '^', '$', '|', '(', ')', '[', ']', '{', '}', '+' -> {
                    append('\\')
                    append(character)
                }
                else -> append(character)
            }
        }
        if (hasWildcard) append('$') else append(".*")
    }
    return Regex(pattern, IGNORE_CASE)
}