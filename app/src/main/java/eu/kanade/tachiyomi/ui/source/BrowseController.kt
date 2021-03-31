package eu.kanade.tachiyomi.ui.source

import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.app.Activity
import android.content.res.ColorStateList
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.bluelinelabs.conductor.ControllerChangeHandler
import com.bluelinelabs.conductor.ControllerChangeType
import com.bluelinelabs.conductor.RouterTransaction
import com.bluelinelabs.conductor.changehandler.FadeChangeHandler
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.snackbar.Snackbar
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.preference.getOrDefault
import eu.kanade.tachiyomi.databinding.BrowseControllerBinding
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.base.controller.BaseController
import eu.kanade.tachiyomi.ui.extension.SettingsExtensionsController
import eu.kanade.tachiyomi.ui.main.BottomSheetController
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.main.RootSearchInterface
import eu.kanade.tachiyomi.ui.setting.SettingsBrowseController
import eu.kanade.tachiyomi.ui.setting.SettingsSourcesController
import eu.kanade.tachiyomi.ui.source.browse.BrowseSourceController
import eu.kanade.tachiyomi.ui.source.global_search.GlobalSearchController
import eu.kanade.tachiyomi.ui.source.latest.LatestUpdatesController
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.spToPx
import eu.kanade.tachiyomi.util.view.activityBinding
import eu.kanade.tachiyomi.util.view.collapse
import eu.kanade.tachiyomi.util.view.expand
import eu.kanade.tachiyomi.util.view.isCollapsed
import eu.kanade.tachiyomi.util.view.isExpanded
import eu.kanade.tachiyomi.util.view.requestPermissionsSafe
import eu.kanade.tachiyomi.util.view.scrollViewWith
import eu.kanade.tachiyomi.util.view.setOnQueryTextChangeListener
import eu.kanade.tachiyomi.util.view.snack
import eu.kanade.tachiyomi.util.view.updateLayoutParams
import eu.kanade.tachiyomi.util.view.updatePaddingRelative
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import kotlinx.android.parcel.Parcelize
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.max
import kotlin.math.min

/**
 * This controller shows and manages the different catalogues enabled by the user.
 * This controller should only handle UI actions, IO actions should be done by [SourcePresenter]
 * [SourceAdapter.SourceListener] call function data on browse item click.
 * [SourceAdapter.OnLatestClickListener] call function data on latest item click
 */
class BrowseController :
    BaseController<BrowseControllerBinding>(),
    FlexibleAdapter.OnItemClickListener,
    SourceAdapter.SourceListener,
    RootSearchInterface,
    BottomSheetController {

    /**
     * Application preferences.
     */
    private val preferences: PreferencesHelper = Injekt.get()

    /**
     * Adapter containing sources.
     */
    private var adapter: SourceAdapter? = null

    var extQuery = ""
        private set

    var headerHeight = 0

    var showingExtensions = false

    var snackbar: Snackbar? = null

    /**
     * Called when controller is initialized.
     */
    init {
        setHasOptionsMenu(true)
    }

    override fun getTitle(): String? {
        return if (showingExtensions) {
            view?.context?.getString(
                when (binding.bottomSheet.tabs.selectedTabPosition) {
                    0 -> R.string.extensions
                    else -> R.string.source_migration
                }
            )
        } else view?.context?.getString(R.string.browse)
    }

    val presenter = SourcePresenter(this)

    override fun createBinding(inflater: LayoutInflater) = BrowseControllerBinding.inflate(inflater)

    override fun onViewCreated(view: View) {
        super.onViewCreated(view)

        adapter = SourceAdapter(this)
        // Create binding.sourceRecycler and set adapter.
        binding.sourceRecycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(view.context)

        binding.sourceRecycler.adapter = adapter
        adapter?.isSwipeEnabled = true
        adapter?.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        // binding.sourceRecycler.addItemDecoration(SourceDividerItemDecoration(view.context))
        val attrsArray = intArrayOf(android.R.attr.actionBarSize)
        val array = view.context.obtainStyledAttributes(attrsArray)
        val appBarHeight = array.getDimensionPixelSize(0, 0)
        array.recycle()
        scrollViewWith(
            binding.sourceRecycler,
            customPadding = true,
            afterInsets = {
                headerHeight = it.systemWindowInsetTop + appBarHeight
                binding.sourceRecycler.updatePaddingRelative(
                    top = activityBinding?.appBar?.height ?: 0,
                    bottom = (activityBinding?.bottomNav?.height ?: 0) + 58.spToPx
                )
            },
            onBottomNavUpdate = {
                setBottomPadding()
            }
        )

        binding.sourceRecycler.post {
            setBottomSheetTabs(if (binding.bottomSheet.root.sheetBehavior.isCollapsed()) 0f else 1f)
            activityBinding?.appBar?.elevation = min(
                if (binding.bottomSheet.root.sheetBehavior.isCollapsed()) 0f else 1f * 15f,
                if (binding.sourceRecycler.canScrollVertically(-1)) 15f else 0f
            )
            binding.sourceRecycler.updatePaddingRelative(
                bottom = (activityBinding?.bottomNav?.height ?: 0) + 58.spToPx
            )
        }

        requestPermissionsSafe(arrayOf(WRITE_EXTERNAL_STORAGE), 301)
        binding.bottomSheet.root.onCreate(this)

        binding.bottomSheet.root.sheetBehavior?.addBottomSheetCallback(
            object : BottomSheetBehavior
            .BottomSheetCallback() {
                override fun onSlide(bottomSheet: View, progress: Float) {
                    binding.shadow2.alpha = (1 - max(0f, progress)) * 0.25f
                    activityBinding?.appBar?.elevation = min(
                        (1f - progress) * 15f,
                        if (binding.sourceRecycler.canScrollVertically(-1)) 15f else 0f
                    )
                    activityBinding?.appBar?.y = max(activityBinding!!.appBar.y, -headerHeight * (1 - progress))
                    val oldShow = showingExtensions
                    showingExtensions = progress > 0.92f
                    if (oldShow != showingExtensions) {
                        setTitle()
                        activity?.invalidateOptionsMenu()
                    }
                    setBottomSheetTabs(max(0f, progress))
                }

                override fun onStateChanged(p0: View, state: Int) {
                    if (state == BottomSheetBehavior.STATE_SETTLING) {
                        binding.bottomSheet.root.updatedNestedRecyclers()
                    }
                    val extBottomSheet = binding.bottomSheet.root
                    if (state == BottomSheetBehavior.STATE_EXPANDED) {
                        activityBinding?.appBar?.y = 0f
                    }
                    if (state == BottomSheetBehavior.STATE_EXPANDED ||
                        state == BottomSheetBehavior.STATE_COLLAPSED
                    ) {
                        showingExtensions = state == BottomSheetBehavior.STATE_EXPANDED
                        setTitle()
                        if (state == BottomSheetBehavior.STATE_EXPANDED) {
                            extBottomSheet.fetchOnlineExtensionsIfNeeded()
                        } else extBottomSheet.shouldCallApi = true
                        activity?.invalidateOptionsMenu()
                    }

                    retainViewMode = if (state == BottomSheetBehavior.STATE_EXPANDED) {
                        RetainViewMode.RETAIN_DETACH
                    } else RetainViewMode.RELEASE_DETACH
                    binding.bottomSheet.sheetLayout.isClickable = state == BottomSheetBehavior.STATE_COLLAPSED
                    binding.bottomSheet.sheetLayout.isFocusable = state == BottomSheetBehavior.STATE_COLLAPSED
                    if (state == BottomSheetBehavior.STATE_COLLAPSED || state == BottomSheetBehavior.STATE_EXPANDED) {
                        setBottomSheetTabs(if (state == BottomSheetBehavior.STATE_COLLAPSED) 0f else 1f)
                    }
                }
            }
        )

        if (showingExtensions) {
            binding.bottomSheet.root.sheetBehavior?.expand()
        }
        presenter.onCreate()
        if (presenter.sourceItems.isNotEmpty()) {
            setSources(presenter.sourceItems, presenter.lastUsedItem)
        }
    }

    fun updateTitleAndMenu() {
        setTitle()
        activity?.invalidateOptionsMenu()
    }

    fun setBottomSheetTabs(progress: Float) {
        val bottomSheet = binding.bottomSheet.root
        binding.bottomSheet.tabs.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = ((activityBinding?.appBar?.height?.minus(9f.dpToPx) ?: 0f) * progress).toInt()
        }
        val selectedColor = ColorUtils.setAlphaComponent(
            ContextCompat.getColor(binding.bottomSheet.tabs.context, R.color.colorAccent),
            (progress * 255).toInt()
        )
        val unselectedColor = ColorUtils.setAlphaComponent(
            bottomSheet.context.getResourceColor(R.attr.colorOnBackground),
            153
        )
        binding.bottomSheet.sheetLayout.elevation = progress * 5
        binding.bottomSheet.pager.alpha = progress * 10
        binding.bottomSheet.tabs.setSelectedTabIndicatorColor(selectedColor)
        binding.bottomSheet.tabs.setTabTextColors(
            ColorUtils.blendARGB(
                bottomSheet.context.getResourceColor(R.attr.actionBarTintColor),
                unselectedColor,
                progress
            ),
            ColorUtils.blendARGB(
                bottomSheet.context.getResourceColor(R.attr.actionBarTintColor),
                selectedColor,
                progress
            )
        )

        binding.bottomSheet.sheetLayout.backgroundTintList = ColorStateList.valueOf(
            ColorUtils.blendARGB(
                bottomSheet.context.getResourceColor(R.attr.colorPrimaryVariant),
                bottomSheet.context.getResourceColor(R.attr.colorSecondary),
                progress
            )
        )
    }

    private fun setBottomPadding() {
        val bottomBar = activityBinding?.bottomNav ?: return
        val pad = bottomBar.translationY - bottomBar.height
        val padding = max(
            (-pad).toInt(),
            if (binding.bottomSheet.root.sheetBehavior.isExpanded()) 0 else {
                view?.rootWindowInsets?.systemWindowInsetBottom ?: 0
            }
        )
        binding.shadow2.translationY = pad
        binding.bottomSheet.root.sheetBehavior?.peekHeight = 58.spToPx + padding
        binding.bottomSheet.root.extensionFrameLayout?.binding?.fastScroller?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = -pad.toInt()
        }
        binding.bottomSheet.root.migrationFrameLayout?.binding?.fastScroller?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = -pad.toInt()
        }
        binding.sourceRecycler.updatePaddingRelative(
            bottom = (activityBinding?.bottomNav?.height ?: 0) + 58.spToPx
        )
    }

    override fun showSheet() {
        binding.bottomSheet.root.sheetBehavior?.expand()
    }

    override fun toggleSheet() {
        if (!binding.bottomSheet.root.sheetBehavior.isCollapsed()) {
            binding.bottomSheet.root.sheetBehavior?.collapse()
        } else {
            binding.bottomSheet.root.sheetBehavior?.expand()
        }
    }

    override fun sheetIsExpanded(): Boolean = binding.bottomSheet.root.sheetBehavior.isExpanded()

    override fun handleSheetBack(): Boolean {
        if (!binding.bottomSheet.root.sheetBehavior.isCollapsed()) {
            if (binding.bottomSheet.root.canGoBack()) {
                binding.bottomSheet.root.sheetBehavior?.collapse()
            }
            return true
        }
        return false
    }

    override fun onDestroyView(view: View) {
        adapter = null
        super.onDestroyView(view)
    }

    override fun onDestroy() {
        super.onDestroy()
        presenter.onDestroy()
    }

    override fun onChangeStarted(handler: ControllerChangeHandler, type: ControllerChangeType) {
        super.onChangeStarted(handler, type)
        if (!type.isPush) {
            binding.bottomSheet.root.updateExtTitle()
            binding.bottomSheet.root.presenter.refreshExtensions()
            presenter.updateSources()
        }
        if (!type.isEnter) {
            binding.bottomSheet.root.canExpand = false
            activityBinding?.appBar?.elevation =
                when {
                    binding.bottomSheet.root.sheetBehavior.isExpanded() -> 0f
                    binding.sourceRecycler.canScrollVertically(-1) -> 15f
                    else -> 0f
                }
        } else {
            binding.bottomSheet.root.presenter.refreshMigrations()
        }
        setBottomPadding()
    }

    override fun onChangeEnded(handler: ControllerChangeHandler, type: ControllerChangeType) {
        super.onChangeEnded(handler, type)
        if (type.isEnter) {
            binding.bottomSheet.root.canExpand = true
            setBottomPadding()
        }
    }

    override fun onActivityResumed(activity: Activity) {
        super.onActivityResumed(activity)
        binding.bottomSheet.root.presenter.refreshExtensions()
        binding.bottomSheet.root.presenter.refreshMigrations()
        setBottomPadding()
    }

    override fun onItemClick(view: View, position: Int): Boolean {
        val item = adapter?.getItem(position) as? SourceItem ?: return false
        val source = item.source
        // Open the catalogue view.
        openCatalogue(source, BrowseSourceController(source))
        return false
    }

    fun hideCatalogue(position: Int) {
        val source = (adapter?.getItem(position) as? SourceItem)?.source ?: return
        val current = preferences.hiddenSources().get()
        preferences.hiddenSources().set(current + source.id.toString())

        presenter.updateSources()

        snackbar = view?.snack(R.string.source_hidden, Snackbar.LENGTH_INDEFINITE) {
            anchorView = binding.bottomSheet.root
            setAction(R.string.undo) {
                val newCurrent = preferences.hiddenSources().get()
                preferences.hiddenSources().set(newCurrent - source.id.toString())
                presenter.updateSources()
            }
        }
        (activity as? MainActivity)?.setUndoSnackBar(snackbar)
    }

    private fun pinCatalogue(source: Source, isPinned: Boolean) {
        val current = preferences.pinnedCatalogues().getOrDefault()
        if (isPinned) {
            preferences.pinnedCatalogues().set(current - source.id.toString())
        } else {
            preferences.pinnedCatalogues().set(current + source.id.toString())
        }

        presenter.updateSources()
    }

    /**
     * Called when browse is clicked in [SourceAdapter]
     */
    override fun onPinClick(position: Int) {
        val item = adapter?.getItem(position) as? SourceItem ?: return
        val isPinned = item.isPinned ?: item.header?.code?.equals(SourcePresenter.PINNED_KEY)
            ?: false
        pinCatalogue(item.source, isPinned)
    }

    /**
     * Called when latest is clicked in [SourceAdapter]
     */
    override fun onLatestClick(position: Int) {
        val item = adapter?.getItem(position) as? SourceItem ?: return
        openCatalogue(item.source, LatestUpdatesController(item.source))
    }

    /**
     * Opens a catalogue with the given controller.
     */
    private fun openCatalogue(source: CatalogueSource, controller: BrowseSourceController) {
        preferences.lastUsedCatalogueSource().set(source.id)
        router.pushController(controller.withFadeTransaction())
    }

    override fun expandSearch() {
        if (showingExtensions) binding.bottomSheet.root.sheetBehavior?.collapse()
        else activityBinding?.toolbar?.menu?.findItem(R.id.action_search)?.expandActionView()
    }

    /**
     * Adds items to the options menu.
     *
     * @param menu menu containing options.
     * @param inflater used to load the menu xml.
     */
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        if (onRoot) (activity as? MainActivity)?.setDismissIcon(showingExtensions)
        if (showingExtensions) {
            if (binding.bottomSheet.tabs.selectedTabPosition == 0) {
                // Inflate menu
                inflater.inflate(R.menu.extension_main, menu)

                // Initialize search option.
                val searchItem = menu.findItem(R.id.action_search)
                val searchView = searchItem.actionView as SearchView

                // Change hint to show global search.
                searchView.queryHint = view?.context?.getString(R.string.search_extensions)

                // Create query listener which opens the global search view.
                setOnQueryTextChangeListener(searchView) {
                    extQuery = it ?: ""
                    binding.bottomSheet.root.drawExtensions()
                    true
                }
            } else {
                inflater.inflate(R.menu.migration_main, menu)
            }
        } else {
            // Inflate menu
            inflater.inflate(R.menu.catalogue_main, menu)

            // Initialize search option.
            val searchItem = menu.findItem(R.id.action_search)
            val searchView = searchItem.actionView as SearchView

            // Change hint to show global search.
            searchView.queryHint = view?.context?.getString(R.string.global_search)

            // Create query listener which opens the global search view.
            setOnQueryTextChangeListener(searchView, true) {
                if (!it.isNullOrBlank()) performGlobalSearch(it)
                true
            }
        }
    }

    private fun performGlobalSearch(query: String) {
        router.pushController(GlobalSearchController(query).withFadeTransaction())
    }

    /**
     * Called when an option menu item has been selected by the user.
     *
     * @param item The selected item.
     * @return True if this event has been consumed, false if it has not.
     */
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            // Initialize option to open catalogue settings.
            R.id.action_filter -> {
                val controller =
                    if (showingExtensions) {
                        SettingsExtensionsController()
                    } else SettingsSourcesController()
                router.pushController(
                    (RouterTransaction.with(controller)).popChangeHandler(
                        SettingsSourcesFadeChangeHandler()
                    ).pushChangeHandler(FadeChangeHandler())
                )
            }
            R.id.action_migration_guide -> {
                activity?.openInBrowser(HELP_URL)
            }
            R.id.action_sources_settings -> {
                router.pushController(SettingsBrowseController().withFadeTransaction())
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    /**
     * Called to update adapter containing sources.
     */
    fun setSources(sources: List<IFlexible<*>>, lastUsed: SourceItem?) {
        adapter?.updateDataSet(sources, false)
        setLastUsedSource(lastUsed)
    }

    /**
     * Called to set the last used catalogue at the top of the view.
     */
    fun setLastUsedSource(item: SourceItem?) {
        adapter?.removeAllScrollableHeaders()
        if (item != null) {
            adapter?.addScrollableHeader(item)
            adapter?.addScrollableHeader(LangItem(SourcePresenter.LAST_USED_KEY))
        }
    }

    class SettingsSourcesFadeChangeHandler : FadeChangeHandler()

    @Parcelize
    data class SmartSearchConfig(val origTitle: String, val origMangaId: Long) : Parcelable

    companion object {
        const val HELP_URL = "https://tachiyomi.org/help/guides/source-migration/"
    }
}
