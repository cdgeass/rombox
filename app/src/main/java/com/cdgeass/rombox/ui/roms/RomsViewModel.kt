package com.cdgeass.rombox.ui.roms

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cdgeass.rombox.container
import com.cdgeass.rombox.core.model.RomPlatform
import com.cdgeass.rombox.core.platform.Platforms
import com.cdgeass.rombox.core.rom.Naming
import com.cdgeass.rombox.core.rom.RomFile
import com.cdgeass.rombox.core.util.StorageAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SortMode(val label: String) {
    NAME("名称"),
    SIZE("大小"),
    RECENT("最近修改"),
}

data class RomsUiState(
    val platformName: String = "",
    val dirPath: String = "",
    val items: List<RomFile> = emptyList(),
    val scanning: Boolean = false,
    val scanned: Int = 0,
    val total: Int = 0,
    val indeterminate: Boolean = true,
    val query: String = "",
    val sort: SortMode = SortMode.NAME,
    val error: String? = null,
    val hasStorageAccess: Boolean = true,
)

class RomsViewModel(app: Application) : AndroidViewModel(app) {

    private val container = app.container
    private val settings = container.settings
    private val repository = container.repository
    private val platform: RomPlatform = Platforms.byId(settings.platformId) ?: Platforms.default

    private val _state = MutableStateFlow(
        RomsUiState(
            platformName = platform.displayName,
            dirPath = settings.romDirPath,
            hasStorageAccess = StorageAccess.hasAccess(app),
        )
    )
    val state: StateFlow<RomsUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var scanJob: Job? = null

    init {
        refresh()
        viewModelScope.launch {
            // 无线传输写入新文件后自动刷新
            container.dataRevision.drop(1).collect { refresh(silent = true) }
        }
    }

    fun refresh(silent: Boolean = false) {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            val dir = repository.romDir()
            _state.update {
                it.copy(
                    dirPath = dir.absolutePath,
                    scanning = true,
                    error = null,
                    indeterminate = !silent,
                    scanned = 0,
                    hasStorageAccess = StorageAccess.hasAccess(getApplication()),
                )
            }
            if (!dir.isDirectory) {
                _state.update {
                    it.copy(scanning = false, items = emptyList(), error = "目录不存在：${dir.absolutePath}")
                }
                return@launch
            }

            val context = coroutineContext
            val items = withContext(Dispatchers.IO) {
                repository.listRoms(
                    platform = platform,
                    computeCrc = true,
                    onProgress = { done, total ->
                        _state.update { s -> s.copy(scanned = done, total = total, indeterminate = false) }
                    },
                    cancelled = { !context.isActive },
                )
            }
            _state.update { it.copy(scanning = false, items = items, error = null, indeterminate = false) }
        }
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    /** 从系统设置页/授权弹窗回来时调用：权限状态变了就重新扫描 */
    fun onResumeCheck() {
        val access = StorageAccess.hasAccess(getApplication())
        if (access != _state.value.hasStorageAccess) {
            refresh()
        } else if (_state.value.items.isEmpty() && !_state.value.scanning) {
            refresh(silent = true)
        }
    }

    fun setSort(sort: SortMode) = _state.update { it.copy(sort = sort) }

    /** 当前过滤 + 排序后的列表 */
    fun visibleItems(): List<RomFile> {
        val current = _state.value
        val query = current.query.trim()
        val filtered = if (query.isEmpty()) {
            current.items
        } else {
            current.items.filter { item ->
                item.name.contains(query, true) ||
                    item.title.contains(query, true) ||
                    item.gameCode?.contains(query, true) == true ||
                    item.region?.contains(query, true) == true
            }
        }
        return when (current.sort) {
            SortMode.NAME -> filtered.sortedBy { it.title.lowercase() }
            SortMode.SIZE -> filtered.sortedByDescending { it.size }
            SortMode.RECENT -> filtered.sortedByDescending { it.lastModified }
        }
    }

    fun delete(item: RomFile) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { repository.delete(item.file) }
            _messages.emit(if (ok) "已删除 ${item.name}" else "删除失败：${item.name}")
            if (ok) refresh(silent = true)
        }
    }

    fun renameToCanonical(item: RomFile) {
        viewModelScope.launch {
            val message = withContext(Dispatchers.IO) {
                val desired = Naming.canonicalFileName(item.file, item.header)
                if (desired == item.name) {
                    "已经是规范名称"
                } else {
                    val target = repository.uniqueTarget(item.file.parentFile ?: repository.romDir(), desired, item.file)
                    if (runCatching { item.file.renameTo(target) }.getOrDefault(false)) {
                        "已重命名为 ${target.name}"
                    } else {
                        "重命名失败"
                    }
                }
            }
            _messages.emit(message)
            refresh(silent = true)
        }
    }

    fun renameAll() {
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) { repository.renameAllToCanonical(platform) }
            _messages.emit(if (count == 0) "都已经是规范名称" else "已整理 $count 个文件")
            refresh(silent = true)
        }
    }

    fun duplicateGroups(): List<List<RomFile>> = repository.duplicateGroups(_state.value.items)

    /** 每组保留第一份，其余删除 */
    fun deleteDuplicates() {
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) {
                var count = 0
                repository.duplicateGroups(_state.value.items).forEach { group ->
                    group.drop(1).forEach { if (repository.delete(it.file)) count++ }
                }
                count
            }
            _messages.emit(if (removed == 0) "没有可删除的重复文件" else "已删除 $removed 个重复文件")
            refresh(silent = true)
        }
    }
}
