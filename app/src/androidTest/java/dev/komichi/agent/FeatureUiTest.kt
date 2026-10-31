package dev.komichi.agent

import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.komichi.agent.agent.RunPhase
import dev.komichi.agent.agent.RunProgress
import dev.komichi.agent.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import android.content.ContentValues
import android.provider.MediaStore
import android.os.Build
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import dev.komichi.agent.auth.LoginService
import dev.komichi.agent.device.TextDocuments
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class FeatureUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun vm(): AgentViewModel = ViewModelProvider(compose.activity)[AgentViewModel::class.java]
    private fun unique() = "tests/${UUID.randomUUID()}.md"
    @After fun stopUnfinishedTestRuns() {
        val model = vm()
        compose.runOnIdle { if(model.busy) model.stop() }
        compose.waitUntil(10_000) { !model.busy }
    }
    @Test fun approvalShowsRunningAndAWorkspaceWriteIsNotPerformedBeforeConsent() {
        val model = vm(); val path = unique()
        try {
            compose.runOnIdle { model.manualTool("workspace_write",obj("path" to path,"content" to "synthetic UI text","expected_version" to "")) }
            compose.onNodeWithText("動作中").assertIsDisplayed()
            compose.onNodeWithText("承認待ち").assertIsDisplayed()
            assertTrue(runCatching { model.library.read(path) }.isFailure)
            compose.onNodeWithText("今回だけ許可").performClick()
            compose.waitUntil(10_000) { !model.busy }
            assertEquals("synthetic UI text",model.library.read(path).text)
            assertEquals(RunPhase.COMPLETED,model.runProgress.value.phase)
            compose.onNodeWithText("動作中").assertDoesNotExist()
            compose.onNodeWithText("ライブラリ").performClick()
            compose.onNodeWithText(path).assertIsDisplayed()
        } finally { compose.runOnIdle { runCatching { model.library.read(path).let { model.library.remove(it.path,it.version) } }; model.refreshLibrary() } }
    }
    @Test fun denyingAnOperationStopsWithoutWritingAFile() {
        val model = vm(); val path = unique()
        compose.runOnIdle { model.manualTool("workspace_write",obj("path" to path,"content" to "not saved","expected_version" to "")) }
        compose.onNodeWithText("拒否").performClick()
        compose.waitUntil(10_000) { !model.busy }
        assertTrue(runCatching { model.library.read(path) }.isFailure)
        assertEquals(RunPhase.CANCELLED,model.runProgress.value.phase)
        compose.onNodeWithText("動作中").assertDoesNotExist()
    }
    @Test fun aDisabledRoutineStepStopsBeforeAnyLaterWrite() {
        val model = vm(); val path = unique(); val previous = model.preferences.toolEnabled("workspace_list")
        try {
            val steps = LibraryPolicy.routine(array(listOf(obj("tool" to "workspace_list","arguments" to obj("path" to ".")),obj("tool" to "workspace_write","arguments" to obj("path" to path,"content" to "must not run","expected_version" to "")))).toString())
            compose.runOnIdle { model.preferences.setToolEnabled("workspace_list",false); model.runRoutine(SavedRoutine("test","Test routine",steps)) }
            compose.onNode(hasText("ワークフローを実行: Test routine") and hasAnyAncestor(isDialog())).assertIsDisplayed()
            compose.onNodeWithText("今回だけ許可").performClick()
            compose.waitUntil(10_000) { !model.busy }
            assertEquals(RunPhase.FAILED,model.runProgress.value.phase)
            assertTrue(runCatching { model.library.read(path) }.isFailure)
        } finally { compose.runOnIdle { model.preferences.setToolEnabled("workspace_list",previous); model.refreshStatus() } }
    }
    @Test fun workspaceEditorSavesEncryptedTextAndSurvivesActivityRecreation() {
        val model = vm(); val path = unique()
        try {
            compose.onNodeWithText("ライブラリ").performClick()
            compose.onNodeWithText("新規作成").performClick()
            compose.onNode(hasSetTextAction() and hasText("作業スペース内のパス")).performTextReplacement(path)
            compose.onNode(hasSetTextAction() and hasText("本文（UTF-8テキスト）")).performTextInput("# Local note\nOnly synthetic data.")
            compose.onNodeWithText("保存").performClick()
            compose.onNodeWithText(path).assertIsDisplayed()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText(path).assertIsDisplayed()
            val storage = File(compose.activity.noBackupFilesDir,"library.vault").readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(storage.contains("Only synthetic data."))
        } finally { compose.runOnIdle { runCatching { model.library.read(path).let { model.library.remove(it.path,it.version) } }; model.refreshLibrary() } }
    }
    @Test fun statusCardShowsStoppingAndDoesNotStayRunningAfterCancellation() {
        val state = mutableStateOf(RunProgress(RunPhase.APPROVAL,"Confirm operation",true,System.currentTimeMillis()-2500))
        compose.activityRule.scenario.onActivity { activity -> activity.setContent { MaterialTheme {
            RunStatusCard(state.value,false,state.value.detail) { state.value = state.value.copy(phase = RunPhase.STOPPING,detail = "Stopping") }
        } } }
        compose.onNodeWithText("動作中").assertIsDisplayed()
        compose.onNodeWithTag("runElapsed").assertIsDisplayed()
        compose.onNodeWithText("停止").performClick()
        compose.onNodeWithText("停止処理中").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(phase = RunPhase.CANCELLED,active = false,detail = "Stopped",finishedAt = System.currentTimeMillis()) }
        compose.onNodeWithText("動作中").assertDoesNotExist()
        compose.onNodeWithText("停止済み · Stopped").assertIsDisplayed()
    }
    @Test fun cancellingServiceStartupAndThenRunningFastTasksDoesNotCrashLater() {
        val model = vm()
        repeat(3) {
            compose.runOnIdle { model.manualTool("workspace_list",obj("path" to ".")); model.stop() }
            compose.waitUntil(10_000) { !model.busy }
        }
        repeat(3) {
            compose.runOnIdle { model.manualTool("workspace_list",obj("path" to ".")) }
            compose.waitUntil(10_000) { !model.busy }
            assertEquals(RunPhase.COMPLETED,model.runProgress.value.phase)
        }
        // Android's foreground-service deadline is asynchronous. Staying alive
        // past it catches the real crash that an immediate UI assertion missed.
        android.os.SystemClock.sleep(6000)
        compose.onNodeWithText("チャット").assertIsDisplayed()
        assertFalse(model.runProgress.value.active)
    }
    @Test fun loginNotificationStartupCanBeCancelledWithoutAnAccountOrNetworkCall() {
        val finished = AtomicBoolean(false)
        compose.runOnIdle { compose.activity.lifecycleScope.launch {
            repeat(3) { val job = launch(start = CoroutineStart.UNDISPATCHED) { LoginService.startAndAwait(compose.activity) }; job.cancelAndJoin() }
            repeat(3) { val session = LoginService.startAndAwait(compose.activity); LoginService.finish(session) }
            finished.set(true)
        } }
        compose.waitUntil(15_000) { finished.get() }
        android.os.SystemClock.sleep(6000)
        compose.onNodeWithText("チャット").assertIsDisplayed()
    }
    @Test fun selectedTextTransferPreservesUtf8AndRejectsBinaryAndOversizedContent() = runBlocking {
        val file = File(compose.activity.cacheDir,"utf8-transfer-${UUID.randomUUID()}.txt")
        try {
            val uri = android.net.Uri.fromFile(file)
            TextDocuments.write(compose.activity,uri,"日本語のテキスト 🙂\n# UTF-8")
            assertEquals("日本語のテキスト 🙂\n# UTF-8",TextDocuments.read(compose.activity,uri))
            file.writeBytes(byteArrayOf(0,1,2))
            assertTrue(runCatching { TextDocuments.read(compose.activity,uri) }.isFailure)
            file.writeText("x".repeat(65000))
            assertTrue(runCatching { TextDocuments.read(compose.activity,uri) }.isFailure)
            assertEquals(65000,TextDocuments.read(compose.activity,uri,256000).length)
        } finally { file.delete() }
    }
    @Test fun browserOpeningCannotBypassTheSelectedAppScope() {
        val model = vm(); val previous = model.preferences.allowed(); val full = model.preferences.fullAccessEnabled()
        try {
            compose.runOnIdle { previous.forEach { model.preferences.allow(it,false) }; model.preferences.setFullAccess(false); model.manualTool("open_url",obj("url" to "https://example.com")) }
            compose.waitUntil(10_000) { !model.busy }
            assertEquals(RunPhase.FAILED,model.runProgress.value.phase)
            assertTrue(model.status.contains("ブラウザ"))
            compose.onNodeWithText("チャット").assertIsDisplayed()
        } finally { compose.runOnIdle { previous.forEach { model.preferences.allow(it,true) }; model.preferences.setFullAccess(full); model.refreshStatus() } }
    }
    @Test fun captureSyntheticScreensForVisualReview() {
        org.junit.Assume.assumeTrue(Build.VERSION.SDK_INT >= 29)
        val model = vm(); val path = "notes/UI-review.md"; val prior = runCatching { model.library.read(path) }.getOrNull(); val theme = model.preferences.theme()
        try {
            compose.activityRule.scenario.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            compose.runOnIdle { model.preferences.setTheme("light"); model.refreshStatus(); model.newConversation(); model.library.write(path,"# Workspace\nEncrypted notes, text editing and workflows.",prior?.version ?: ""); model.refreshLibrary() }
            capture("chat-light")
            compose.onNodeWithText("ライブラリ").performClick(); capture("library-light")
            compose.onNodeWithText("接続・権限").performClick(); capture("settings-light")
            compose.runOnIdle { model.preferences.setTheme("dark"); model.refreshStatus() }
            compose.onNodeWithText("チャット").performClick(); capture("chat-dark")
        } finally {
            compose.runOnIdle { model.preferences.setTheme(theme); model.refreshStatus(); val doc = model.library.read(path); if(prior == null) model.library.remove(path,doc.version) else model.library.write(path,prior.text,doc.version); model.refreshLibrary() }
            compose.activityRule.scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        // Test-only synthetic captures. MediaStore preserves these on the
        // disposable emulator even when Gradle uninstalls the test app.
        if(Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME,"$name.png"); put(MediaStore.MediaColumns.MIME_TYPE,"image/png"); put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/komichi-ui-preview") }
            val uri = compose.activity.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values) ?: error("Cannot create synthetic capture")
            compose.activity.contentResolver.openOutputStream(uri)!!.use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)) }
        }
    }
}
