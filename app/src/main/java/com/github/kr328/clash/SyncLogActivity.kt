package com.github.kr328.clash

import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.github.kr328.clash.v2board.SyncLog
import android.view.Gravity
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

        // 标题
        val title = TextView(this).apply {
            text = "同步日志"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(null, Typeface.BOLD)
            val mb = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics).toInt()
            setPadding(0, 0, 0, mb)
        }
        layout.addView(title)

        // 日志文件路径提示
        val logFile = File(getExternalFilesDir(null), "sync_log.txt")
        val pathText = TextView(this).apply {
            text = "日志文件: ${logFile.absolutePath}"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            val mb = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics).toInt()
            setPadding(0, 0, 0, mb)
        }
        layout.addView(pathText)

        // 日志内容
        val logContent = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            val pad = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, resources.displayMetrics).toInt()
            setPadding(pad, pad, pad, pad)
        }

        // 从文件读取日志
        val content = if (logFile.exists()) {
            try {
                logFile.readText()
            } catch (e: Exception) {
                "读取日志文件失败: ${e.message}"
            }
        } else {
            // 回退到内存中的日志
            SyncLog.getFormatted()
        }
        logContent.text = if (content.isBlank()) "暂无日志" else content

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
        }

        // 用 HorizontalScrollView 包裹以支持横向滚动
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
