package com.cdgeass.rombox.ui.common

import android.app.Activity
import android.app.AlertDialog
import android.widget.ArrayAdapter
import android.widget.ListView
import java.io.File

/**
 * 极简目录选择器（不需要 SAF，配合「所有文件访问权限」直接选绝对路径）。
 */
object DirPicker {

    fun show(activity: Activity, start: File, onPick: (File) -> Unit) {
        val initial = when {
            start.isDirectory -> start
            start.parentFile?.isDirectory == true -> start.parentFile!!
            else -> File("/storage/emulated/0")
        }
        var current = initial

        val listView = ListView(activity)
        val adapter = ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1)
        listView.adapter = adapter

        val dialog = AlertDialog.Builder(activity)
            .setTitle("选择 ROM 目录")
            .setView(listView)
            .setPositiveButton("用此目录", null)
            .setNegativeButton("取消", null)
            .create()

        fun reload() {
            adapter.clear()
            val parent = current.parentFile
            if (parent != null) adapter.add("↑ 上级目录")
            childDirs(current).forEach { adapter.add("📁 ${it.name}") }
            if (childDirs(current).isEmpty()) adapter.add("（没有子目录）")
            dialog.setTitle("选择目录\n${current.absolutePath}")
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            val offset = if (current.parentFile != null) 1 else 0
            if (offset == 1 && position == 0) {
                current = current.parentFile ?: current
            } else {
                val dir = childDirs(current).getOrNull(position - offset) ?: return@setOnItemClickListener
                current = dir
            }
            reload()
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                onPick(current)
                dialog.dismiss()
            }
        }

        reload()
        dialog.show()
    }

    private fun childDirs(dir: File): List<File> {
        val children = dir.listFiles() ?: return emptyList()
        return children
            .filter { it.isDirectory && !it.name.startsWith(".") && it.canRead() }
            .sortedBy { it.name.lowercase() }
    }
}
