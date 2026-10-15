package com.undal.syl.app.features.vendor.menu

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.undal.syl.app.core.model.Campaign
import com.undal.syl.app.core.model.CampaignDraft
import com.undal.syl.app.core.model.Category
import com.undal.syl.app.core.model.Combo
import com.undal.syl.app.core.model.ComboDraft
import com.undal.syl.app.core.model.ComboRefDraft
import com.undal.syl.app.core.model.CouponDraft
import com.undal.syl.app.core.model.MenuDraft
import com.undal.syl.app.core.model.MenuItem
import com.undal.syl.app.core.model.MenuSnapshot
import com.undal.syl.app.core.model.MenuSort
import com.undal.syl.app.core.model.Subcategory
import com.undal.syl.app.core.model.VendorCoupon
import com.undal.syl.app.core.util.Money
import com.undal.syl.app.core.util.UndalDigits
import com.undal.syl.app.core.util.UndalResult
import com.undal.syl.app.data.repo.MenuRepo
import com.undal.syl.app.data.repo.VendorMenuRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The menu manager (V-049 … V-070).
 *
 * One screen that owns the whole catalogue: sections, dishes, combos, campaigns
 * and coupons. Everything is a live snapshot, so two devices editing the menu at
 * once see each other's writes instead of silently overwriting them, and every
 * save is validated in the draft before it ever reaches Firestore.
 */
@HiltViewModel
class VendorMenuViewModel @Inject constructor(
    private val menuRepo: MenuRepo,
    private val vendorMenuRepo: VendorMenuRepo
) : ViewModel() {

    enum class Tab(val label: String) {
        DISHES("Dishes"),
        SECTIONS("Sections"),
        COMBOS("Combos"),
        OFFERS("Offers")
    }

    /** A category or a subcategory being edited — both are a name and an order. */
    data class SectionDraft(
        val id: String? = null,
        val isCategory: Boolean = true,
        val categoryId: String = "",
        val name: String = "",
        val orderText: String = "0",
        val active: Boolean = true,
        val iconUrl: String = ""
    ) {
        /**
         * The position in the menu. ০ (Bangla zero) and 0 are the same position,
         * and an unreadable value falls back to the top rather than putting the
         * section somewhere nobody asked for.
         */
        val order: Int get() = UndalDigits.toIntOrNull(orderText)?.coerceIn(0, 999) ?: 0
        /**
         * The name is judged by the floor the rules enforce (three characters), so
         * a short one is refused here with a sentence instead of by the database
         * with "Missing or insufficient permissions".
         */
        val error: String?
            get() = com.undal.syl.app.core.util.Validators.menuName(
                if (isCategory) "category name" else "subcategory name",
                name
            )
                ?: when {
                    !isCategory && categoryId.isBlank() -> "Pick the category it belongs to."
                    else -> null
                }
    }

    data class DeleteRequest(
        val kind: Kind,
        val id: String,
        val name: String,
        val ordersAffected: Int? = null
    ) {
        enum class Kind { DISH, SECTION, SUB_SECTION, COMBO, CAMPAIGN, COUPON }
    }

    data class State(
        val loading: Boolean = true,
        val tab: Tab = Tab.DISHES,
        val menu: MenuSnapshot = MenuSnapshot(),
        val campaigns: List<Campaign> = emptyList(),
        val coupons: List<VendorCoupon> = emptyList(),
        val query: String = "",
        val categoryId: String = "",
        val subcategoryId: String = "",
        val sort: MenuSort = MenuSort.RECOMMENDED,
        val bulkMode: Boolean = false,
        val selectedIds: Set<String> = emptySet(),
        val dragIndex: Int = -1,
        val itemDraft: MenuDraft? = null,
        val comboDraft: ComboDraft? = null,
        val campaignDraft: CampaignDraft? = null,
        val couponDraft: CouponDraft? = null,
        val sectionDraft: SectionDraft? = null,
        val dirtyWarning: Boolean = false,
        val uploadProgress: Float? = null,
        val busy: Boolean = false,
        val confirmDelete: DeleteRequest? = null,
        val message: String? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    /**
     * Nothing this ViewModel does may end the process.
     *
     * An exception that escapes a `viewModelScope` job is uncaught, and Android's
     * answer to an uncaught exception is to kill the app — which the person
     * experiences as the app vanishing a few seconds after it opened, then the
     * crash guard reopening it, then the home screen. Every coroutine here carries
     * this handler, so a failed read becomes one sentence where the screen already
     * shows messages.
     */
    private val guard = com.undal.syl.app.core.util.UndalGuards.viewModel("reading the menu") { message ->
        _state.value = _state.value.copy(message = message, busy = false)
    }

    init {
        viewModelScope.launch(guard) {
            menuRepo.observeMenu().collect { menu ->
                val filtered = menu
                _state.value = _state.value.copy(loading = false, menu = filtered)
            }
        }
        viewModelScope.launch(guard) {
            vendorMenuRepo.observeCampaigns().collect { campaigns ->
                _state.value = _state.value.copy(campaigns = campaigns)
            }
        }
        viewModelScope.launch(guard) {
            vendorMenuRepo.observeCoupons().collect { coupons ->
                _state.value = _state.value.copy(coupons = coupons)
            }
        }
    }

    // ------------------------------------------------------------------ browsing

    fun onTab(tab: Tab) {
        _state.value = _state.value.copy(
            tab = tab,
            bulkMode = false,
            selectedIds = emptySet(),
            categoryId = "",
            subcategoryId = ""
        )
    }

    fun onQuery(value: String) {
        _state.value = _state.value.copy(query = value)
    }

    fun onCategoryFilter(id: String) {
        _state.value = _state.value.copy(categoryId = if (_state.value.categoryId == id) "" else id, subcategoryId = "")
    }

    fun onSubcategoryFilter(id: String) {
        _state.value = _state.value.copy(subcategoryId = if (_state.value.subcategoryId == id) "" else id)
    }

    fun onSort(sort: MenuSort) {
        _state.value = _state.value.copy(sort = sort)
    }

    /** Dishes the manager should show right now, before the sort is applied. */
    fun visibleDishes(): List<MenuItem> {
        val state = _state.value
        return state.menu.items
            .filter { state.categoryId.isBlank() || it.categoryId == state.categoryId }
            .filter { state.subcategoryId.isBlank() || it.subcategoryId == state.subcategoryId }
            .filter { it.matches(state.query) }
            .sortedWith(
                when (state.sort) {
                    MenuSort.PRICE_LOW -> compareBy { it.payablePaisa }
                    MenuSort.PRICE_HIGH -> compareByDescending { it.payablePaisa }
                    MenuSort.POPULAR -> compareByDescending { it.orderCount }
                    MenuSort.RECOMMENDED -> compareByDescending<MenuItem> { it.featured }.thenByDescending { it.orderCount }
                }
            )
    }

    fun countFor(categoryId: String): Int = _state.value.menu.items.count { it.categoryId == categoryId }

    fun subcategoriesOf(categoryId: String): List<Subcategory> =
        _state.value.menu.subcategories.filter { it.categoryId == categoryId }.sortedBy { it.order }

    // ----------------------------------------------------------------- bulk mode

    fun toggleBulkMode() {
        val bulk = !_state.value.bulkMode
        _state.value = _state.value.copy(bulkMode = bulk, selectedIds = emptySet())
    }

    fun toggleSelected(itemId: String) {
        val selected = _state.value.selectedIds.toMutableSet()
        if (!selected.add(itemId)) selected.remove(itemId)
        _state.value = _state.value.copy(selectedIds = selected)
    }

    fun selectAllVisible() {
        _state.value = _state.value.copy(selectedIds = visibleDishes().map { it.id }.toSet())
    }

    fun clearSelection() {
        _state.value = _state.value.copy(selectedIds = emptySet())
    }

    fun applyBulk(available: Boolean) {
        val ids = _state.value.selectedIds.toList()
        if (ids.isEmpty()) return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch(guard) {
            val result = vendorMenuRepo.setAvailabilityBulk(ids, available)
            _state.value = _state.value.copy(
                busy = false,
                selectedIds = emptySet(),
                bulkMode = false,
                message = when (result) {
                    is UndalResult.Success ->
                        "${ids.size} dish(es) are now ${if (available) "available" else "sold out"}."

                    is UndalResult.Failure -> result.message
                }
            )
        }
    }

    // -------------------------------------------------------------------- dishes

    fun startNewDish() {
        val category = _state.value.categoryId.ifBlank { _state.value.menu.categories.firstOrNull()?.id.orEmpty() }
        _state.value = _state.value.copy(
            itemDraft = MenuDraft(categoryId = category),
            dirtyWarning = false
        )
    }

    fun editDish(item: MenuItem) {
        _state.value = _state.value.copy(itemDraft = MenuDraft.of(item), dirtyWarning = false)
    }

    fun onItemDraftChange(draft: MenuDraft) {
        _state.value = _state.value.copy(itemDraft = draft)
    }

    /** Backing out of a dirty editor asks first (V-054). */
    fun requestCloseEditor() {
        val draft = _state.value.itemDraft ?: return
        val original = draft.id?.let { _state.value.menu.dish(it) }
        val dirty = when {
            original == null -> draft.id == null && !draft.sameAsEmpty()
            else -> !draft.sameAs(original)
        }
        if (dirty) {
            _state.value = _state.value.copy(dirtyWarning = true)
        } else {
            _state.value = _state.value.copy(itemDraft = null)
        }
    }

    /** "Keep editing" — closes the warning and leaves the draft exactly as it was. */
    fun cancelDirtyWarning() {
        _state.value = _state.value.copy(dirtyWarning = false)
    }

    fun discardEditor() {
        _state.value = _state.value.copy(itemDraft = null, dirtyWarning = false)
    }

    fun uploadDishImage(uri: Uri) {
        _state.value = _state.value.copy(uploadProgress = 0f)
        viewModelScope.launch(guard) {
            when (val result = vendorMenuRepo.uploadImage(uri) { fraction ->
                _state.value = _state.value.copy(uploadProgress = fraction)
            }) {
                is UndalResult.Success -> _state.value = _state.value.copy(
                    uploadProgress = null,
                    itemDraft = _state.value.itemDraft?.copy(imageUrl = result.value),
                    message = "Photo uploaded."
                )

                is UndalResult.Failure -> _state.value = _state.value.copy(
                    uploadProgress = null,
                    message = result.message
                )
            }
        }
    }

    fun saveDish() {
        val draft = _state.value.itemDraft ?: return
        if (!draft.isValid) {
            _state.value = _state.value.copy(
                message = draft.nameError ?: draft.priceError ?: draft.compareError ?: draft.categoryError
            )
            return
        }
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch(guard) {
            val result = vendorMenuRepo.saveItem(draft)
            _state.value = _state.value.copy(
                busy = false,
                itemDraft = if (result is UndalResult.Success) null else draft,
                message = when (result) {
                    is UndalResult.Success ->
                        if (draft.isNew) "${draft.name.trim()} is on the menu." else "Saved ${draft.name.trim()}."

                    is UndalResult.Failure -> result.message
                }
            )
        }
    }

    fun toggleAvailability(item: MenuItem) {
        viewModelScope.launch(guard) {
            val result = vendorMenuRepo.setItemAvailability(item.id, !item.available)
            if (result is UndalResult.Failure) {
                _state.value = _state.value.copy(message = result.message)
            }
        }
    }

    fun toggleFeatured(item: MenuItem) {
        viewModelScope.launch(guard) {
            val result = vendorMenuRepo.setItemFeatured(item.id, !item.featured)
            _state.value = _state.value.copy(
                message = when (result) {
                    is UndalResult.Success -> if (!item.featured) {
                        "${item.name} sits in the home highlight."
                    } else {
                        "${item.name} left the home highlight."
                    }

                    is UndalResult.Failure -> result.message
                }
            )
        }
    }

    // ------------------------------------------------------------------ sections

    fun startNewSection() {
        _state.value = _state.value.copy(
            sectionDraft = SectionDraft(orderText = (_state.value.menu.categories.size).toString())
        )
    }

    fun editSection(category: Category) {
        _state.value = _state.value.copy(
            sectionDraft = SectionDraft(
                id = category.id,
                isCategory = true,
                name = category.name,
                orderText = category.order.toString(),
                active = category.active,
                iconUrl = category.iconUrl
            )
        )
    }

    fun startNewSubcategory(categoryId: String) {
        _state.value = _state.value.copy(
            sectionDraft = SectionDraft(
                isCategory = false,
                categoryId = categoryId,
                orderText = subcategoriesOf(categoryId).size.toString()
            )
        )
    }

    fun editSubcategory(subcategory: Subcategory) {
        _state.value = _state.value.copy(
            sectionDraft = SectionDraft(
                id = subcategory.id,
                isCategory = false,
                categoryId = subcategory.categoryId,
                name = subcategory.name,
                orderText = subcategory.order.toString(),
                active = subcategory.active
            )
        )
    }

    fun onSectionDraftChange(draft: SectionDraft) {
        _state.value = _state.value.copy(sectionDraft = draft)
    }

    fun closeSectionEditor() {
        _state.value = _state.value.copy(sectionDraft = null)
    }

    fun uploadSectionIcon(uri: Uri) {
        val draft = _state.value.sectionDraft ?: return
        _state.value = _state.value.copy(uploadProgress = 0f)
        viewModelScope.launch(guard) {
            when (val result = vendorMenuRepo.uploadImage(uri) { fraction ->
                _state.value = _state.value.copy(uploadProgress = fraction)
            }) {
                is UndalResult.Success -> _state.value = _state.value.copy(
                    uploadProgress = null,
                    sectionDraft = draft.copy(iconUrl = result.value),
                    message = "Section image uploaded."
                )

                is UndalResult.Failure -> _state.value = _state.value.copy(
                    uploadProgress = null,
                    message = result.message
                )
            }
        }
    }

    fun saveSection() {
        val draft = _state.value.sectionDraft ?: return
        if (draft.error != null) {
            _state.value = _state.value.copy(message = draft.error)
            return
        }
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch(guard) {
            val result = if (draft.isCategory) {
                vendorMenuRepo.saveCategory(
                    id = draft.id,
                    name = draft.name,
                    order = draft.order,
                    active = draft.active,
                    iconUrl = draft.iconUrl
                )
            } else {
                vendorMenuRepo.saveSubcategory(
                    id = draft.id,
                    categoryId = draft.categoryId,
                    name = draft.name,
                    order = draft.order,
                    active = draft.active
                )
            }
            _state.value = _state.value.copy(
                busy = false,
                sectionDraft = if (result is UndalResult.Success) null else draft,
                message = when (result) {
                    is UndalResult.Success -> "Saved ${draft.name.trim()}."
                    is UndalResult.Failure -> result.message
                }
            )
        }
    }

    /** Real drag reorder: the drop writes the whole order field in one batch (V-051). */
    fun onDragStart(index: Int) {
        _state.value = _state.value.copy(dragIndex = index)
    }

    fun onDragSwap(from: Int, to: Int, isCategory: Boolean) {
        val state = _state.value
        val ids = if (isCategory) {
            state.menu.categories.sortedBy { it.order }.map { it.id }
        } else {
            state.menu.subcategories
                .filter { it.categoryId == state.categoryId }
                .sortedBy { it.order }
                .map { it.id }
        }
        if (from !in ids.indices || to !in ids.indices) return
        val reordered = ids.toMutableList()
        val moved = reordered.removeAt(from)
        reordered.add(to, moved)
        _state.value = _state.value.copy(dragIndex = -1)
        viewModelScope.launch(guard) {
            val result = if (isCategory) {
                vendorMenuRepo.reorderCategories(reordered)
            } else {
                vendorMenuRepo.reorderSubcategories(reordered)
            }
            if (result is UndalResult.Failure) {
                _state.value = _state.value.copy(message = result.message)
            }
        }
    }

    // -------------------------------------------------------------------- combos

    fun startNewCombo() {
        _state.value = _state.value.copy(comboDraft = ComboDraft())
    }

    fun editCombo(combo: Combo) {
        _state.value = _state.value.copy(comboDraft = ComboDraft.of(combo, _state.value.menu.items))
    }

    fun onComboDraftChange(draft: ComboDraft) {
        _state.value = _state.value.copy(comboDraft = draft)
    }

    fun addComboRef(item: MenuItem) {
        val draft = _state.value.comboDraft ?: return
        if (draft.refs.any { it.menuItemId == item.id }) return
        val refs = draft.refs + ComboRefDraft(
            menuItemId = item.id,
            qty = 1,
            name = item.name,
            unitAmountPaisa = item.payablePaisa
        )
        // A combo starts at "no saving yet" — the kitchen decides the cut.
        val suggested = refs.sumOf { it.lineTotalPaisa }
        _state.value = _state.value.copy(
            comboDraft = draft.copy(
                refs = refs,
                priceText = draft.priceText.ifBlank { Money.formatPlain(suggested) }
            )
        )
    }

    fun removeComboRef(itemId: String) {
        val draft = _state.value.comboDraft ?: return
        _state.value = _state.value.copy(comboDraft = draft.copy(refs = draft.refs.filterNot { it.menuItemId == itemId }))
    }

    fun changeComboQty(itemId: String, delta: Int) {
        val draft = _state.value.comboDraft ?: return
        val refs = draft.refs.map { ref ->
            if (ref.menuItemId == itemId) ref.copy(qty = (ref.qty + delta).coerceIn(1, 10)) else ref
        }
        _state.value = _state.value.copy(comboDraft = draft.copy(refs = refs))
    }

    fun closeComboEditor() {
        _state.value = _state.value.copy(comboDraft = null)
    }

    fun uploadComboImage(uri: Uri) {
        val draft = _state.value.comboDraft ?: return
        _state.value = _state.value.copy(uploadProgress = 0f)
        viewModelScope.launch(guard) {
            when (val result = vendorMenuRepo.uploadImage(uri) { fraction ->
                _state.value = _state.value.copy(uploadProgress = fraction)
            }) {
                is UndalResult.Success -> _state.value = _state.value.copy(
                    uploadProgress = null,
                    comboDraft = draft.copy(imageUrl = result.value),
                    message = "Combo photo uploaded."
                )

                is UndalResult.Failure -> _state.value = _state.value.copy(
                    uploadProgress = null,
                    message = result.message
                )
            }
        }
    }

    fun saveCombo() {
        val draft = _state.value.comboDraft ?: return
        if (!draft.isValid) {
            _state.value = _state.value.copy(message = draft.error)
            return
        }
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch(guard) {
            val result = vendorMenuRepo.saveCombo(draft)
            _state.value = _state.value.copy(
                busy = false,
                comboDraft = if (result is UndalResult.Success) null else draft,
                message = when (result) {
                    is UndalResult.Success -> "${draft.name.trim()} is a combo now."
                    is UndalResult.Failure -> result.message
                }
            )
        }
    }

    fun toggleCombo(combo: Combo) {
        viewModelScope.launch(guard) {
            val result = vendorMenuRepo.setComboAvailability(combo.id, !combo.available)
            if (result is UndalResult.Failure) _state.value = _state.value.copy(message = result.message)
        }
    }

    // ------------------------------------------------------------------- offers

    fun startNewCampaign() {
        _state.value = _state.value.copy(campaignDraft = CampaignDraft())
    }

    fun editCampaign(campaign: Campaign) {
        _state.value = _state.value.copy(campaignDraft = CampaignDraft.of(campaign))
    }

    fun onCampaignDraftChange(draft: CampaignDraft) {
        _state.value = _state.value.copy(campaignDraft = draft)
    }

    fun closeCampaignEditor() {
        _state.value = _state.value.copy(campaignDraft = null)
    }

    fun uploadCampaignBanner(uri: Uri) {
        val draft = _state.value.campaignDraft ?: return
        _state.value = _state.value.copy(uploadProgress = 0f)
        viewModelScope.launch(guard) {
            when (val result = vendorMenuRepo.uploadImage(uri) { fraction ->
                _state.value = _state.value.copy(uploadProgress = fraction)
            }) {
                is UndalResult.Success -> _state.value = _state.value.copy(
                    uploadProgress = null,
                    campaignDraft = draft.copy(bannerUrl = result.value),
                    message = "Banner uploaded."
                )

                is UndalResult.Failure -> _state.value = _state.value.copy(
                    uploadProgress = null,
                    message = result.message
                )
            }
        }
    }

    fun saveCampaign() {
        val draft = _state.value.campaignDraft ?: return
        if (!draft.isValid) {
            _state.value = _state.value.copy(message = draft.error)
            return
        }
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch(guard) {
            val result = vendorMenuRepo.saveCampaign(draft)
            _state.value = _state.value.copy(
                busy = false,
                campaignDraft = if (result is UndalResult.Success) null else draft,
                message = when (result) {
                    is UndalResult.Success -> "${draft.title.trim()} is saved."
                    is UndalResult.Failure -> result.message
                }
            )
        }
    }

    fun toggleCampaign(campaign: Campaign) {
        viewModelScope.launch(guard) {
            val result = vendorMenuRepo.setCampaignActive(campaign.id, !campaign.active)
            _state.value = _state.value.copy(
                message = when (result) {
                    is UndalResult.Success -> if (campaign.active) "Campaign switched off." else "Campaign is live."
                    is UndalResult.Failure -> result.message
                }
            )
        }
    }

    fun startNewCoupon() {
        _state.value = _state.value.copy(couponDraft = CouponDraft())
    }

    fun editCoupon(coupon: VendorCoupon) {
        _state.value = _state.value.copy(couponDraft = CouponDraft.of(coupon))
    }

    fun onCouponDraftChange(draft: CouponDraft) {
        _state.value = _state.value.copy(couponDraft = draft)
    }

    fun closeCouponEditor() {
        _state.value = _state.value.copy(couponDraft = null)
    }

    fun saveCoupon() {
        val draft = _state.value.couponDraft ?: return
        if (!draft.isValid) {
            // The sheet's own first wrong field, whatever it is: a code, a value, a
            // cap, a window or a date already passed. Chaining two of them by hand
            // left the other three refusing in silence.
            _state.value = _state.value.copy(message = draft.firstError)
            return
        }
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch(guard) {
            val result = vendorMenuRepo.saveCoupon(draft)
            _state.value = _state.value.copy(
                busy = false,
                couponDraft = if (result is UndalResult.Success) null else draft,
                message = when (result) {
                    is UndalResult.Success -> "${draft.normalisedCode} is ready to share."
                    is UndalResult.Failure -> result.message
                }
            )
        }
    }

    fun toggleCoupon(coupon: VendorCoupon) {
        viewModelScope.launch(guard) {
            val result = vendorMenuRepo.setCouponActive(coupon.code, !coupon.active)
            _state.value = _state.value.copy(
                message = when (result) {
                    is UndalResult.Success -> if (coupon.active) {
                        "${coupon.code} is switched off."
                    } else {
                        "${coupon.code} is accepting uses again."
                    }

                    is UndalResult.Failure -> result.message
                }
            )
        }
    }

    // ------------------------------------------------------------------- deletes

    fun askDeleteDish(item: MenuItem) {
        _state.value = _state.value.copy(
            confirmDelete = DeleteRequest(DeleteRequest.Kind.DISH, item.id, item.name, null)
        )
        viewModelScope.launch(guard) {
            val used = vendorMenuRepo.recentOrdersUsing(item.name)
            val current = _state.value.confirmDelete
            if (current != null && current.id == item.id) {
                _state.value = _state.value.copy(confirmDelete = current.copy(ordersAffected = used))
            }
        }
    }

    fun askDeleteSection(category: Category) {
        _state.value = _state.value.copy(
            confirmDelete = DeleteRequest(DeleteRequest.Kind.SECTION, category.id, category.name)
        )
    }

    fun askDeleteSubcategory(subcategory: Subcategory) {
        _state.value = _state.value.copy(
            confirmDelete = DeleteRequest(DeleteRequest.Kind.SUB_SECTION, subcategory.id, subcategory.name)
        )
    }

    fun askDeleteCombo(combo: Combo) {
        _state.value = _state.value.copy(
            confirmDelete = DeleteRequest(DeleteRequest.Kind.COMBO, combo.id, combo.name)
        )
    }

    fun askDeleteCampaign(campaign: Campaign) {
        _state.value = _state.value.copy(
            confirmDelete = DeleteRequest(DeleteRequest.Kind.CAMPAIGN, campaign.id, campaign.title)
        )
    }

    fun askDeleteCoupon(coupon: VendorCoupon) {
        _state.value = _state.value.copy(
            confirmDelete = DeleteRequest(DeleteRequest.Kind.COUPON, coupon.code, coupon.code)
        )
    }

    fun dismissDelete() {
        _state.value = _state.value.copy(confirmDelete = null)
    }

    fun confirmDelete() {
        val request = _state.value.confirmDelete ?: return
        _state.value = _state.value.copy(confirmDelete = null, busy = true)
        viewModelScope.launch(guard) {
            val result: UndalResult<Unit> = when (request.kind) {
                DeleteRequest.Kind.DISH -> vendorMenuRepo.deleteItem(request.id)
                DeleteRequest.Kind.SECTION -> vendorMenuRepo.deleteCategory(request.id)
                DeleteRequest.Kind.SUB_SECTION -> vendorMenuRepo.deleteSubcategory(request.id)
                DeleteRequest.Kind.COMBO -> vendorMenuRepo.deleteCombo(request.id)
                DeleteRequest.Kind.CAMPAIGN -> vendorMenuRepo.deleteCampaign(request.id)
                DeleteRequest.Kind.COUPON -> vendorMenuRepo.deleteCoupon(request.id)
            }
            _state.value = _state.value.copy(
                busy = false,
                message = when (result) {
                    is UndalResult.Success -> "${request.name} is gone from the menu."
                    is UndalResult.Failure -> result.message
                }
            )
        }
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }

    /** A dish that has never been touched has nothing worth warning about. */
    private fun MenuDraft.sameAsEmpty(): Boolean =
        name.isBlank() && description.isBlank() && priceText.isBlank() && compareText.isBlank() &&
            tagsText.isBlank() && imageUrl.isBlank() && subcategoryId.isBlank()

}
