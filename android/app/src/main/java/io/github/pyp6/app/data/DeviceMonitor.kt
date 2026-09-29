package io.github.pyp6.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import io.github.pyp6.core.device.P6Drive
import io.github.pyp6.core.io.VNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A USB drive Android currently has mounted. */
data class UsbVolume(val description: String, val uuid: String?)

data class DeviceState(
    /** The folder the user granted, if any. */
    val treeUri: String? = null,
    /** The granted folder can be read right now (the drive is plugged in and mounted). */
    val reachable: Boolean = false,
    val rootName: String? = null,
    /** Which of IMPORT, EXPORT, BACKUP, RESTORE it holds. */
    val folders: Set<String> = emptySet(),
    val usbVolumes: List<UsbVolume> = emptyList(),
) {
    val hasImport get() = P6Drive.IMPORT in folders
    val hasExport get() = P6Drive.EXPORT in folders
    val hasBackup get() = P6Drive.BACKUP in folders
    val hasRestore get() = P6Drive.RESTORE in folders
}

/**
 * Keeps track of the P-6 drive. The P-6 in storage mode is a USB mass
 * storage device; a Pixel mounts it as a removable volume, and the app gets
 * at it through a folder permission the user grants once (Storage Access
 * Framework). The grant is remembered, and because the volume keeps its ID
 * the next time the P-6 is plugged in, it is found again by itself.
 *
 * Checked every couple of seconds while the app is in the foreground, and
 * whenever Android reports a volume change.
 */
class DeviceMonitor(private val context: Context, private val settings: AppSettings, private val scope: CoroutineScope) {
    private val storage = context.getSystemService(StorageManager::class.java)
    private val _state = MutableStateFlow(DeviceState(treeUri = settings.value.p6TreeUri))
    val state: StateFlow<DeviceState> = _state.asStateFlow()
    private var poller: Job? = null

    private val callback = object : StorageManager.StorageVolumeCallback() {
        override fun onStateChanged(volume: StorageVolume) {
            scope.launch(Dispatchers.IO) { refresh() }
        }
    }

    fun setForeground(on: Boolean) {
        if (on) {
            if (poller?.isActive == true) return
            runCatching { storage.registerStorageVolumeCallback(context.mainExecutor, callback) }
            poller = scope.launch(Dispatchers.IO) {
                while (isActive) {
                    refresh()
                    delay(2500)
                }
            }
        } else {
            poller?.cancel()
            poller = null
            runCatching { storage.unregisterStorageVolumeCallback(callback) }
        }
    }

    fun usbVolumes(): List<StorageVolume> = storage.storageVolumes.filter {
        it.isRemovable && it.state == Environment.MEDIA_MOUNTED
    }

    /** The system folder picker, opened at the root of the first USB drive when there is one. */
    fun grantIntent(): Intent? = usbVolumes().firstOrNull()?.createOpenDocumentTreeIntent()

    /** The granted folder's root, or null when it is not reachable now. */
    fun root(): VNode? {
        val uri = settings.value.p6TreeUri ?: return null
        return SafNode.fromTree(context, Uri.parse(uri))
    }

    fun grant(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
        val old = settings.value.p6TreeUri
        if (old != null && old != uri.toString() && old != settings.value.presetsTreeUri) {
            runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(old), flags) }
        }
        settings.update { it.copy(p6TreeUri = uri.toString()) }
        scope.launch(Dispatchers.IO) { refresh() }
    }

    fun forget() {
        val old = settings.value.p6TreeUri ?: return
        if (old != settings.value.presetsTreeUri) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
        settings.update { it.copy(p6TreeUri = null) }
        _state.value = DeviceState(usbVolumes = _state.value.usbVolumes)
    }

    fun refresh() {
        val volumes = runCatching {
            usbVolumes().map { UsbVolume(it.getDescription(context) ?: "USB drive", it.uuid) }
        }.getOrDefault(emptyList())
        val uri = settings.value.p6TreeUri
        if (uri == null) {
            _state.value = DeviceState(usbVolumes = volumes)
            return
        }
        val root = SafNode.fromTree(context, Uri.parse(uri))
        val folders = root?.let { r -> runCatching { P6Drive.describe(r) }.getOrNull() }
        _state.value = DeviceState(
            treeUri = uri,
            reachable = root != null && folders != null,
            rootName = root?.name,
            folders = folders ?: emptySet(),
            usbVolumes = volumes,
        )
    }
}
