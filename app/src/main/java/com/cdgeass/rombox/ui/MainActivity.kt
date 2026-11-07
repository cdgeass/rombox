package com.cdgeass.rombox.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.cdgeass.rombox.R
import com.cdgeass.rombox.container
import com.cdgeass.rombox.databinding.ActivityMainBinding
import com.cdgeass.rombox.ui.roms.RomsFragment
import com.cdgeass.rombox.ui.settings.SettingsFragment
import com.cdgeass.rombox.ui.transfer.TransferFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val fragments = mutableMapOf<Int, Fragment>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val restored = savedInstanceState?.getInt(KEY_TAB)
        if (restored != null) {
            binding.bottomNav.selectedItemId = restored
            showTab(restored)
        } else {
            binding.bottomNav.selectedItemId = R.id.tab_roms
            showTab(R.id.tab_roms)
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
        }

        container.maybeAutoStart(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(KEY_TAB, binding.bottomNav.selectedItemId)
        super.onSaveInstanceState(outState)
    }

    /** 用 hide/show 切换，保留各页状态（避免切换 Tab 就重新扫描） */
    private fun showTab(itemId: Int) {
        val manager = supportFragmentManager
        val transaction = manager.beginTransaction()
        fragments.values.forEach { if (it.isAdded && !it.isHidden) transaction.hide(it) }

        val fragment = fragments.getOrPut(itemId) {
            manager.findFragmentByTag(itemId.toString()) ?: createFragment(itemId)
        }
        if (fragment.isAdded) {
            transaction.show(fragment)
        } else {
            transaction.add(R.id.container, fragment, itemId.toString())
        }
        transaction.commitNowAllowingStateLoss()
    }

    private fun createFragment(itemId: Int): Fragment = when (itemId) {
        R.id.tab_transfer -> TransferFragment()
        R.id.tab_settings -> SettingsFragment()
        else -> RomsFragment()
    }

    companion object {
        private const val KEY_TAB = "tab"
    }
}
