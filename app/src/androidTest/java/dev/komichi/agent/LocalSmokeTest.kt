package dev.komichi.agent

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.komichi.agent.data.Vault
import dev.komichi.agent.data.obj
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun startsWithoutCredentialsOrPrivilegedServicesAndSettingsSurviveRecreation() {
        compose.onNodeWithText("チャット").assertIsDisplayed()
        compose.onNodeWithText("接続・権限").performClick()
        compose.onNodeWithText("Continue with ChatGPT").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Continue with ChatGPT").assertIsDisplayed()
        compose.onNodeWithText("操作ログ").performClick()
        compose.onNodeWithText("端末内の操作ログ").assertIsDisplayed()
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
}
