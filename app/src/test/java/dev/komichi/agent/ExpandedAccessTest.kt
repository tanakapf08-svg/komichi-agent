package dev.komichi.agent

import dev.komichi.agent.device.SharedStorage
import dev.komichi.agent.security.AppAccessPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ExpandedAccessTest {
    @Test fun fullModeExpandsTargetsWhileDisablingItRestoresTheAllowlist() {
        val selected = setOf("com.example.chosen")
        assertFalse(AppAccessPolicy.allows("com.example.new","dev.komichi.agent",false,selected))
        assertTrue(AppAccessPolicy.allows("com.example.new","dev.komichi.agent",true,selected))
        assertFalse(AppAccessPolicy.allows("dev.komichi.agent","dev.komichi.agent",true,selected))
        assertTrue(AppAccessPolicy.allows("com.example.chosen","dev.komichi.agent",false,selected))
        assertFalse(AppAccessPolicy.allows("com.example.new","dev.komichi.agent",false,selected))
    }
    @Test fun storageRejectsTraversalSiblingPrefixesAndPrivateAppPaths() {
        val root = Files.createTempDirectory("komichi-storage-test").toFile()
        try {
            assertEquals(File(root,"Download").canonicalFile,SharedStorage.resolve(root,"Download"))
            listOf("../outside",File(root.parentFile,root.name + "-sibling").path,"Android/data","Android/data/com.example","Android/obb/test").forEach {
                assertTrue("Must reject $it",runCatching { SharedStorage.resolve(root,it) }.isFailure)
            }
        } finally { root.delete() }
    }
}
