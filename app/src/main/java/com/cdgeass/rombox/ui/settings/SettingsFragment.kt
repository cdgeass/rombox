package com.cdgeass.rombox.ui.settings

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
import com.cdgeass.rombox.R
import com.cdgeass.rombox.container
import com.cdgeass.rombox.core.settings.AppSettings
import com.cdgeass.rombox.core.util.StorageAccess
import com.cdgeass.rombox.databinding.FragmentSettingsBinding
import com.cdgeass.rombox.ui.common.DirPicker
import java.io.File

class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val settings get() = requireContext().container.settings

    private val legacyPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshPermissionText() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentSettingsBinding.bind(view)

        binding.btnPickDir.setOnClickListener {
            DirPicker.show(requireActivity(), File(settings.romDirPath)) { dir ->
                settings.romDirPath = dir.absolutePath
                requireContext().container.notifyDataChanged()
                render()
            }
        }

        binding.btnResetDir.setOnClickListener {
            settings.romDirPath = AppSettings.defaultRomDir()
            requireContext().container.notifyDataChanged()
            render()
        }

        binding.editPort.doAfterTextChanged { text ->
            val port = text?.toString()?.toIntOrNull() ?: return@doAfterTextChanged
            if (port in 1024..65535) settings.port = port
        }

        binding.switchAutoStart.setOnCheckedChangeListener { _, checked ->
            settings.autoStart = checked
        }

        binding.btnPermission.setOnClickListener { requestStorageAccess() }

        binding.aboutText.text = buildString {
            append(getString(R.string.settings_about_body))
            append("\n\n")
            val version = runCatching {
                requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
            }.getOrNull()
            append("版本：").append(version ?: "1.0.0")
        }

        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun render() {
        binding.dirText.text = settings.romDirPath
        binding.editPort.setText(settings.port.toString())
        binding.switchAutoStart.isChecked = settings.autoStart
        refreshPermissionText()
    }

    private fun refreshPermissionText() {
        val granted = StorageAccess.hasAccess(requireContext())
        binding.permissionText.text = if (granted) {
            "已获得「所有文件访问权限」，可直接读写 ROM 目录。"
        } else {
            "未获得权限：Android 11+ 需要「所有文件访问权限」才能读写 /sdcard 上的 ROM 目录。"
        }
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = StorageAccess.requestIntent(requireContext())
            if (intent != null) {
                runCatching { startActivity(intent) }
                return
            }
        }
        val granted = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            Toast.makeText(requireContext(), R.string.permission_already, Toast.LENGTH_SHORT).show()
        } else {
            legacyPermission.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                )
            )
        }
    }
}
