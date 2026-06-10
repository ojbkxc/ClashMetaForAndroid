package com.github.kr328.clash

import com.github.kr328.clash.design.PermissionInfoDesign

class PermissionInfoActivity : BaseActivity<PermissionInfoDesign>() {
    override suspend fun main() {
        val design = PermissionInfoDesign(this)
        setContentDesign(design)
    }
}