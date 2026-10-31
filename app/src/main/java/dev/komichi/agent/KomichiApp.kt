package dev.komichi.agent

import android.app.Application
import dev.komichi.agent.data.Vault
import dev.komichi.agent.device.DevicePreferences
import dev.komichi.agent.data.LibraryStore

class KomichiApp: Application() {
    val vault by lazy { Vault(this) }
    val preferences by lazy { DevicePreferences(vault) }
    val library by lazy { LibraryStore(vault) }
}
