package dev.komichi.agent

import dev.komichi.agent.agent.ToolCall
import dev.komichi.agent.agent.ToolCatalog
import dev.komichi.agent.data.obj
import dev.komichi.agent.device.AppDataCopyPlan
import dev.komichi.agent.device.AppDataDestination
import dev.komichi.agent.device.AppDataPaths
import dev.komichi.agent.device.RootFileScripts
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RootFilesTest {
    private val pkg = "dev.komichi.rootfixture"
    private val ce = "/data/user/0/$pkg"
    private val de = "/data/user_de/0/$pkg"
    private val storage = "/storage/emulated/0"
    private fun rejected(action: () -> Unit) { assertTrue(runCatching(action).isFailure) }

    @Test fun privatePathsUseInstalledAppRootsIncludingDeviceProtectedStorage() {
        assertEquals(ce,AppDataPaths.source(pkg,listOf(ce,de),".").path)
        assertEquals("$ce/databases",AppDataPaths.source(pkg,listOf(ce,de),"/data/data/$pkg/databases").path)
        assertEquals("$de/files",AppDataPaths.source(pkg,listOf(ce,de),"$de/files").path)
        val profile = "/data/user/10/$pkg"
        assertEquals(profile,AppDataPaths.source(pkg,listOf(profile),".").path)
        rejected { AppDataPaths.source(pkg,listOf(profile),"/data/data/$pkg") }
    }
    @Test fun rejectsOtherAppsTraversalAndInvalidPackageRoots() {
        listOf("../other","/data/data/${pkg}2","/data/user/0/other.app","files/../../other","files\nsecret").forEach { path ->
            rejected { AppDataPaths.source(pkg,listOf(ce,de),path) }
        }
        rejected { AppDataPaths.source(pkg,listOf("/data/system"),".") }
        rejected { AppDataPaths.source("$pkg;id",listOf(ce),".") }
    }
    @Test fun destinationsAreNewSharedStoragePathsAndFormatsAreExplicit() {
        assertEquals("$storage/Download/backup",AppDataPaths.destination(storage,"/sdcard/Download/backup","directory").path)
        assertEquals("$storage/Download/backup.tar",AppDataPaths.destination(storage,"Download/backup.tar","tar").path)
        listOf(".","/data/local/tmp/copy","../escape","Android/data/other.app","Android/obb/x","/storage/emulated/01/sibling").forEach { path ->
            rejected { AppDataPaths.destination(storage,path,"directory") }
        }
        rejected { AppDataPaths.destination(storage,"Download/backup","tar") }
        rejected { AppDataPaths.destination(storage,"Download/backup","shell") }
    }
    @Test fun catalogRequiresStructuredRootArgumentsAndRejectsExtraCommands() {
        val args = obj("package" to pkg,"source_path" to ".","destination" to "Download/backup","format" to "directory")
        ToolCatalog.validate(ToolCall("test","android","copy_app_data",args))
        rejected { ToolCatalog.validate(ToolCall("test","android","copy_app_data",obj("package" to pkg,"source_path" to ".","destination" to "Download/backup","format" to "directory","command" to "id"))) }
        rejected { ToolCatalog.validate(ToolCall("test","android","copy_app_data",obj("package" to pkg,"source_path" to ".","destination" to "Download/backup","format" to "shell"))) }
    }
    @Test fun exportsProductionTemplatesForAndroidUidZeroIntegrationChecks() {
        // Only synthetic fixture paths. The host harness runs these exact production
        // templates on a disposable emulator, never on a user's real app data.
        val dir = File("build/root-script-fixtures").apply { mkdirs() }
        val source = AppDataPaths.source(pkg,listOf(ce,de),".")
        val lease = "/data/local/tmp/komichi-root-fixture/permit"
        val odd = "quote' \$(id) §__JOB__"
        val jobs = linkedMapOf("list" to 1,"directory" to 2,"tar" to 3,"symlink-source" to 4,"symlink-destination" to 5,"expired" to 6,"odd-name" to 7,"single-file" to 8,"single-tar" to 9)
        jobs.forEach { (name,index) ->
            val job = index.toString(16).padStart(32,'0')
            val src = when(name) {
                "symlink-source" -> AppDataPaths.source(pkg,listOf(ce),"escape")
                "single-file","single-tar" -> AppDataPaths.source(pkg,listOf(ce),"files/note.txt")
                else -> source
            }
            val format = if(name == "tar" || name == "single-tar") "tar" else "directory"
            val dest = if(name == "symlink-destination") AppDataDestination("/data/local/tmp/komichi-root-fixture/storage","link","blocked")
            else AppDataPaths.destination(storage,when(name) {
                "tar" -> "Download/komichi-root-fixture/backup.tar"
                "symlink-destination" -> "Download/komichi-root-fixture/link/blocked"
                "odd-name" -> "Download/komichi-root-fixture/$odd"
                "single-tar" -> "Download/komichi-root-fixture/single.tar"
                else -> "Download/komichi-root-fixture/$name"
            },format)
            val script = if (name == "list") RootFileScripts.list(src,job,lease)
                else RootFileScripts.copy(AppDataCopyPlan(src,dest,format,job),lease)
            assertTrue(script.contains("KOMICHI_OK $job"))
            assertFalse(script.contains("__SOURCE_BASE__"))
            File(dir,"$name.sh").writeText(script)
            File(dir,"$name.permit").writeText(job)
        }
        assertTrue(File(dir,"odd-name.sh").readText().contains("§__JOB__"))
    }
}
