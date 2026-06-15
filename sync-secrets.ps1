<#
.SYNOPSIS
将 .env 文件中的配置同步到 GitHub Secrets

.DESCRIPTION
读取项目根目录的 .env 文件，将其中的配置项同步到指定 GitHub 仓库的 Secrets 中

.PARAMETER Owner
GitHub 仓库所有者（用户名或组织名）

.PARAMETER Repo
GitHub 仓库名称

.EXAMPLE
.\sync-secrets.ps1 -Owner "lxseek" -Repo "ClashMetaForAndroid"

.NOTES
需要先安装 GitHub CLI: https://cli.github.com/
#>

param(
    [Parameter(Mandatory=$true)]
    [string]$Owner,
    
    [Parameter(Mandatory=$true)]
    [string]$Repo
)

# 检查 .env 文件是否存在
if (-not (Test-Path ".env")) {
    Write-Error ".env 文件不存在，请先从 .env.example 复制并修改"
    exit 1
}

# 读取 .env 文件内容
$content = Get-Content ".env" -Raw

# 解析 .env 文件
$vars = @{}
foreach ($line in $content -split "`n") {
    $line = $line.Trim()
    # 跳过注释和空行
    if ($line -match "^#" -or $line -eq "") {
        continue
    }
    # 匹配 KEY=VALUE 格式
    if ($line -match "^([^#=]+)=(.+)$") {
        $key = $matches[1].Trim()
        $value = $matches[2].Trim()
        $vars[$key] = $value
        Write-Host "找到配置: $key = $value"
    }
}

# 检查 GitHub CLI 是否安装
if (-not (Get-Command "gh" -ErrorAction SilentlyContinue)) {
    Write-Error "请先安装 GitHub CLI: https://cli.github.com/"
    Write-Host "安装后执行: gh auth login"
    exit 1
}

# 同步到 GitHub Secrets
Write-Host "`n开始同步 Secrets 到 $Owner/$Repo..."
foreach ($key in $vars.Keys) {
    $value = $vars[$key]
    Write-Host "同步 $key..."
    try {
        echo $value | gh secret set $key --repo "$Owner/$Repo"
        Write-Host "  ✓ 成功"
    } catch {
        Write-Host "  ✗ 失败: $_"
    }
}

Write-Host "`n所有 Secrets 同步完成！"
