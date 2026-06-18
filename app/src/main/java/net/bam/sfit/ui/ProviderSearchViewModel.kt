package net.bam.sfit.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.bam.sfit.data.AppRepository
import net.bam.sfit.data.BarcodeFood
import net.bam.sfit.data.ExternalProvider
import net.bam.sfit.data.SparkyApi

data class ProviderSearchState(
    val providers: List<ExternalProvider> = emptyList(),
    val selected: ExternalProvider? = null,
    val query: String = "",
    val loading: Boolean = false,
    val loadingProviders: Boolean = false,    // provider list fetch in flight
    val providersLoaded: Boolean = false,     // a fetch completed (success or empty)
    val results: List<BarcodeFood> = emptyList(),
    val searched: Boolean = false,
    val importingKey: String? = null,    // provider_external_id being imported
    val message: String? = null,
)

/** Search any configured food provider (Open Food Facts, USDA, Swiss, …) via
 *  the generic /v2/foods/search API and import a hit into the food library. */
class ProviderSearchViewModel(private val repo: AppRepository) : ViewModel() {
    private val _state = MutableStateFlow(ProviderSearchState())
    val state: StateFlow<ProviderSearchState> = _state.asStateFlow()

    init {
        loadProviders()
    }

    /** Fetch the provider list. Public + idempotent so the screen can retry on open:
     *  the VM is activity-scoped, so a one-time failure here (a transient tailnet blip,
     *  the server briefly unreachable, settings not yet ready at app start) must not
     *  wedge the list for the whole session. On failure we surface the real error rather
     *  than silently showing "No food providers configured". */
    fun loadProviders() {
        if (_state.value.loadingProviders) return
        viewModelScope.launch {
            val s = repo.store.settings.first()
            if (!s.isConfigured) {
                _state.update { it.copy(message = "Set the Server URL and API key in Settings first.") }
                return@launch
            }
            _state.update { it.copy(loadingProviders = true) }
            try {
                val providers = SparkyApi(s.baseUrl, s.apiKey).foodProviders()
                _state.update {
                    it.copy(
                        providers = providers,
                        selected = it.selected ?: providers.preferred(),
                        loadingProviders = false,
                        providersLoaded = true,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(loadingProviders = false, message = e.message ?: "Couldn't load providers")
                }
            }
        }
    }

    fun selectProvider(p: ExternalProvider) {
        if (p.id == _state.value.selected?.id) return
        _state.update { it.copy(selected = p, results = emptyList(), searched = false) }
        if (_state.value.query.isNotBlank()) search()
    }

    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    fun search() {
        val provider = _state.value.selected ?: return
        val q = _state.value.query.trim()
        if (q.isBlank() || _state.value.loading) return
        viewModelScope.launch {
            val s = repo.store.settings.first()
            if (!s.isConfigured) {
                _state.update { it.copy(message = "Not configured") }
                return@launch
            }
            _state.update { it.copy(loading = true, message = null, searched = true) }
            try {
                val results = SparkyApi(s.baseUrl, s.apiKey)
                    .searchProvider(provider.providerType, q, provider.id)
                _state.update { it.copy(loading = false, results = results) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(loading = false, results = emptyList(), message = e.message ?: "Search failed")
                }
            }
        }
    }

    /** Import a result into the library (then refresh shared data). */
    fun import(food: BarcodeFood) {
        if (_state.value.importingKey != null) return
        viewModelScope.launch {
            val s = repo.store.settings.first()
            if (!s.isConfigured) return@launch
            _state.update { it.copy(importingKey = food.providerExternalId) }
            try {
                val ok = SparkyApi(s.baseUrl, s.apiKey).addFood(food)
                _state.update {
                    it.copy(
                        importingKey = null,
                        message = if (ok) "Added ${food.name} to library" else "Couldn't import ${food.name}",
                    )
                }
                if (ok) repo.refresh()
            } catch (e: Exception) {
                _state.update { it.copy(importingKey = null, message = e.message ?: "Import failed") }
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}

/** Prefer Open Food Facts as the default provider, else the first one. */
private fun List<ExternalProvider>.preferred(): ExternalProvider? =
    firstOrNull { it.providerType == "openfoodfacts" } ?: firstOrNull()
