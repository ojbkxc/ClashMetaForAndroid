package com.github.kr328.clash.design.model

import java.util.*

data class LogFile(val fileName: String, val date: Date, val isError: Boolean = false) {
    companion object {
        private val REGEX_FILE = Regex("clash-(\\d+).log")
        private val REGEX_ERROR = Regex("error-(\\d+).log")
        private const val FORMAT_FILE_NAME = "clash-%d.log"

        fun parseFromFileName(fileName: String): LogFile? {
            return REGEX_FILE.matchEntire(fileName)?.run {
                LogFile(fileName, Date(groupValues[1].toLong()))
            } ?: REGEX_ERROR.matchEntire(fileName)?.run {
                LogFile(fileName, Date(groupValues[1].toLong()), isError = true)
            }
        }

        fun generate(): LogFile {
            val current = Date()
            val fileName = FORMAT_FILE_NAME.format(current.time)

            return LogFile(fileName, current)
        }
    }
}