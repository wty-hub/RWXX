package io.github.rwx.app

import io.github.rwx.PlatformBridge
import io.github.rwx.PlatformFileSelection
import io.github.rwx.i18n.I18n
import io.github.rwx.logger
import io.github.rwx.mod.ModRepository
import io.github.rwx.session.GameSession
import io.github.rwx.ui.component.Icon
import io.github.rwx.ui.component.invalidateModThumbnailTextureCache
import io.github.rwx.ui.host.DialogSceneHost
import io.github.rwx.ui.host.LoadingDialogSceneHost
import io.github.rwx.ui.host.ModsSceneHost
import io.github.rwx.ui.model.Dialog
import io.github.rwx.ui.model.DialogButton
import io.github.rwx.ui.model.DialogTextInput
import io.github.rwx.ui.model.ModEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.koin.mp.KoinPlatform.getKoin
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.milliseconds

internal class ModsController(
    private val modRepository: ModRepository,
    private val gameSession: GameSession,
    private val sceneHost: ModsSceneHost,
    private val loadingDialogSceneHost: LoadingDialogSceneHost,
    private val dialogSceneHost: DialogSceneHost,
    private val onModsReloaded: () -> Unit = {},
) {
    private var reloadLoading = false
    private var reloadDialogVisible = false
    private var reloadJob: Job? = null
    private val reloadResult = AtomicReference<ModsReloadResult?>(null)
    private var publishedMods = emptyList<ModEntry>()

    fun refresh(statusText: String = "") {
        updateModsOnOwner { statusText }
    }

    fun applyChangesAndRefresh() {
        updateModsOnOwner { modRepository.applyChanges(); "" }
    }

    fun reloadAvailableAndRefresh() {
        updateModsOnOwner(invalidateThumbnails = true) { modRepository.reloadAvailableMods(); "" }
    }

    fun disableAllAndRefresh() {
        updateModsOnOwner { modRepository.disableAll(); "" }
    }

    fun toggleEnabledAndRefresh(modId: String) {
        updateModsOnOwner { modRepository.toggleEnabled(modId); "" }
    }

    fun deleteAndRefresh(modId: String) {
        updateModsOnOwner { if (modRepository.delete(modId)) "" else I18n.mods.deleteFailed() }
    }

    private fun updateModsOnOwner(invalidateThumbnails: Boolean = false,
        afterComplete: () -> Unit = {}, change: () -> String) {
        gameSession.requestSessionTask({
            val status = change()
            ModsDisplaySnapshot(modRepository.listMods(), status)
        }) { result ->
            try {
                result.onSuccess { snapshot ->
                    if (invalidateThumbnails) invalidateModThumbnailTextureCache()
                    publishedMods = snapshot.mods
                    sceneHost.updateMods(snapshot.mods, snapshot.status)
                }.onFailure { error ->
                    logger.warn(error) { "Unable to update mods" }
                    sceneHost.updateMods(publishedMods, I18n.mods.updateFailed(error.message ?: error.javaClass.simpleName))
                }
            } finally { afterComplete() }
        }
    }

    fun showImportDialog() {
        var selectedFile: PlatformFileSelection? = null
        dialogSceneHost.show(
            Dialog(
                title = I18n.mods.importTitle(),
                message = I18n.mods.importPrompt(),
                textInput = DialogTextInput(
                    hint = I18n.mods.importPathHint(),
                    trailingIcon = Icon.Import,
                    trailingIconTooltip = I18n.mods.chooseFile(),
                    onTrailingIconPress = { setInputValue ->
                        val host=getKoin().get<PlatformBridge>().filePickerHost?:return@DialogTextInput
                        host.openFilePicker(
                            title = I18n.mods.chooseFileOrDirectory(),
                            allowedExtensions = setOf(
                                "rwmod", "zip", "jar", "ini", "rwxkey", "rwxpub", "rwxlicense"
                            ),
                            allowDirectories = true
                        ) { selection ->
                            if (selection != null) {
                                selectedFile?.release()
                                selectedFile = selection
                                setInputValue(selection.displayPath)
                            }
                        }
                    },
                ),
                buttons = listOf(
                    DialogButton(
                        I18n.mods.importFile(),
                        onInputPress = { inputPath ->
                            val path = selectedFile
                                ?.takeIf { inputPath == it.displayPath }
                                ?.path
                                ?: inputPath
                            val importSelection = selectedFile
                            selectedFile = null
                            updateModsOnOwner(afterComplete = { importSelection?.release() }) {
                                modRepository.importMod(path).message
                            }
                        },
                    ),
                    DialogButton(
                        I18n.common.cancel(),
                        onPress = {
                            selectedFile?.release()
                            selectedFile = null
                        },
                    ),
                ),
            ),
        )
    }

    fun reloadWithDialog() {
        if (reloadLoading) {
            return
        }
        reloadLoading = true
        reloadResult.set(null)

        // Queue the complete operation at the click boundary. An IO launch must not reorder
        // this operation behind a later toggle/import click in the independent-engine backend.
        val ownerReload = if (gameSession.usesIndependentEngineLoop) gameSession.submitSessionTask {
            runBlocking {
                modRepository.applyChanges()
                if (!gameSession.requestReloadMods()) modRepository.reloadAppliedMods()
            }
            modRepository.listMods()
        } else null

        val job = launchOnIO("mods-reload") {
            val result = try {
                val mods = if (ownerReload != null) ownerReload.awaitModOperation()
                else {
                    // Legacy backends keep their existing inline/application reload behavior.
                    modRepository.applyChanges()
                    val handledByBackend = gameSession.requestReloadMods()
                    if (handledByBackend) waitForLoadingText("Mods reloaded", timeoutMillis = 60_000L)
                    else modRepository.reloadAppliedMods()
                    modRepository.listMods()
                }
                ModsReloadResult.Success(mods)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                ModsReloadResult.Failed(error)
            }
            reloadResult.set(result)
        }
        reloadJob = job
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                reloadResult.compareAndSet(null, ModsReloadResult.Cancelled)
            }
        }
        reloadDialogVisible = true
        loadingDialogSceneHost.showProgress(
            title = I18n.mods.reloading(),
            message = I18n.mods.loadingUnitData(),
            progress = 0.05f,
        ) {
            cancelReload(job)
        }
    }

    fun driveReload(): Boolean {
        if (reloadLoading && reloadDialogVisible) {
            val status = gameSession.loadingStatus()
            loadingDialogSceneHost.updateProgress(
                message = status.text.ifBlank { I18n.mods.loadingUnitData() },
                progress = status.progress ?: 0.05f,
            )
        }
        reloadResult.getAndSet(null)?.let { result ->
            if (reloadLoading) {
                finishReload(result)
            }
        }
        return reloadLoading
    }

    private fun cancelReload(job: Job) {
        if (!reloadLoading || reloadJob !== job) {
            return
        }
        reloadDialogVisible = false
        loadingDialogSceneHost.hide()
        job.cancel()
    }

    private suspend fun waitForLoadingText(expectedText: String, timeoutMillis: Long) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val status = gameSession.loadingStatus()
            if (status.text == expectedText && (status.progress ?: 0.0f) >= 1.0f) {
                return
            }
            delay(50L.milliseconds)
        }
        throw IllegalStateException("Timed out waiting for $expectedText")
    }

    private fun finishReload(result: ModsReloadResult) {
        reloadLoading = false
        reloadJob = null
        if (reloadDialogVisible) {
            reloadDialogVisible = false
            loadingDialogSceneHost.hide()
        }
        when (result) {
            is ModsReloadResult.Success -> {
                onModsReloaded()
                invalidateModThumbnailTextureCache()
                publishedMods = result.mods
                sceneHost.updateMods(result.mods, I18n.mods.reloaded())
            }

            ModsReloadResult.Cancelled -> Unit
            is ModsReloadResult.Failed -> {
                val error = result.error
                logger.warn(error) { "Unable to reload mods" }
                refresh(I18n.mods.reloadFailed(error.message ?: error.javaClass.simpleName))
            }
        }
    }
}

private sealed interface ModsReloadResult {
    data class Success(val mods: List<ModEntry>) : ModsReloadResult
    data object Cancelled : ModsReloadResult
    data class Failed(val error: Throwable) : ModsReloadResult
}

private data class ModsDisplaySnapshot(val mods: List<ModEntry>, val status: String)

private suspend fun <T> CompletableFuture<T>.awaitModOperation(): T = suspendCancellableCoroutine { continuation ->
    whenComplete { result, error ->
        if (continuation.isActive) {
            if (error == null) continuation.resume(result)
            else continuation.resumeWithException(error.cause ?: error)
        }
    }
    // Cancelling the dialog does not interrupt a running original engine reload mid-update.
}
