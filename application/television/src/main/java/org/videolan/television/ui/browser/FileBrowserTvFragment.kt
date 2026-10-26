package org.videolan.television.ui.browser

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Rect
import android.os.Bundle
import android.os.Parcelable
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.vectordrawable.graphics.drawable.VectorDrawableCompat
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.Dialog
import org.videolan.medialibrary.MLServiceLocator
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.medialibrary.media.MediaLibraryItem
import org.videolan.resources.CATEGORY
import org.videolan.resources.ITEM
import org.videolan.resources.util.parcelable
import org.videolan.television.R
import org.videolan.television.ui.FileTvItemAdapter
import org.videolan.television.ui.TvItemAdapter
import org.videolan.television.ui.TvUtil
import org.videolan.tools.isStarted
import org.videolan.vlc.gui.DialogActivity
import org.videolan.vlc.gui.dialogs.CONFIRM_EXPRESSION_SEARCH_DIALOG_RESULT
import org.videolan.vlc.gui.dialogs.EXPRESSION_SEARCH_QUERY
import org.videolan.vlc.gui.dialogs.ExpressionSearchDialog
import org.videolan.vlc.gui.browser.PathAdapter
import org.videolan.vlc.gui.browser.PathAdapterListener
import org.videolan.vlc.gui.view.EmptyLoadingState
import org.videolan.vlc.gui.view.VLCDividerItemDecoration
import org.videolan.vlc.interfaces.IEventsHandler
import org.videolan.vlc.providers.BrowserProvider
import org.videolan.vlc.repository.BrowserFavRepository
import org.videolan.vlc.util.DialogDelegate
import org.videolan.vlc.util.FileUtils
import org.videolan.vlc.util.IDialogManager
import org.videolan.vlc.util.isSchemeSupported
import org.videolan.vlc.util.onAnyChange
import org.videolan.vlc.viewmodels.browser.BrowserModel
import org.videolan.vlc.viewmodels.browser.IPathOperationDelegate
import org.videolan.vlc.viewmodels.browser.TYPE_FILE
import org.videolan.vlc.viewmodels.browser.TYPE_NETWORK
import org.videolan.vlc.viewmodels.browser.getBrowserModel

private const val TAG = "FileBrowserTvFragment"

class FileBrowserTvFragment : BaseBrowserTvFragment<MediaLibraryItem>(), PathAdapterListener, IDialogManager {

    private lateinit var dataObserver: RecyclerView.AdapterDataObserver
    private var favExists: Boolean = false
    private var isRootLevel = false
    private lateinit var browserFavRepository: BrowserFavRepository
    private var currentItem: MediaLibraryItem? = null
    override lateinit var adapter: TvItemAdapter
    private val dialogsDelegate by lazy(LazyThreadSafetyMode.NONE) { DialogDelegate() }

    var mrl: String? = null

    override fun getTitle() = when (getCategory()) {
        TYPE_FILE -> getString(R.string.directories)
        TYPE_NETWORK -> getString(R.string.network_browsing)
        else -> getString(R.string.video)
    }

    override fun getColumnNumber() = resources.getInteger(R.integer.tv_songs_col_count)

    override fun provideAdapter(eventsHandler: IEventsHandler<MediaLibraryItem>, itemSize: Int): TvItemAdapter {
        val fileTvItemAdapter = FileTvItemAdapter(this, itemSize, isRootLevel && getCategory() == TYPE_NETWORK)
        // restore the position from the source when navigating from Arian
        dataObserver = fileTvItemAdapter.onAnyChange {
            val source = (viewModel as IPathOperationDelegate).getSource()
            val selectedIndex = if (source != null) {
                if (fileTvItemAdapter.dataset.contains(source)) {
                    //the source has been found because we are on its direct parent
                    fileTvItemAdapter.dataset.indexOf(source)
                } else {
                    // we look for the item included in the source path to find what item to focus
                    var index: Int? = null
                    fileTvItemAdapter.dataset.forEach {
                        if ((source as? MediaWrapper)?.uri?.toString()?.startsWith(it.uri.toString()) == true) {
                            index = fileTvItemAdapter.dataset.indexOf(it)
                        }
                    }
                    index
                }
            } else null
            if (selectedIndex != null) {
                val lm = binding.list.layoutManager as LinearLayoutManager
                lm.scrollToPosition(selectedIndex)
                lm.getChildAt(selectedIndex)?.let {
                    it.requestFocus()
                    (viewModel as IPathOperationDelegate).consumeSource()
                }
            }
        }
        return fileTvItemAdapter
    }

    override fun getDisplayPrefId() = "display_tv_file_${getCategory()}"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentItem = if (savedInstanceState != null) savedInstanceState.parcelable<Parcelable>(ITEM) as? MediaLibraryItem
        else arguments?.parcelable(ITEM) as? MediaLibraryItem

        isRootLevel = arguments?.getBoolean("rootLevel") == true
        (currentItem as? MediaWrapper)?.run { mrl = location }
        val category = arguments?.getLong(CATEGORY, TYPE_FILE) ?: TYPE_FILE
        viewModel = getBrowserModel(category = category, url = mrl)

        viewModel.currentItem = currentItem
        browserFavRepository = BrowserFavRepository.getInstance(requireContext())

        if (getCategory() == TYPE_NETWORK) {
            dialogsDelegate.observeDialogs(this, this)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        requireActivity().supportFragmentManager.setFragmentResultListener(CONFIRM_EXPRESSION_SEARCH_DIALOG_RESULT, viewLifecycleOwner) { _, bundle ->
            val expression = bundle.getString(EXPRESSION_SEARCH_QUERY)
            val browserModel = viewModel as BrowserModel
            if (expression.isNullOrBlank()) browserModel.restore()
            else browserModel.filterExpression(expression)
            updateSearchExpressionUi()
            (viewModel.currentItem as? MediaWrapper).setBreadcrumb()
        }

        (viewModel as BrowserModel).dataset.observe(viewLifecycleOwner) { items ->
            if (items == null) return@observe
            // If an expression filter is active and a refresh is still loading, the data here
            // is momentarily unfiltered. Skip the update — reapplyFilterExpression() will
            // push the correctly filtered dataset once loading completes.
            if (isSearchByExpressionActive() && (viewModel.provider as BrowserProvider).loading.value == true) return@observe
            val lm = binding.list.layoutManager as LinearLayoutManager
            val selectedItem = lm.focusedChild
            submitList(items)
            binding.list.post {
                for (i in 0 until lm.childCount) {
                    if (lm.getChildAt(i) == selectedItem) {
                        lm.getChildAt(i)?.requestFocus()
                        lm.scrollToPosition(lm.getPosition(lm.getChildAt(i)!!))
                    }
                }
            }
            binding.emptyLoading.state = if (items.isEmpty()) EmptyLoadingState.EMPTY else EmptyLoadingState.NONE

            //headers
            val nbColumns = if ((viewModel as BrowserModel).sort == Medialibrary.SORT_ALPHA || (viewModel as BrowserModel).sort == Medialibrary.SORT_DEFAULT) 9 else 1

            binding.headerList.layoutManager = GridLayoutManager(requireActivity(), nbColumns)
            headerAdapter.sortType = (viewModel as BrowserModel).sort
        }

        viewModel.provider.liveHeaders.observe(viewLifecycleOwner) {
            updateHeaders(it)
            binding.list.invalidateItemDecorations()
            animationDelegate.setVisibility(binding.imageButtonHeader, if (viewModel.provider.headers.isEmpty) View.GONE else View.VISIBLE)
            animationDelegate.setVisibility(binding.headerButton, if (viewModel.provider.headers.isEmpty) View.GONE else View.VISIBLE)
            animationDelegate.setVisibility(binding.headerDescription, if (viewModel.provider.headers.isEmpty) View.GONE else View.VISIBLE)
        }

        var previousLoadingState: Boolean? = null
        (viewModel.provider as BrowserProvider).loading.observe(viewLifecycleOwner) { isLoading ->
            if (isLoading) binding.emptyLoading.state = EmptyLoadingState.LOADING
            if (!isLoading && (viewModel as BrowserModel).dataset.isEmpty()) binding.emptyLoading.state = EmptyLoadingState.EMPTY
            // Re-apply the active expression filter after a refresh completes so that
            // returning from a child folder does not clear the search results.
            if (previousLoadingState == true && !isLoading) {
                (viewModel as BrowserModel).reapplyFilterExpression()
            }
            previousLoadingState = isLoading
        }

        (viewModel as BrowserModel).getDescriptionUpdate().observe(viewLifecycleOwner) { pair ->
            if (pair != null) (adapter as RecyclerView.Adapter<*>).notifyItemChanged(pair.first, pair.second)
        }
    }

    override fun onStart() {
        super.onStart()
        updateSearchExpressionUi()
        (viewModel.currentItem as? MediaWrapper).setBreadcrumb()
    }

    private fun MediaWrapper?.setBreadcrumb() {
        val searchBreadcrumbLabel = getSearchBreadcrumbLabel()
        when {
            this != null && isSchemeSupported(uri?.scheme) -> showBreadcrumb(PathAdapter(this@FileBrowserTvFragment, this, searchBreadcrumbLabel))
            searchBreadcrumbLabel != null -> showBreadcrumb(SimpleBreadcrumbAdapter(listOf(getString(org.videolan.vlc.R.string.browser), searchBreadcrumbLabel)))
            else -> binding.ariane.visibility = View.GONE
        }
        updateTopNavigationLabel()
        animationDelegate.setVisibility(binding.title, if (binding.ariane.visibility == View.GONE) View.VISIBLE else View.GONE)
    }

    private fun showBreadcrumb(adapter: RecyclerView.Adapter<*>) {
        binding.ariane.visibility = View.VISIBLE
        val breadcrumbLayoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.ariane.layoutManager = breadcrumbLayoutManager
        binding.ariane.adapter = adapter
        if (binding.ariane.itemDecorationCount == 0) {
            val divider = object : VLCDividerItemDecoration(requireActivity(), HORIZONTAL, VectorDrawableCompat.create(requireActivity().resources, R.drawable.ic_divider, requireActivity().theme)!!) {
                override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                    val position = parent.getChildAdapterPosition(view)
                    if (position == (parent.adapter?.itemCount ?: 0) - 1) {
                        outRect.setEmpty()
                    } else {
                        super.getItemOffsets(outRect, view, parent, state)
                    }
                }
            }
            divider.setDrawable(VectorDrawableCompat.create(requireActivity().resources, R.drawable.ic_divider, requireActivity().theme)!!)
            binding.ariane.addItemDecoration(divider)
        }
        binding.ariane.post {
            breadcrumbLayoutManager.scrollToPositionWithOffset((binding.ariane.adapter?.itemCount ?: 1) - 1, 0)
        }
    }

    private fun getDefaultToolbarTitle() = viewModel.currentItem?.let {
        when (it) {
            is MediaLibraryItem -> if (getCategory() == TYPE_NETWORK || getCategory() == TYPE_FILE) "" else it.title
            else -> ""
        }
    } ?: getTitle()

    private fun updateTopNavigationLabel() {
        binding.title.text = getSearchBreadcrumbLabel() ?: getDefaultToolbarTitle()
    }

    private fun getSearchBreadcrumbLabel() = (viewModel as BrowserModel).filterQuery
        ?.takeIf { it.isNotBlank() }
        ?.let { getString(R.string.search_expression_breadcrumb, it) }

    private fun updateSearchExpressionUi() {
        val query = (viewModel as BrowserModel).filterQuery?.takeIf { it.isNotBlank() }
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
        // Keep the tooltip static — the breadcrumb already shows the active query, so repeating
        // it here would create a large overlay over the breadcrumb when the button gains focus.
        binding.expressionDescription.text = getString(R.string.search_by_expression)
        updateTopNavigationLabel()
    }

    override fun backTo(tag: String) {
        if (tag == "root") {
            requireActivity().finish()
            return
        }
        val supportFragmentManager = requireActivity().supportFragmentManager
        var poped = false
        for (i in 0 until supportFragmentManager.backStackEntryCount) {
            if (tag == supportFragmentManager.getBackStackEntryAt(i).name) {
                supportFragmentManager.popBackStack(tag, FragmentManager.POP_BACK_STACK_INCLUSIVE)
                poped = true
                break
            }
        }
        if (!poped) {
            if (supportFragmentManager.backStackEntryCount == 0) browse(MLServiceLocator.getAbstractMediaWrapper(tag.toUri()), false)
            else {
                (viewModel as IPathOperationDelegate).setDestination(MLServiceLocator.getAbstractMediaWrapper(tag.toUri()))
                (viewModel as IPathOperationDelegate).setSource(currentItem)
                supportFragmentManager.popBackStack()
            }
        }
    }

    override fun currentContext(): Context = requireActivity()

    override fun showRoot(): Boolean = true

    override fun getPathOperationDelegate() = viewModel as IPathOperationDelegate

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        lifecycleScope.launch {
            animationDelegate.setVisibility(binding.favoriteButton, View.VISIBLE)
            animationDelegate.setVisibility(binding.imageButtonFavorite, View.VISIBLE)
            animationDelegate.setVisibility(binding.favoriteDescription, View.VISIBLE)
            animationDelegate.setVisibility(binding.expressionButton, View.VISIBLE)
            animationDelegate.setVisibility(binding.imageButtonExpression, View.VISIBLE)
            animationDelegate.setVisibility(binding.expressionDescription, View.VISIBLE)
            favExists = (currentItem as? MediaWrapper)?.let { browserFavRepository.browserFavExists(it.uri) } == true
            binding.favoriteButton.setImageResource(if (favExists) R.drawable.ic_tv_browser_favorite else R.drawable.ic_tv_browser_favorite_outline)
            binding.favoriteButton.contentDescription = getString(if (favExists) R.string.favorites_remove else R.string.favorites_add)
            binding.imageButtonFavorite.setImageResource(if (favExists) R.drawable.ic_fabtvmini_favorite else R.drawable.ic_fabtvmini_favorite_outline)
            binding.imageButtonFavorite.contentDescription = getString(if (favExists) R.string.favorites_remove else R.string.favorites_add)
            updateSearchExpressionUi()
        }
        binding.favoriteButton.setOnClickListener(favoriteClickListener)
        binding.imageButtonFavorite.setOnClickListener(favoriteClickListener)
        binding.emptyLoading.showNoMedia = false
    }

    override fun onResume() {
        super.onResume()
        if (currentItem == null) (viewModel.provider as BrowserProvider).browseRoot()
        else if (restarted) refresh()
        (viewModel as IPathOperationDelegate).getAndRemoveDestination()?.let {
            browse(it, true)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putParcelable(ITEM, currentItem)
        outState.putLong(CATEGORY, getCategory())
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        super.onStop()
        (viewModel as BrowserModel).stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::dataObserver.isInitialized) (adapter as FileTvItemAdapter).unregisterAdapterDataObserver(dataObserver)
    }

    override fun getCategory() = (viewModel as BrowserModel).type

    override fun canSearchByExpression() = (binding.list.adapter?.itemCount ?: 0) > 0

    override fun isSearchByExpressionActive() = !(viewModel as BrowserModel).filterQuery.isNullOrBlank()

    override fun onSearchExpressionSelected(): Boolean {
        ExpressionSearchDialog.newInstance((viewModel as BrowserModel).filterQuery)
            .show(requireActivity().supportFragmentManager, ExpressionSearchDialog::class.simpleName)
        return true
    }

    override fun onClick(v: View, position: Int, item: MediaLibraryItem) {
        val mediaWrapper = item as MediaWrapper

        mediaWrapper.removeFlags(MediaWrapper.MEDIA_FORCE_AUDIO)
        if (mediaWrapper.type == MediaWrapper.TYPE_DIR) browse(mediaWrapper, true)
        else TvUtil.openMedia(requireActivity(), item, viewModel as BrowserModel)
    }

    fun browse(media: MediaWrapper, save: Boolean) {
        val ctx = activity
        if (ctx == null || !isResumed || isRemoving) return
        val ft = ctx.supportFragmentManager.beginTransaction()
        val next = newInstance(getCategory(), media)
        (viewModel as BrowserModel).saveList(media)
        if (save) ft.addToBackStack(if (mrl == null) "root" else viewModel.currentItem?.title
                ?: FileUtils.getFileNameFromPath(mrl))
        ft.replace(R.id.tv_fragment_placeholder, next, media.title)
        ft.commit()
    }

    override fun onKeyPressed(keyCode: Int): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BOOKMARK) {
            togglefavorite()
            return true
        }
        return super.onKeyPressed(keyCode)
    }

    private val favoriteClickListener: (View) -> Unit = {
        togglefavorite()
    }

    private fun togglefavorite() {
        currentItem?.let { item ->
            lifecycleScope.launch {
                val mw = (item as MediaWrapper)
                withContext(Dispatchers.IO) {
                    when {
                        browserFavRepository.browserFavExists(mw.uri) -> browserFavRepository.deleteBrowserFav(mw.uri)
                        mw.uri.scheme == "file" -> browserFavRepository.addLocalFavItem(mw.uri, mw.title, mw.artworkURL)
                        else -> browserFavRepository.addNetworkFavItem(mw.uri, mw.title, mw.artworkURL)
                    }
                }
                favExists = browserFavRepository.browserFavExists(mw.uri)
                binding.favoriteButton.setImageResource(if (favExists) R.drawable.ic_tv_browser_favorite else R.drawable.ic_tv_browser_favorite_outline)
                binding.favoriteButton.contentDescription = getString(if (favExists) R.string.favorites_remove else R.string.favorites_add)
                binding.imageButtonFavorite.setImageResource(if (favExists) R.drawable.ic_fabtvmini_favorite else R.drawable.ic_fabtvmini_favorite_outline)
                binding.imageButtonFavorite.contentDescription = getString(if (favExists) R.string.favorites_remove else R.string.favorites_add)
            }
        }
    }

    companion object {
        fun newInstance(type: Long, item: MediaLibraryItem?, root: Boolean = false) =
                FileBrowserTvFragment().apply {
                    arguments = bundleOf(CATEGORY to type, ITEM to item, "rootLevel" to root)
                }
    }

    private class SimpleBreadcrumbAdapter(private val items: List<String>) : RecyclerView.Adapter<SimpleBreadcrumbAdapter.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(org.videolan.vlc.R.layout.browser_path_item, parent, false) as TextView
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.root.text = items[position]
        }

        override fun getItemCount() = items.size

        class ViewHolder(val root: TextView) : RecyclerView.ViewHolder(root)
    }

    override fun fireDialog(dialog: Dialog) {
        DialogActivity.dialog = dialog
        startActivity(Intent(DialogActivity.KEY_DIALOG, null, activity, DialogActivity::class.java))
    }

    override fun dialogCanceled(dialog: Dialog?) {
        when(dialog) {
            is Dialog.LoginDialog -> goBack()
            is Dialog.ErrorMessage -> {
                view?.let { Snackbar.make(it, "${dialog.title}: ${dialog.text}", Snackbar.LENGTH_LONG).show() }
                goBack()
            }
        }
    }

    private fun goBack() {
        val activity = activity
        if (activity?.isStarted() != true) return
        if (tag == "root") {
            activity.finish()
        } else if  (!activity.isFinishing && !activity.isDestroyed) {
            activity.supportFragmentManager.popBackStack()
        }
    }
}
