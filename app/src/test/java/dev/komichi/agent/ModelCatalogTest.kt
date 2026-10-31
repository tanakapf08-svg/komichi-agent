package dev.komichi.agent

import dev.komichi.agent.agent.ModelCatalog
import dev.komichi.agent.agent.ModelChoice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ModelCatalogTest {
    @Test fun coalescesLoadsAndRestoresSelectionForTheAccount() = runTest {
        var requests = 0
        val response = CompletableDeferred<List<ModelChoice>>()
        val saved = mutableMapOf("account-a" to "second")
        val catalog = ModelCatalog(this, { requests++; response.await() }, { saved[it] ?: "" }, { account,model -> saved[account] = model }, { "failed" })
        catalog.refresh("account-a"); catalog.refresh("account-a"); runCurrent()
        assertEquals(1,requests); assertTrue(catalog.state.value.loading)
        response.complete(listOf(ModelChoice("first","First"),ModelChoice("second","Second"))); runCurrent()
        assertEquals("second",catalog.state.value.selected)
        catalog.select("first"); assertEquals("first",saved["account-a"])
        catalog.select("unknown"); assertEquals("first",catalog.state.value.selected)
    }
    @Test fun discardsLateResultsAfterAnAccountSwitch() = runTest {
        val oldResponse = CompletableDeferred<List<ModelChoice>>()
        val catalog = ModelCatalog(this, { account ->
            if (account == "old") withContext(NonCancellable) { oldResponse.await() }
            else listOf(ModelChoice("new-model","New model"))
        }, { "" }, { _,_ -> }, { "failed" })
        catalog.refresh("old"); runCurrent(); catalog.clear(); catalog.refresh("new"); runCurrent()
        oldResponse.complete(listOf(ModelChoice("old-model","Private old catalog"))); runCurrent()
        assertEquals("new",catalog.state.value.accountId); assertEquals("new-model",catalog.state.value.selected)
        assertFalse(catalog.state.value.models.any { it.id == "old-model" })
    }
    @Test fun failedRefreshClearsTheSelectableCatalogAndCanBeRetried() = runTest {
        var fail = false
        val catalog = ModelCatalog(this, { if (fail) error("offline") else listOf(ModelChoice("one","One")) }, { "missing-model" }, { _,_ -> }, { "接続エラー" })
        catalog.refresh("account"); runCurrent(); assertEquals("one",catalog.state.value.selected)
        fail = true; catalog.refresh("account"); runCurrent()
        assertTrue(catalog.state.value.models.isEmpty()); assertEquals("",catalog.state.value.selected); assertEquals("接続エラー",catalog.state.value.error)
        fail = false; catalog.refresh("account"); runCurrent(); assertEquals("one",catalog.state.value.selected)
    }
}
