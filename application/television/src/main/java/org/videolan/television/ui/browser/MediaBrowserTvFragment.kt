package org.videolan.television.ui.browser

import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Parcelable
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.medialibrary.media.MediaLibraryItem
import org.videolan.resources.*
import org.videolan.resources.util.parcelable
import org.videolan.television.R
import org.videolan.television.ui.MediaTvItemAdapter
import org.videolan.television.ui.TvItemAdapter
import org.videolan.television.ui.TvUtil
import org.videolan.television.viewmodel.MediaBrowserViewModel
import org.videolan.television.viewmodel.getMediaBrowserModel
import org.videolan.tools.PLAYLIST_MODE_AUDIO
import org.videolan.tools.PLAYLIST_MODE_VIDEO
import org.videolan.tools.Settings
import org.videolan.vlc.gui.dialogs.CONFIRM_EXPRESSION_SEARCH_DIALOG_RESULT
import org.videolan.vlc.gui.dialogs.EXPRESSION_SEARCH_QUERY
import org.videolan.vlc.gui.dialogs.ExpressionSearchDialog
import org.videolan.vlc.gui.view.EmptyLoadingState
import org.videolan.vlc.interfaces.IEventsHandler
import org.videolan.vlc.providers.medialibrary.MedialibraryProvider

class MediaBrowserTvFragment : BaseBrowserTvFragment<MediaLibraryItem>() {
    override fun provideAdapter(eventsHandler: IEventsHandler<MediaLibraryItem>, itemSize: Int): TvItemAdapter {
        return MediaTvItemAdapter(when ((viewModel as MediaBrowserViewModel).category) {
            CATEGORY_SONGS -> MediaWrapper.TYPE_AUDIO
            CATEGORY_ALBUMS -> MediaWrapper.TYPE_ALBUM
            CATEGORY_ARTISTS -> MediaWrapper.TYPE_ARTIST
            CATEGORY_GENRES -> MediaWrapper.TYPE_GENRE
            CATEGORY_PLAYLISTS -> MediaWrapper.TYPE_PLAYLIST
            else -> MediaWrapper.TYPE_VIDEO
        }, this, itemSize)
    }

    override fun getDisplayPrefId() = "display_tv_media_${(viewModel as MediaBrowserViewModel).category}"

    override lateinit var adapter: TvItemAdapter

    override fun getTitle() = when ((viewModel as MediaBrowserViewModel).category) {
        CATEGORY_SONGS -> getString(R.string.tracks)
        CATEGORY_ALBUMS -> getString(R.string.albums)
        CATEGORY_ARTISTS -> getString(R.string.artists)
        CATEGORY_GENRES -> getString(R.string.genres)
        CATEGORY_PLAYLISTS -> getString(R.string.playlists)
        else -> getString(R.string.video)
    }

    override fun getCategory(): Long = (viewModel as MediaBrowserViewModel).category

    override fun getColumnNumber() = when ((viewModel as MediaBrowserViewModel).category) {
        CATEGORY_VIDEOS -> resources.getInteger(R.integer.tv_videos_col_count)
        else -> resources.getInteger(R.integer.tv_songs_col_count)
    }

    companion object {
        fun newInstance(type: Long, item: MediaLibraryItem?) =
                MediaBrowserTvFragment().apply {
                    arguments = bundleOf(CATEGORY to type, ITEM to item)
                }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val currentItem = if (savedInstanceState != null) savedInstanceState.parcelable<Parcelable>(ITEM) as? MediaLibraryItem
        else requireActivity().intent.parcelable<Parcelable>(ITEM) as? MediaLibraryItem

        viewModel = getMediaBrowserModel(arguments?.getLong(CATEGORY, CATEGORY_SONGS) ?: CATEGORY_SONGS, currentItem)

        (viewModel.provider as MedialibraryProvider<*>).pagedList.observe(this) { items ->
            submitList(items)

            binding.emptyLoading.state = if (items.isEmpty()) EmptyLoadingState.EMPTY else EmptyLoadingState.NONE

            //headers
            val nbColumns = if ((viewModel as MediaBrowserViewModel).sort == Medialibrary.SORT_ALPHA || (viewModel as MediaBrowserViewModel).sort == Medialibrary.SORT_DEFAULT) 9 else 1

            binding.headerList.layoutManager = GridLayoutManager(requireActivity(), nbColumns)
            headerAdapter.sortType = (viewModel as MediaBrowserViewModel).sort
        }

        viewModel.provider.liveHeaders.observe(this) {
            updateHeaders(it)
            binding.list.invalidateItemDecorations()
        }

        (viewModel.provider as MedialibraryProvider<*>).loading.observe(this) {
            binding.emptyLoading.state = when {
                it -> EmptyLoadingState.LOADING
                viewModel.isEmpty() && adapter.isEmpty() -> EmptyLoadingState.EMPTY
                else -> EmptyLoadingState.NONE
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        animationDelegate.setVisibility(binding.expressionButton, View.VISIBLE)
        animationDelegate.setVisibility(binding.imageButtonExpression, View.VISIBLE)
        animationDelegate.setVisibility(binding.expressionDescription, View.VISIBLE)

        requireActivity().supportFragmentManager.setFragmentResultListener(CONFIRM_EXPRESSION_SEARCH_DIALOG_RESULT, viewLifecycleOwner) { _, bundle ->
            val expression = bundle.getString(EXPRESSION_SEARCH_QUERY)
            val vm = viewModel as MediaBrowserViewModel
            if (expression.isNullOrBlank()) vm.restore()
            else vm.filter(expression)
            updateSearchExpressionUi()
        }
    }

    override fun onStart() {
        super.onStart()
        updateSearchExpressionUi()
    }

    override fun canSearchByExpression() = (binding.list.adapter?.itemCount ?: 0) > 0

    override fun isSearchByExpressionActive() = !(viewModel as MediaBrowserViewModel).filterQuery.isNullOrBlank()

    override fun onSearchExpressionSelected(): Boolean {
        ExpressionSearchDialog.newInstance((viewModel as MediaBrowserViewModel).filterQuery)
            .show(requireActivity().supportFragmentManager, ExpressionSearchDialog::class.simpleName)
        return true
    }

    private fun updateSearchExpressionUi() {
        val query = (viewModel as MediaBrowserViewModel).filterQuery?.takeIf { it.isNotBlank() }
        val isExpressionFilterActive = query != null
        val orange = ContextCompat.getColor(requireContext(), R.color.orange500)
        val white = ContextCompat.getColor(requireContext(), R.color.white)
        // Inactive: keep the icon's own state-based tint (orange when focused, like the sibling buttons).
        // Active: stay orange, but flip to white on focus so focus remains visible on the highlighted pill.
        val tint = if (isExpressionFilterActive) ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_pressed), intArrayOf()),
            intArrayOf(white, white, orange)
        ) else null
        val label = query?.let { getString(R.string.search_expression_breadcrumb, it) }
            ?: getString(R.string.search_by_expression)

        listOf(binding.expressionButton, binding.imageButtonExpression).forEach { button ->
            button.isActivated = isExpressionFilterActive
            button.contentDescription = label
            ImageViewCompat.setImageTintList(button, tint)
            button.setBackgroundResource(if (isExpressionFilterActive) R.drawable.rounded_corners else android.R.color.transparent)
        }
        binding.expressionDescription.text = label
        binding.title.text = query?.let { getString(R.string.search_expression_breadcrumb, it) } ?: getTitle()
    }

    override fun onClick(v: View, position: Int, item: MediaLibraryItem) {
        lifecycleScope.launchWhenStarted {
            if ((viewModel as MediaBrowserViewModel).category == CATEGORY_VIDEOS && !Settings.getInstance(requireContext()).getBoolean(PLAYLIST_MODE_VIDEO, Settings.tvUI)) {
                TvUtil.playMedia(requireActivity(), item as MediaWrapper)
            } else if ((viewModel as MediaBrowserViewModel).category == CATEGORY_SONGS && !Settings.getInstance(requireContext()).getBoolean(PLAYLIST_MODE_AUDIO, Settings.tvUI)) {
                TvUtil.playMedia(requireActivity(), item as MediaWrapper)
            } else {
                TvUtil.openMediaFromPaged(requireActivity(), item, viewModel.provider as MedialibraryProvider<out MediaLibraryItem>)
            }
        }
    }
}
