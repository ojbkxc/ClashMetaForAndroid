package com.github.kr328.clash.util

import androidx.annotation.StringRes

sealed class AppError(
    @StringRes val messageRes: Int? = null,
    val message: String? = null,
    val cause: Throwable? = null
) : Exception(message, cause) {
    class NetworkError(message: String? = null, cause: Throwable? = null) : AppError(message = message, cause = cause)
    class FileError(message: String? = null, cause: Throwable? = null) : AppError(message = message, cause = cause)
    class ParseError(message: String? = null, cause: Throwable? = null) : AppError(message = message, cause = cause)
    class AuthError(message: String? = null, cause: Throwable? = null) : AppError(message = message, cause = cause)
    class ServiceError(message: String? = null, cause: Throwable? = null) : AppError(message = message, cause = cause)
    class PermissionError(message: String? = null, cause: Throwable? = null) : AppError(message = message, cause = cause)
}