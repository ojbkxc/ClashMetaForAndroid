package com.github.kr328.clash.common

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Root 权限检测与命令执行工具
 * 所有 root 操作都通过此类进行，确保安全性
 */
object RootChecker {
    private const val TAG = "RootChecker"

    /**
     * 检测设备是否有 root 权限（不主动申请，不触发授权弹窗）
     * 只检查 su 文件是否存在，不执行 su 命令
     */
    fun isRooted(): Boolean {
        return try {
            // 检查 su 文件是否存在
            val paths = arrayOf(
                "/system/bin/su", "/system/xbin/su",
                "/sbin/su", "/system/su",
                "/data/local/xbin/su", "/data/local/bin/su"
            )
            paths.any { java.io.File(it).exists() }
        } catch (e: Exception) {
            Log.d(TAG, "Root check failed: ${e.message}")
            false
        }
    }

    /**
     * 主动申请 root 权限
     * 会触发 superuser 弹窗请求用户授权
     * @return true 如果已获得 root 权限
     */
    fun requestRoot(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            Log.d(TAG, "Root request failed: ${e.message}")
            false
        }
    }

    /**
     * 以 root 权限执行命令
     * @return Pair<exitCode, output>
     */
    fun execute(command: String): Pair<Int, String> {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val error = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
            val exitCode = process.waitFor()
            val result = output.trim() + if (error.isNotBlank()) "\n${error.trim()}" else ""
            Pair(exitCode, result)
        } catch (e: Exception) {
            Log.w(TAG, "Root command failed: $command - ${e.message}")
            Pair(-1, e.message ?: "Unknown error")
        }
    }

    /**
     * 以 root 权限执行命令（批量）
     * 通过单个 su 进程执行多条命令，避免反复启动 su
     */
    fun executeBatch(commands: List<String>): Pair<Int, String> {
        return try {
            val fullCommand = commands.joinToString(" && ")
            execute(fullCommand)
        } catch (e: Exception) {
            Pair(-1, e.message ?: "Unknown error")
        }
    }

    /**
     * 检查 iptables 是否可用
     */
    fun isIptablesAvailable(): Boolean {
        val (code, _) = execute("iptables -L -n")
        return code == 0
    }
}
