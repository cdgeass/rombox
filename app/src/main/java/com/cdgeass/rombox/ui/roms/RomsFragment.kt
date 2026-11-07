package com.cdgeass.rombox.ui.roms

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.cdgeass.rombox.R
import com.cdgeass.rombox.core.rom.RomFile
import com.cdgeass.rombox.core.util.Fmt
import com.cdgeass.rombox.core.util.StorageAccess
import com.cdgeass.rombox.databinding.FragmentRomsBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class RomsFragment : Fragment(R.layout.fragment_roms) {

    private var _binding: FragmentRomsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: RomsViewModel by viewModels()

    private lateinit var adapter: RomAdapter

    private val legacyPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.refresh() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentRomsBinding.bind(view)

        adapter = RomAdapter(onClick = { item -> showInfo(item) }, onAction = { item -> showActions(item) })
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter

        binding.btnScan.setOnClickListener { viewModel.refresh() }
        binding.btnMenu.setOnClickListener { showMoreMenu() }
        binding.btnPermission.setOnClickListener { requestStorageAccess() }
        binding.swipe.setOnRefreshListener { viewModel.refresh() }
        binding.editSearch.doAfterTextChanged { viewModel.setQuery(it?.toString().orEmpty()) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collectLatest { render() }
                }
                launch {
                    viewModel.messages.collect { Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResumeCheck()
    }

    override fun onDestroyView() {
        binding.list.adapter = null
        _binding = null
        super.onDestroyView()
    }

    // ------------------------------------------------------------ 渲染

    private fun render() {
        val state = viewModel.state.value
        val items = viewModel.visibleItems()

        binding.titleText.text = getString(R.string.rom_library_count, state.items.size)
        binding.dirPath.text = state.dirPath
        binding.permissionCard.visibility = if (state.hasStorageAccess) View.GONE else View.VISIBLE

        binding.progressRow.visibility = if (state.scanning) View.VISIBLE else View.GONE
        if (state.scanning) {
            binding.progress.isIndeterminate = state.indeterminate
            if (!state.indeterminate && state.total > 0) {
                binding.progress.isIndeterminate = false
                binding.progress.setProgressCompat(state.scanned * 100 / state.total, true)
                binding.progressText.text = "${state.scanned}/${state.total}"
            } else {
                binding.progressText.text = ""
            }
        }
        binding.swipe.isRefreshing = false

        adapter.submitList(items)

        val empty = items.isEmpty() && !state.scanning
        binding.emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        binding.emptyView.text = when {
            state.error != null -> state.error
            state.items.isEmpty() -> getString(R.string.rom_empty_hint)
            else -> getString(R.string.rom_no_match)
        }
    }

    // ------------------------------------------------------------ 交互

    private fun showMoreMenu() {
        val options = arrayOf(
            getString(R.string.rename_all),
            getString(R.string.find_dupes),
            "排序：名称",
            "排序：大小",
            "排序：最近修改",
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.more_actions)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> confirmRenameAll()
                    1 -> showDuplicates()
                    2 -> viewModel.setSort(SortMode.NAME)
                    3 -> viewModel.setSort(SortMode.SIZE)
                    4 -> viewModel.setSort(SortMode.RECENT)
                }
                render()
            }
            .show()
    }

    private fun confirmRenameAll() {
        val count = viewModel.state.value.items.size
        if (count == 0) {
            toast(getString(R.string.rom_empty_hint))
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.rename_all)
            .setMessage(getString(R.string.rename_all_message, count))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.rename_all_confirm) { _, _ -> viewModel.renameAll() }
            .show()
    }

    private fun showDuplicates() {
        val groups = viewModel.duplicateGroups()
        if (groups.isEmpty()) {
            toast(getString(R.string.no_dupes))
            return
        }
        val total = groups.sumOf { it.size - 1 }
        val text = buildString {
            append(getString(R.string.dupes_found, groups.size, total))
            append("\n\n")
            groups.take(12).forEachIndexed { index, group ->
                append("${index + 1}. ")
                append(group.first().title)
                append(" ×${group.size}")
                append("\n")
                group.drop(1).forEach { append("   · ${it.name} (${Fmt.bytes(it.size)})\n") }
            }
            if (groups.size > 12) append("…")
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.find_dupes)
            .setMessage(text.trim())
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete_dupes) { _, _ -> viewModel.deleteDuplicates() }
            .show()
    }

    private fun showActions(item: RomFile) {
        val options = arrayOf(
            getString(R.string.action_info),
            getString(R.string.action_rename),
            getString(R.string.action_delete),
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(item.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showInfo(item)
                    1 -> viewModel.renameToCanonical(item)
                    2 -> confirmDelete(item)
                    else -> Unit
                }
            }
            .show()
    }

    private fun showInfo(item: RomFile) {
        val header = item.header
        val text = buildString {
            append(getString(R.string.info_title, item.title)).append("\n\n")
            append(getString(R.string.info_file, item.name)).append("\n")
            append(getString(R.string.info_size, Fmt.bytes(item.size))).append("\n")
            item.crc32?.let { append(getString(R.string.info_crc, Fmt.hex(it))).append("\n") }
            item.gameCode?.let { append(getString(R.string.info_code, it)).append("\n") }
            header?.makerCode?.let { append(getString(R.string.info_maker, it)).append("\n") }
            item.region?.let { append(getString(R.string.info_region, it)).append("\n") }
            header?.version?.let { append(getString(R.string.info_version, it)).append("\n") }
            append(getString(R.string.info_modified, Fmt.date(item.lastModified))).append("\n")
            append("\n").append(item.path)
            if (header != null && !header.valid) {
                append("\n\n").append(getString(R.string.info_header_problem, header.problems.joinToString("；")))
            }
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.action_info)
            .setMessage(text)
            .setNegativeButton(android.R.string.ok, null)
            .setPositiveButton(R.string.action_rename) { _, _ -> viewModel.renameToCanonical(item) }
            .setNeutralButton(R.string.action_delete) { _, _ -> confirmDelete(item) }
            .show()
    }

    private fun confirmDelete(item: RomFile) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.action_delete)
            .setMessage(getString(R.string.delete_message, item.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ -> viewModel.delete(item) }
            .show()
    }

    // ------------------------------------------------------------ 权限

    private fun requestStorageAccess() {
        val context = requireContext()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = StorageAccess.requestIntent(context)
            if (intent != null) {
                runCatching { startActivity(intent) }
                return
            }
        }
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            legacyPermission.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                )
            )
        } else {
            toast(getString(R.string.permission_already))
        }
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }
}
