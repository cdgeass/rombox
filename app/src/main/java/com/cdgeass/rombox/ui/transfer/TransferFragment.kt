package com.cdgeass.rombox.ui.transfer

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.cdgeass.rombox.R
import com.cdgeass.rombox.core.util.Fmt
import com.cdgeass.rombox.databinding.FragmentTransferBinding
import com.cdgeass.rombox.transfer.ServerState
import kotlinx.coroutines.launch

class TransferFragment : Fragment(R.layout.fragment_transfer) {

    private var _binding: FragmentTransferBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TransferViewModel by viewModels()

    private var ignoreSwitch = false

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentTransferBinding.bind(view)

        binding.editPort.setText(viewModel.state.value.port.toString())

        binding.switchServer.setOnCheckedChangeListener { _, checked ->
            if (ignoreSwitch) return@setOnCheckedChangeListener
            if (checked) {
                val port = binding.editPort.text?.toString()?.toIntOrNull()
                if (port == null || port !in 1024..65535) {
                    toast(getString(R.string.transfer_port_invalid))
                    setSwitch(false)
                    return@setOnCheckedChangeListener
                }
                requestNotificationIfNeeded()
                if (!viewModel.start(port)) {
                    val error = viewModel.state.value.error ?: "未知错误"
                    toast(getString(R.string.transfer_start_failed, error))
                    setSwitch(false)
                }
            } else {
                viewModel.stop()
            }
        }

        binding.btnCopy.setOnClickListener { copyUrl() }

        binding.editPort.doAfterTextChanged { text ->
            val port = text?.toString()?.toIntOrNull() ?: return@doAfterTextChanged
            if (port in 1024..65535) viewModel.savePort(port)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun render(state: ServerState) {
        setSwitch(state.running)

        if (state.running) {
            binding.urlText.text = state.urls.joinToString("\n")
            binding.urlText.setTextColor(
                requireContext().getColor(com.cdgeass.rombox.R.color.md_primary)
            )
        } else {
            binding.urlText.text = getString(R.string.transfer_off)
            binding.urlText.setTextColor(
                requireContext().getColor(com.cdgeass.rombox.R.color.md_onSurfaceVariant)
            )
        }

        binding.statsText.text = getString(
            R.string.transfer_stats,
            state.receivedFiles,
            Fmt.bytes(state.receivedBytes),
        )

        val log = if (state.logs.isEmpty()) {
            "等待请求…"
        } else {
            state.logs.joinToString("\n") { "${Fmt.clock(it.time)}  ${it.text}" }
        }
        binding.logText.text = log
        binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setSwitch(checked: Boolean) {
        ignoreSwitch = true
        binding.switchServer.isChecked = checked
        ignoreSwitch = false
    }

    private fun copyUrl() {
        val url = viewModel.state.value.urls.firstOrNull() ?: return
        val manager = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("RomBox", url))
        toast(getString(R.string.transfer_copied))
    }

    private fun requestNotificationIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }
}
