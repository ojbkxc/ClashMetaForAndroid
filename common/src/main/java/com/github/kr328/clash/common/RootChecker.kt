package com.github.kr328.clash.common

import android.util.Log
import com.topjohnwu.superuser.Shell
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Root 权限检测与命令执行工具
 * 使用 libsu (topjohnwu) 库，参考 Shizuku 项目方案
 * 所有 root 操作都通过此类进行，确保安全性
 */
object RootChecker {
    private const val TAG = "RootChecker"

    init {
        // 配置 libsu Shell，参考 Shizuku 项目方案
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(30)  // 增加超时时间，参考 Shizuku 的 30s
        )
    }

    /**
     * 检测设备是否有 root 权限（不主动申请，不触发授权弹窗）
     * 参考 Shizuku 的 EnvironmentUtils.isRooted() 方案
     * 通过 PATH 环境变量查找 su 二进制文件
     */
    fun isRooted(): Boolean {
        return try {
            // 参考 Shizuku: 检查 PATH 环境变量中的 su 文件
            System.getenv("PATH")
                ?.split(File.pathSeparatorChar)
                ?.find { File("$it/su").exists() } != null
        } catch (e: Exception) {
            Log.d(TAG, "Root check failed: ${e.message}")
            false
        }
    }

    /**
     * 主动申请 root 权限
     * 使用 libsu 库获取 root shell，会触发 superuser 弹窗
     * 参考 Shizuku 项目的 startRoot() 方案
     * @return true 如果已获得 root 权限
     */
    fun requestRoot(): Boolean {
        return try {
            Log.d(TAG, "Requesting root access via libsu...")
            val shell = Shell.getShell()
            val isRoot = shell.isRoot
            Log.d(TAG, "Root shell obtained, isRoot=$isRoot")
            isRoot
        } catch (e: Exception) {
            Log.w(TAG, "Root request failed: ${e.message}")
            false
        }
    }

    /**
     * 重新请求 root 权限
     * 关闭现有 shell 并重新获取，参考 Shizuku 的重试机制
     * @return true 如果已获得 root 权限
     */
    fun requestRootWithRetry(): Boolean {
        return try {
            // 关闭现有 shell 缓存
            Shell.getCachedShell()?.close()
            // 重新请求
            requestRoot()
        } catch (e: Exception) {
            Log.w(TAG, "Root retry failed: ${e.message}")
            false
        }
    }

    /**
     * 以 root 权限执行命令
     * 使用 libsu Shell.cmd() 替代 Runtime.exec("su")
     * @return Pair<exitCode, output>
     */
    fun execute(command: String): Pair<Int, String> {
        return try {
            Log.d(TAG, "Executing root command: $command")
            val result = Shell.cmd(command).exec()
            val output = result.out.joinToString("\n").trim()
            val error = result.err.joinToString("\n").trim()
            val exitCode = result.code
            val combined = output + if (error.isNotBlank()) "\n$error" else ""
            Log.d(TAG, "Root command exit code: $exitCode")
            Pair(exitCode, combined)
        } catch (e: Exception) {
            Log.w(TAG, "Root command failed: $command - ${e.message}")
            Pair(-1, e.message ?: "Unknown error")
        }
    }

    /**
     * 以 root 权限执行命令（批量）
     * 通过单个 Shell 会话执行多条命令，避免反复启动 su
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
