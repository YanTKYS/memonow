package com.yantkys.memonow

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.yantkys.memonow.update.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val installed = InstalledApp("0.1.3",4,setOf("fixture"))
    private val release = ReleaseUpdate("0.1.4","test",8,"a".repeat(64))
    private lateinit var store: ViewModelStore
    @Before fun setUp() { resetUpdateProviderCache(); store=ViewModelStore(); Dispatchers.setMain(UnconfinedTestDispatcher()); File(context.cacheDir,"updates").deleteRecursively() }
    @After fun close() { store.clear(); Dispatchers.resetMain(); File(context.cacheDir,"updates").deleteRecursively() }
    private fun model(source: UpdateSource, key: String = "main", available: (File)->Long = { it.usableSpace }, verifier: (File,ReleaseUpdate,InstalledApp)->Unit = {_,_,_->}): UpdateViewModel {
        val factory=object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T: androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                UpdateViewModel(context,source,verifier, { installed }, available) as T
        }
        return ViewModelProvider(store,factory).get(key,UpdateViewModel::class.java)
    }
    private fun source(version: String = "0.1.4") = object : UpdateSource {
        override suspend fun latest()=release.copy(versionName=version)
        override suspend fun download(release: ReleaseUpdate,file: File,progress:(Long)->Unit) { file.writeBytes(ByteArray(8));progress(8) }
    }
    private suspend fun idle(model: UpdateViewModel) { model.state.first { !it.busy }; yield() }
    @Test fun defaultAndroidFactoryReadsActualPackageInfoWithoutStartingCheck() {
        val model=ViewModelProvider(store,ViewModelProvider.AndroidViewModelFactory(context))[UpdateViewModel::class.java]
        @Suppress("DEPRECATION") val info=context.packageManager.getPackageInfo(context.packageName,0)
        assertEquals(info.versionName,model.installed.name)
        assertEquals(info.code(),model.installed.code)
        assertFalse(model.state.value.busy)
        assertNull(model.state.value.release)
    }
    @Test fun checkIsManualAndDistinguishesCurrentOlderAndNewer() = runTest {
        var checks=0
        val source=object: UpdateSource {
            var name="0.1.3"
            override suspend fun latest():ReleaseUpdate { checks++; return release.copy(versionName=name) }
            override suspend fun download(release:ReleaseUpdate,file:File,progress:(Long)->Unit) { fail("not requested") }
        }
        val model=model(source)
        assertEquals(0,checks)
        model.check(); idle(model); assertFalse(model.state.value.newer);assertTrue(model.state.value.message.contains("利用中"))
        source.name="0.1.2";model.check();idle(model);assertTrue(model.state.value.message.contains("古い"))
        source.name="0.1.4";model.check();idle(model);assertTrue(model.state.value.newer)
    }
    @Test fun installerRequiresPermissionAndReverificationAndOnlyReadGrant() = runTest {
        var verified=0
        val model=model(source()) { file,_,_-> assertTrue(file.isFile);verified++ }
        model.check();idle(model);model.download();idle(model)
        assertTrue(model.state.value.ready);assertEquals(1,verified)
        shadowOf(context.packageManager).setCanRequestPackageInstalls(false)
        var launched:Intent?=null
        model.install { launched=it }; assertNull(launched)
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,model.installPermissionIntent().action)
        assertEquals("package:${context.packageName}",model.installPermissionIntent().data.toString())
        shadowOf(context.packageManager).setCanRequestPackageInstalls(true)
        model.install { launched=it };idle(model)
        assertNotNull(model.state.value.toString(),launched);assertEquals(2,verified)
        assertEquals(Intent.ACTION_VIEW,launched!!.action)
        assertEquals("content",launched!!.data!!.scheme)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,launched!!.flags)
        assertEquals(launched!!.data,launched!!.clipData!!.getItemAt(0).uri)
        model.close() // A returned/cancelled OS installer may still be reading this file.
        assertTrue(File(context.cacheDir,"updates/ready").walkTopDown().any { it.isFile })
    }
    @Test fun verificationFailureNeverMakesInstallableFile() = runTest {
        val model=model(source()) { _,_,_->throw UpdateFailure("署名不一致") }
        model.check();idle(model);model.download();idle(model)
        assertFalse(model.state.value.ready)
        assertFalse(File(context.cacheDir,"updates").walkTopDown().any { it.isFile && it.extension == "part" })
        assertFalse(File(context.cacheDir,"updates/ready").walkTopDown().any { it.isFile })
        model.install { fail("must not launch") }
    }
    @Test fun interruptedDownloadDeletesPartialFileAndCannotLaunchInstaller() = runTest {
        val entered=CompletableDeferred<Unit>()
        val model=model(object:UpdateSource {
            override suspend fun latest()=release
            override suspend fun download(release:ReleaseUpdate,file:File,progress:(Long)->Unit) {
                file.writeBytes(ByteArray(4));entered.complete(Unit);awaitCancellation()
            }
        })
        model.check();idle(model);model.download();entered.await();model.cancel();idle(model)
        assertFalse(model.state.value.ready)
        assertFalse(File(context.cacheDir,"updates").walkTopDown().any { it.isFile && it.extension == "part" })
        model.install { fail("must not launch") }
    }
    @Test fun permissionSettingsFailureAndOfflineCheckAreRecoverable() = runTest {
        val model=model(object:UpdateSource {
            override suspend fun latest():ReleaseUpdate = throw java.net.UnknownHostException("offline")
            override suspend fun download(release:ReleaseUpdate,file:File,progress:(Long)->Unit) { fail("must not download") }
        })
        model.check();idle(model)
        assertFalse(model.state.value.ready);assertNull(model.state.value.release)
        assertTrue(model.state.value.message.contains("ネット接続"))
        model.openPermission { throw android.content.ActivityNotFoundException() }
        assertTrue(model.state.value.message.contains("開けません"))
    }
    @Test fun insufficientSpacePreventsDownloadAndInstaller() = runTest {
        val model=model(source(),available={0}) {_,_,_->fail("must not verify")}
        model.check();idle(model);model.download();idle(model)
        assertTrue(model.state.value.message.contains("空き容量"))
        assertFalse(model.state.value.ready)
        model.install { fail("must not install") }
    }
    @Test fun independentScreensDoNotOverwriteOrCleanEachOthersFiles() = runTest {
        val entered=CompletableDeferred<Unit>()
        val finish=CompletableDeferred<Unit>()
        val first=model(object:UpdateSource {
            override suspend fun latest()=release
            override suspend fun download(release:ReleaseUpdate,file:File,progress:(Long)->Unit) {
                file.writeBytes(ByteArray(8));entered.complete(Unit);finish.await()
            }
        },key="first") {file,_,_->assertTrue(file.exists())}
        first.check();idle(first);first.download();entered.await()
        val second=model(source(),key="second")
        assertEquals(1,File(context.cacheDir,"updates").walkTopDown().count { it.isFile && it.extension=="part" })
        second.check();idle(second);second.download();idle(second);second.close()
        assertEquals(1,File(context.cacheDir,"updates").walkTopDown().count { it.isFile && it.extension=="part" })
        finish.complete(Unit);idle(first)
        assertTrue(first.state.value.ready)
        assertEquals(1,File(context.cacheDir,"updates/ready").walkTopDown().count { it.isFile })
        first.close()
    }
    @Test fun failedCheckAndClosingBeforeHandoffClearReadyState() = runTest {
        val model=model(source())
        model.check();idle(model);model.download();idle(model)
        assertTrue(model.state.value.ready);model.close();assertFalse(model.state.value.ready)
        assertFalse(File(context.cacheDir,"updates/ready").walkTopDown().any { it.isFile })
    }
}
