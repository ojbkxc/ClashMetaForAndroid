package com.github.kr328.clash.common

import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Buffer pool for reducing memory allocation in UDP packet processing
 * Thread-safe implementation using ReentrantLock
 */
object BufferPool {
    private const val DEFAULT_BUFFER_SIZE = 8192
    private const val MAX_POOL_SIZE = 64
    
    private val pools = mutableMapOf<Int, ArrayDeque<ByteArray>>()
    private val lock = ReentrantLock()
    
    fun acquire(size: Int = DEFAULT_BUFFER_SIZE): ByteArray {
        return lock.withLock {
            val pool = pools.getOrPut(size) { ArrayDeque() }
            pool.pollFirst() ?: ByteArray(size)
        }
    }
    
    fun release(buffer: ByteArray) {
        val size = buffer.size
        lock.withLock {
            val pool = pools.getOrPut(size) { ArrayDeque() }
            if (pool.size < MAX_POOL_SIZE) {
                pool.add(buffer)
            }
        }
    }
    
    fun clear() {
        lock.withLock {
            pools.clear()
        }
    }
}

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
        // NOTE: Do NOT call checkSelinuxStatus() here - it would trigger
        // the superuser dialog at app startup, before the user can interact.
        // SELinux status is checked when root is first requested.
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
            
            // Execute a simple root command to trigger the superuser dialog.
            // Shell.cmd().exec() is more reliable than Shell.getShell().isRoot
            // because it actually invokes su and triggers the authorization prompt.
            val result = Shell.cmd("id").exec()
            val isRoot = result.code == 0
            Log.d(TAG, "Root request result: code=${result.code}, isRoot=$isRoot")
            
            // If root obtained, check SELinux status (deferred from init)
            if (isRoot && selinuxEnforcing == null) {
                checkSelinuxStatus()
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
            // Invalidate cached shell (libsu 6.x manages internal cache automatically)
            invalidateCachedShell()
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
     * Execute commands with root permission (batch mode)
     * Execute multiple commands through a single Shell session to avoid repeated su startup
     */
    fun executeBatch(commands: List<String>): Pair<Int, String> {
        return try {
            // Try to relax SELinux
            relaxSelinux()
            
            val fullCommand = commands.joinToString(" && ")
            execute(fullCommand)
        } catch (e: Exception) {
            Pair(-1, e.message ?: "Unknown error")
        }
    }
    
    /**
     * Execute commands in parallel for better performance
     * Uses coroutines to run independent commands concurrently
     * @param commands list of commands to execute
     * @return list of results in the same order as commands
     */
    suspend fun executeParallel(commands: List<String>): List<Pair<Int, String>> {
        if (commands.isEmpty()) {
            return emptyList()
        }
        
        return coroutineScope {
            commands.mapIndexed { index, cmd ->
                async(Dispatchers.IO) {
                    Log.d(TAG, "Executing command $index in parallel: ${cmd.take(30)}...")
                    execute(cmd)
                }
            }.awaitAll()
        }
    }
    
    /**
     * Execute commands using cached shell session for better performance
     * Reuses existing shell connection to reduce overhead
     * @param commands list of commands to execute
     * @return list of results in the same order as commands
     */
    fun executeWithCachedShell(commands: List<String>): List<Pair<Int, String>> {
        // libsu 6.x: Shell.getShell() returns Shell directly, no need for explicit job management
        // Fallback to executeBatch since libsu 6.x manages sessions automatically
        return try {
            // libsu 6.x handles shell lifecycle automatically
            commands.map { cmd ->
                execute(cmd)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to use cached shell: ${e.message}")
            commands.map { execute(it) }
        }
    }
    
    /**
     * Invalidate cached shell session
     * Call this when shell becomes invalid or root permission is revoked
     * Forces libsu to create a new shell on next request
     */
    fun invalidateCachedShell() {
        try {
            // Close the existing shell to force a new root authorization
            val shell = Shell.getShell()
            // The Shell class may not have public close(), use exec to verify
            // If shell is already cached with denied state, we need to force
            // a new shell creation by closing the session
            try {
                shell.close()
                Log.d(TAG, "Cached shell closed successfully")
            } catch (_: NoSuchMethodError) {
                // Some libsu versions don't expose close() publicly
                // Fallback: force exit the su process
                Log.d(TAG, "Shell.close() not available, using fallback")
                try {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "exit")).waitFor()
                } catch (_: Exception) {
                    Log.w(TAG, "Fallback shell close also failed")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to invalidate shell: ${e.message}")
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