package dev.komichi.agent.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ModelCatalogState(val accountId: String = "", val models: List<ModelChoice> = emptyList(),
    val selected: String = "", val loading: Boolean = false, val error: String? = null)

/** One catalog per active registration. Late results from a previous account are discarded. */
class ModelCatalog(private val scope: CoroutineScope, private val load: suspend (String) -> List<ModelChoice>,
    private val remembered: (String) -> String, private val remember: (String, String) -> Unit,
    private val errorText: (Exception) -> String) {
    private val mutable = MutableStateFlow(ModelCatalogState())
    val state = mutable.asStateFlow()
    private var request: Job? = null
    private var generation = 0
    fun refresh(accountId: String) {
        if (mutable.value.accountId == accountId && mutable.value.loading) return
        val previous = mutable.value.takeIf { it.accountId == accountId }
        request?.cancel(); val ticket = ++generation
        mutable.value = ModelCatalogState(accountId, previous?.models ?: emptyList(), previous?.selected ?: "", loading = true)
        request = scope.launch {
            try {
                val models = load(accountId)
                if (ticket != generation) return@launch
                val preferred = remembered(accountId)
                val selected = models.firstOrNull { it.id == preferred }?.id ?: models.firstOrNull()?.id ?: ""
                if (selected.isNotBlank()) remember(accountId, selected)
                mutable.value = ModelCatalogState(accountId, models, selected,
                    error = if (models.isEmpty()) "利用可能なモデルがありません。有料APIには切り替えません" else null)
            } catch (_: CancellationException) {
                if (ticket == generation) mutable.value = mutable.value.copy(loading = false)
            } catch (e: Exception) {
                if (ticket == generation) mutable.value = ModelCatalogState(accountId, error = errorText(e))
            }
        }
    }
    fun select(id: String) {
        val value = mutable.value
        if (value.loading || value.models.none { it.id == id }) return
        remember(value.accountId, id); mutable.value = value.copy(selected = id)
    }
    fun clear() { generation++; request?.cancel(); request = null; mutable.value = ModelCatalogState() }
    fun cancelRefresh() { generation++; request?.cancel(); request = null; mutable.value = mutable.value.copy(loading = false) }
}
