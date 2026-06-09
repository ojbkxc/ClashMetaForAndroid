Add-Type -AssemblyName System.Drawing

$sourcePath = "c:\GitHub\ClashMetaForAndroid\logo.png"
$baseDir = "c:\GitHub\ClashMetaForAndroid\app\src\main\res"

# 尺寸定义
$sizes = @{
    "mdpi" = 48
    "hdpi" = 72
    "xhdpi" = 96
    "xxhdpi" = 144
    "xxxhdpi" = 192
}

# 加载源图
$source = New-Object System.Drawing.Bitmap($sourcePath)
Write-Host "Source image size:" $source.Width "x" $source.Height

# 生成各尺寸mipmap图标
foreach ($key in $sizes.Keys) {
    $size = $sizes[$key]
    
    # 创建目标Bitmap
    $dest = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($dest)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $g.Clear([System.Drawing.Color]::Transparent)
    
    # 计算缩放以填满
    $ratio = [Math]::Max($size / $source.Width, $size / $source.Height)
    $newWidth = [int]($source.Width * $ratio)
    $newHeight = [int]($source.Height * $ratio)
    $x = [int](($size - $newWidth) / 2)
    $y = [int](($size - $newHeight) / 2)
    
    $g.DrawImage($source, $x, $y, $newWidth, $newHeight)
    $g.Dispose()
    $dest.Save("$baseDir\mipmap-$key\ic_launcher.png", [System.Drawing.Imaging.ImageFormat]::Png)
    $dest.Dispose()
    Write-Host "Created: mipmap-$key\ic_launcher.png"
    
    # Round图标
    $dest = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($dest)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $g.Clear([System.Drawing.Color]::Transparent)
    $g.DrawImage($source, $x, $y, $newWidth, $newHeight)
    $g.Dispose()
    $dest.Save("$baseDir\mipmap-$key\ic_launcher_round.png", [System.Drawing.Imaging.ImageFormat]::Png)
    $dest.Dispose()
    Write-Host "Created: mipmap-$key\ic_launcher_round.png"
}

# 生成前景图 - 432x432
$size = 432
$dest = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$g = [System.Drawing.Graphics]::FromImage($dest)
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
$g.Clear([System.Drawing.Color]::Transparent)
$ratio = [Math]::Max($size / $source.Width, $size / $source.Height)
$newWidth = [int]($source.Width * $ratio)
$newHeight = [int]($source.Height * $ratio)
$x = [int](($size - $newWidth) / 2)
$y = [int](($size - $newHeight) / 2)
$g.DrawImage($source, $x, $y, $newWidth, $newHeight)
$g.Dispose()
$dest.Save("$baseDir\drawable\ic_launcher_foreground.png", [System.Drawing.Imaging.ImageFormat]::Png)
$dest.Dispose()
Write-Host "Created: drawable\ic_launcher_foreground.png (filled)"

# 生成主页图标 - 192x192
$size = 192
$dest = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$g = [System.Drawing.Graphics]::FromImage($dest)
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
$g.Clear([System.Drawing.Color]::Transparent)
$ratio = [Math]::Max($size / $source.Width, $size / $source.Height)
$newWidth = [int]($source.Width * $ratio)
$newHeight = [int]($source.Height * $ratio)
$x = [int](($size - $newWidth) / 2)
$y = [int](($size - $newHeight) / 2)
$g.DrawImage($source, $x, $y, $newWidth, $newHeight)
$g.Dispose()
$dest.Save("$baseDir\drawable\ic_clash.png", [System.Drawing.Imaging.ImageFormat]::Png)
$dest.Dispose()
Write-Host "Created: drawable\ic_clash.png"

$source.Dispose()
Write-Host "`nAll icons generated successfully!"
