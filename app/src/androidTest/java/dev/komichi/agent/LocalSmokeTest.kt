package dev.komichi.agent

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performImeAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.komichi.agent.data.Vault
import dev.komichi.agent.data.obj
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.MaterialTheme
import dev.komichi.agent.agent.ModelCatalogState
import dev.komichi.agent.agent.ModelChoice

@RunWith(AndroidJUnit4::class)
class LocalSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun startsWithoutCredentialsOrPrivilegedServicesAndSettingsSurviveRecreation() {
        compose.onNodeWithText("チャット").assertIsDisplayed()
        compose.onNodeWithText("モデルを選択").assertIsDisplayed()
        compose.onNodeWithText("接続・権限").performClick()
        compose.onNodeWithText("Continue with ChatGPT").performScrollTo().assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Continue with ChatGPT").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("操作ログ").performClick()
        compose.onNodeWithText("端末内の操作ログ").assertIsDisplayed()
    }
    @Test fun permissionPanelReportsUnverifiedRootAndFullModeNeedsExplicitConfirmation() {
        val preferences = (compose.activity.application as KomichiApp).preferences
        try {
            compose.onNodeWithText("接続・権限").performClick()
            compose.onNodeWithTag("fullAccessSwitch").performScrollTo().assertIsOff().performClick()
            assertFalse(preferences.fullAccessEnabled())
            compose.onNodeWithText("モードを有効にする").performClick()
            compose.onNodeWithTag("fullAccessSwitch").assertIsOn()
            assertTrue(preferences.fullAccessEnabled()); assertFalse(preferences.rootEnabled())
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("fullAccessSwitch").performScrollTo().assertIsOn().performClick()
            assertFalse(preferences.fullAccessEnabled())
            compose.onNodeWithText("権限一覧を表示").performScrollTo().performClick()
            compose.onNodeWithText("画面画像の取得：毎回確認").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Rootの実利用：未確認").performScrollTo().assertIsDisplayed()
        } finally { preferences.setFullAccess(false) }
    }
    @Test fun chatModelPickerChangesTheSelectedModelWithoutANetworkCall() {
        val selected = mutableStateOf("one")
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            MaterialTheme { ModelPicker(ModelCatalogState("test",listOf(ModelChoice("one","First model"),ModelChoice("two","Second model")),selected.value),
                enabled = true,connected = true,onChoose = { selected.value = it },onReload = {}) }
        } }
        compose.onNodeWithText("First model").performClick()
        compose.onNodeWithText("Second model").performClick()
        compose.onNodeWithText("Second model").assertIsDisplayed()
        compose.runOnIdle { assertEquals("two",selected.value) }
    }
    @Test fun keystoreVaultEncryptsAndDetectsTampering() {
        val context = compose.activity.applicationContext
        val vault = Vault(context); val name = "instrumentation_probe"
        val secret = "test-only-secret-7c159a"
        val file = File(context.noBackupFilesDir,"$name.vault")
        try {
            vault.write(name,obj("secret" to secret))
            assertEquals(secret,vault.read(name)!!.getString("secret"))
            val bytes = file.readBytes()
            assertFalse(bytes.toString(Charsets.ISO_8859_1).contains(secret))
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes)
            assertTrue(runCatching { vault.read(name) }.isFailure)
        } finally { file.delete() }
    }
    @Test fun rootFileFormRequiresAnAppAndEmitsOnlyStructuredArguments() {
        var submitted: Pair<String,org.json.JSONObject>? = null
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            MaterialTheme { RootFilesDialog(onDismiss = {},onRun = { name,args -> submitted = name to args }) }
        } }
        compose.onNodeWithText("コピー内容を確認").assertIsNotEnabled()
        compose.onNodeWithText("対象パッケージ名").performTextInput("com.example.fixture")
        compose.onNodeWithText("対象パッケージ名").performImeAction()
        compose.onNodeWithText("一覧を確認").performScrollTo().performClick()
        compose.waitUntil(3000) { submitted != null }
        compose.runOnIdle {
            assertEquals("list_app_data",submitted!!.first)
            assertEquals("com.example.fixture",submitted!!.second.getString("package"))
            assertEquals(".",submitted!!.second.getString("path"))
        }
        compose.onNodeWithText("コピー内容を確認").performClick()
        compose.runOnIdle {
            assertEquals("copy_app_data",submitted!!.first)
            assertEquals("directory",submitted!!.second.getString("format"))
            assertEquals("Download/app-backup",submitted!!.second.getString("destination"))
        }
    }
}
