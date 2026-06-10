package com.github.kr328.clash.design.model

data class PermissionFeature(
    val name: String,
    val vpnMode: Boolean,
    val rootMode: Boolean,
    val description: String
)