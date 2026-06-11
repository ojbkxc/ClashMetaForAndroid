package com.github.kr328.clash.core

import java.io.File

/**
 * YAML config optimizer that injects performance tuning defaults
 * for protocols that benefit from explicit parameter tuning.
 *
 * Operates on the config.yaml before it is loaded by the Go clash core.
 * Modifications are additive: existing user-defined values are preserved.
 */
object ConfigOptimizer {

    /**
     * Optimize the clash config YAML at [configDir] (directory containing config.yaml).
     *
     * Applies:
     * - Hysteria2: adds sensible up/down bandwidth defaults if not specified
     * - TUIC: adds max-open-streams based on CPU core count if not specified
     *
     * @return true if the file was modified
     */
    fun optimize(configDir: File): Boolean {
        val configFile = File(configDir, "config.yaml")
        if (!configFile.isFile) return false

        val content = configFile.readText()
        val optimized = applyOptimizations(content)
        if (optimized == content) return false

        configFile.writeText(optimized)
        return true
    }

    private fun applyOptimizations(yaml: String): String {
        val lines = yaml.lines().toMutableList()
        val cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.matches(Regex("""^\s*-?\s*type:\s*hysteria2\s*$""")) -> {
                    i = optimizeHysteria2(lines, i, cpuCores)
                }
                line.matches(Regex("""^\s*-?\s*type:\s*tuic\s*$""")) -> {
                    i = optimizeTuic(lines, i, cpuCores)
                }
                else -> i++
            }
        }

        return lines.joinToString("\n")
    }

    /**
     * Hysteria2 proxy block optimization.
     *
     * Defaults:
     *   up: 50 Mbps (adequate for most mobile networks)
     *   down: 100 Mbps
     *
     * Only injected if the proxy block lacks these fields.
     * Respects existing user-provided values.
     */
    private fun optimizeHysteria2(lines: MutableList<String>, typeLineIndex: Int, cpuCores: Int): Int {
        val indent = getIndent(lines[typeLineIndex])
        var hasUp = false
        var hasDown = false
        var insertIndex = typeLineIndex + 1

        // Scan forward to find the end of this proxy block
        var i = typeLineIndex + 1
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) { i++; continue }

            if (getIndent(line) <= indent && !line.trimStart().startsWith("- ")) {
                // End of this proxy block
                break
            }

            when {
                line.trimStart().startsWith("up:") -> hasUp = true
                line.trimStart().startsWith("down:") -> hasDown = true
            }

            insertIndex = i + 1
            i++
        }

        if (!hasDown) {
            // Insert before the last field, or before the next proxy
            val downLine = "${indent}  down: \"100 Mbps\""
            lines.add(insertIndex, downLine)
            insertIndex++
            i++
        }
        if (!hasUp) {
            val upLine = "${indent}  up: \"50 Mbps\""
            lines.add(insertIndex, upLine)
            i++
        }

        return i
    }

    /**
     * TUIC proxy block optimization.
     *
     * Default: max-open-streams = cpuCores * 25
     * (e.g., 8-core → 200, 4-core → 100 streams)
     *
     * Only injected if the proxy block lacks this field.
     */
    private fun optimizeTuic(lines: MutableList<String>, typeLineIndex: Int, cpuCores: Int): Int {
        val indent = getIndent(lines[typeLineIndex])
        var hasMaxOpenStreams = false
        var insertIndex = typeLineIndex + 1

        var i = typeLineIndex + 1
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) { i++; continue }

            if (getIndent(line) <= indent && !line.trimStart().startsWith("- ")) {
                break
            }

            if (line.trimStart().startsWith("max-open-streams")) {
                hasMaxOpenStreams = true
            }

            insertIndex = i + 1
            i++
        }

        if (!hasMaxOpenStreams) {
            val maxStreams = (cpuCores * 25).coerceIn(50, 500)
            val mosLine = "${indent}  max-open-streams: $maxStreams"
            lines.add(insertIndex, mosLine)
            i++
        }

        return i
    }

    private fun getIndent(line: String): String {
        val trimmed = line.trimStart()
        val spaces = line.length - trimmed.length
        return line.substring(0, spaces)
    }
}