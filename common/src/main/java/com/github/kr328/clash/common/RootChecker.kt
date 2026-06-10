package com.github.kr328.clash.common

import android.util.Log
import com.topjohnwu.superuser.Shell
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Root permission detection and command execution utility
 * Uses libsu (topjohnwu) library, reference Shizuku project approach
 * All root operations go through this class for security
 */
object RootChecker {
    private const val TAG = "RootChecker"

    // SELinux status cache
    private var selinuxEnforcing: Boolean? = null
    private var useMagiskPolicy = false

    init {
        // Configure libsu Shell, reference Shizuku project approach
        // Note: Shell.enableVerboseLogging removed in libsu 6.x, use Shell.setDefaultBuilder instead
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(30000)  // Timeout in milliseconds (libsu 6.x uses ms)
        )
        
        // Detect SELinux status
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
     * Request root permission actively
     * Uses libsu library to obtain root shell, triggers superuser dialog
     * Reference Shizuku project's startRoot() approach
     * @return true if root permission obtained
     */
    fun requestRoot(): Boolean {
        return try {
            Log.d(TAG, "Requesting root access via libsu...")
            // libsu 6.x: Shell.getShell() returns Result<Shell>
            val result = Shell.getShell()
            if (!result.isSuccess) {
                Log.w(TAG, "Root request failed: Shell.getShell() returned failure")
                return false
            }
            val shell = result.getOrThrow()
            val isRoot = shell.isRoot
            Log.d(TAG, "Root shell obtained, isRoot=$isRoot")
            
            // If root obtained, try to relax SELinux restrictions
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
     * Re-request root permission
     * Close existing shell and re-obtain, reference Shizuku's retry mechanism
     * @return true if root permission obtained
     */
    fun requestRootWithRetry(): Boolean {
        return try {
            // libsu 6.x: Use Shell.close() to release cached shell
            Shell.close()
            // Re-request
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