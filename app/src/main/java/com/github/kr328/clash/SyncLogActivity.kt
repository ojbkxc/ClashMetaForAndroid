package com.github.kr328.clash

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.github.kr328.clash.v2board.SyncLog
import android.graphics.Typeface
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
import java.io.File

class SyncLogActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics).toInt()
            setPadding(pad, pad, pad, pad)
        }

        // 标题栏：标题 + 复制按钮
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val mb = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics).toInt()
            setPadding(0, 0, 0, mb)
        }

        val title = TextView(this).apply {
            text = getString(com.github.kr328.clash.design.R.string.log_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleBar.addView(title)

        // 日志文件路径提示
        val logFile = File(getExternalFilesDir(null), "sync_log.txt")
        val pathText = TextView(this).apply {
            text = getString(com.github.kr328.clash.design.R.string.log_file_path, logFile.absolutePath)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            val mb = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics).toInt()
            setPadding(0, 0, 0, mb)
        }

        // 从文件读取日志
        val content = if (logFile.exists()) {
            try {
                logFile.readText()
            } catch (e: Exception) {
                getString(com.github.kr328.clash.design.R.string.log_read_failed, e.message ?: "")
            }
        } else {
            SyncLog.getFormatted()
        }
        val logText = if (content.isBlank()) getString(com.github.kr328.clash.design.R.string.log_empty) else content

        // 复制按钮
        val copyBtn = Button(this).apply {
            text = getString(com.github.kr328.clash.design.R.string.btn_copy)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, resources.displayMetrics).toInt()
            setPadding(px, 0, px, 0)
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("sync_log", logText))
                Toast.makeText(this@SyncLogActivity,
                    com.github.kr328.clash.design.R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
            }
        }
        titleBar.addView(copyBtn)

        layout.addView(titleBar)
        layout.addView(pathText)

        // 日志内容
        val logContent = TextView(this).apply {
            text = logText
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            val pad = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, resources.displayMetrics).toInt()
            setPadding(pad, pad, pad, pad)
            setTextIsSelectable(true)
        }

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
        }

        val hScroll = HorizontalScrollView(this).apply {
            addView(logContent, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        scrollView.addView(hScroll, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        layout.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0, 1f
        ))

        setContentView(layout)
    }
}
