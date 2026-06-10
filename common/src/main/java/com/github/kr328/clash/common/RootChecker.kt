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

    // SELinux 状态缓存
    private var selinuxEnforcing: Boolean? = null
    private var useMagiskPolicy = false

    init {
        // 配置 libsu Shell，参考 Shizuku 项目方案
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(30)  // 增加超时时间，参考 Shizuku 的 30s
        )
        
        // 检测 SELinux 状态
        checkSelinuxStatus()
    }

    /**
     * 检测 SELinux 状态
     */
    private fun checkSelinuxStatus() {
        try {
            val (code, output) = executeCommand("getenforce")
            if (code == 0) {
                selinuxEnforcing = output.trim().equals("Enforcing", ignoreCase = true)
                Log.d(TAG, "SELinux status: ${if (selinuxEnforcing == true) "Enforcing" else "Permissive"}")
            }
            
            // 检查是否有 magiskpolicy 命令
            val (magiskCode) = executeCommand("which magiskpolicy")
            useMagiskPolicy = magiskCode == 0
            Log.d(TAG, "Magisk policy available: $useMagiskPolicy")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check SELinux status: ${e.message}")
        }
    }

    /**
     * 检查 SELinux 是否处于 Enforcing 模式
     */
    fun isSelinuxEnforcing(): Boolean {
        return selinuxEnforcing ?: false
    }

    /**
     * 临时放宽 SELinux 限制（用于执行 iptables 等操作）
     * 在 Android 12+ 上，直接执行 iptables 可能被 SELinux 阻止
     */
    fun relaxSelinux(): Boolean {
        if (!isSelinuxEnforcing()) {
            return true
        }

        // 方法1：尝试使用 magiskpolicy 允许 net_raw 权限
        if (useMagiskPolicy) {
            val commands = listOf(
                "magiskpolicy --live 'allow untrusted_app * * net_raw_socket'",
                "magiskpolicy --live 'allow untrusted_app * * net_admin_socket'",
                "magiskpolicy --live 'allow untrusted_app * * rawip_socket'"
            )
            for (cmd in commands) {
                val (code, output) = executeCommand(cmd)
                if (code != 0) {
                    Log.w(TAG, "Failed to apply magiskpolicy: $cmd - $output")
                }
            }
            return true
        }

        // Method 2: Try using su -M mode (magisk relaxed permission mode)
        // Execute command directly with su -M prefix
        try {
            val result = Shell.cmd("su -M", "id").exec()
            if (result.code == 0) {
                Log.d(TAG, "Successfully using su -M mode")
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to use su -M mode: ${e.message}")
        }

        Log.w(TAG, "SELinux is enforcing and no magiskpolicy available")
        return false
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
            
            // 如果获取到 root，尝试放宽 SELinux 限制
            if (isRoot) {
                relaxSelinux()
            }
            
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
     * 执行命令（内部方法，不处理 SELinux）
     */
    private fun executeCommand(command: String): Pair<Int, String> {
        return try {
            val result = Shell.cmd(command).exec()
            val output = result.out.joinToString("\n").trim()
            val error = result.err.joinToString("\n").trim()
            val exitCode = result.code
            val combined = output + if (error.isNotBlank()) "\n$error" else ""
            Pair(exitCode, combined)
        } catch (e: Exception) {
            Pair(-1, e.message ?: "Unknown error")
        }
    }

    /**
     * 以 root 权限执行命令
     * 使用 libsu Shell.cmd() 替代 Runtime.exec("su")
     * 自动处理 SELinux 限制
     * @return Pair<exitCode, output>
     */
    fun execute(command: String): Pair<Int, String> {
        return try {
            Log.d(TAG, "Executing root command: $command")
            
            // 对于 iptables/netfilter 相关命令，尝试放宽 SELinux
            if (command.contains("iptables") || command.contains("ip ") || command.contains("sysctl")) {
                relaxSelinux()
            }
            
            val result = Shell.cmd(command).exec()
            val output = result.out.joinToString("\n").trim()
            val error = result.err.joinToString("\n").trim()
            val exitCode = result.code
            val combined = output + if (error.isNotBlank()) "\n$error" else ""
            Log.d(TAG, "Root command exit code: $exitCode")
            
            // 如果命令失败且 SELinux 是 enforcing，记录警告
            if (exitCode != 0 && isSelinuxEnforcing()) {
                Log.w(TAG, "Command failed with SELinux enforcing: $command")
            }
            
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
            // 尝试放宽 SELinux
            relaxSelinux()
            
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

    /**
     * 检查 ip6tables 是否可用
     */
    fun isIp6tablesAvailable(): Boolean {
        val (code, _) = execute("ip6tables -L -n")
        return code == 0
    }

    /**
     * 检查 ip 命令是否可用
     */
    fun isIpCommandAvailable(): Boolean {
        val (code, _) = execute("ip -V")
        return code == 0
    }
}