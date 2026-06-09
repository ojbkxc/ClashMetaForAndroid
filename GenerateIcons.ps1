Add-Type -AssemblyName System.Drawing

$sourcePath = "C:\GitHub\ClashMetaForAndroid\logo.png"
$source = [System.Drawing.Image]::FromFile($sourcePath)

function Resize-Image {
    param(
        [System.Drawing.Image]$source,
        [int]$size,
        [int]$safePadding = 0
    )
    
    $output = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($output)
    $g.Clear([System.Drawing.Color]::Transparent)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    
    $drawSize = $size - 2 * $safePadding
    $scale = [Math]::Min($drawSize / $source.Width, $drawSize / $source.Height)
    $newWidth = [int]($source.Width * $scale)
    $newHeight = [int]($source.Height * $scale)
    $x = ($size - $newWidth) / 2
    $y = ($size - $newHeight) / 2
    
    $g.DrawImage($source, [int]$x, [int]$y, $newWidth, $newHeight)
    $g.Dispose()
    
    return $output
}

function Create-BlackImage {
    param(
        [System.Drawing.Image]$source,
        [int]$size,
        [int]$safePadding = 0
    )
    
    $output = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($output)
    $g.Clear([System.Drawing.Color]::Transparent)
    
    $drawSize = $size - 2 * $safePadding
    $scale = [Math]::Min($drawSize / $source.Width, $drawSize / $source.Height)
    $newWidth = [int]($source.Width * $scale)
    $newHeight = [int]($source.Height * $scale)
    
    $temp = New-Object System.Drawing.Bitmap($newWidth, $newHeight, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $tempG = [System.Drawing.Graphics]::FromImage($temp)
    $tempG.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $tempG.DrawImage($source, 0, 0, $newWidth, $newHeight)
    $tempG.Dispose()
    
    $data = $temp.LockBits((New-Object System.Drawing.Rectangle(0,0,$newWidth,$newHeight)), [System.Drawing.Imaging.ImageLockMode]::ReadWrite, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $ptr = $data.Scan0
    $bytes = New-Object byte[] ($data.Stride * $data.Height)
    [System.Runtime.InteropServices.Marshal]::Copy($ptr, $bytes, 0, $bytes.Length)
    
    for ($i = 0; $i -lt $bytes.Length; $i += 4) {
        $alpha = $bytes[$i + 3]
        if ($alpha -gt 0) {
            $bytes[$i] = 0
            $bytes[$i + 1] = 0
            $bytes[$i + 2] = 0
            $bytes[$i + 3] = $alpha
        }
    }
    
    [System.Runtime.InteropServices.Marshal]::Copy($bytes, 0, $ptr, $bytes.Length)
    $temp.UnlockBits($data)
    
    $x = ($size - $newWidth) / 2
    $y = ($size - $newHeight) / 2
    $g.DrawImage($temp, [int]$x, [int]$y)
    $g.Dispose()
    $temp.Dispose()
    
    return $output
}

$baseDir = "C:\GitHub\ClashMetaForAndroid"

$dirs = @(
    "$baseDir\app\src\main\res\mipmap-mdpi",
    "$baseDir\app\src\main\res\mipmap-hdpi",
    "$baseDir\app\src\main\res\mipmap-xhdpi",
    "$baseDir\app\src\main\res\mipmap-xxhdpi",
    "$baseDir\app\src\main\res\mipmap-xxxhdpi",
    "$baseDir\app\src\main\res\drawable"
)

foreach ($dir in $dirs) {
    if (-not (Test-Path $dir)) {
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
        Write-Host "Created directory: $dir"
    }
}

$mdpi = 48
$hdpi = 72
$xhdpi = 96
$xxhdpi = 144
$xxxhdpi = 192

$img = Resize-Image $source $mdpi
$img.Save("$baseDir\app\src\main\res\mipmap-mdpi\ic_launcher.png", [System.Drawing.Imaging.ImageFormat]::Png)
$img.Dispose()
Write-Host "Created: mipmap-mdpi\ic_launcher.png"

$img = Resize-Image $source $mdpi
$img.Save("$baseDir\app\src\main\res\mipmap-mdpi\ic_launcher_round.png", [System.Drawing.Imaging.ImageFormat]::Png)
$img.Dispose()
Write-Host "Created: mipmap-mdpi\ic_launcher_round.png"

$img = Resize-Image $source $hdpi
$img.Save("$baseDir\app\src\main\res\mipmap-hdpi\ic_launcher.png", [System.Drawing.Imaging.ImageFormat]::Png)
$img.Dispose()
Write-Host "Created: mipmap-hdpi\ic_launcher.png"

$img = Resize-Image $source $hdpi
$img.Save("$baseDir\app\src\main\res\mipmap-hdpi\ic_launcher_round.png", [System.Drawing.Imaging.ImageFormat]::Png)
$img.Dispose()
Write-Host "Created: mipmap-hdpi\ic_launcher_round.png"

$img = Resize-Image $source $xhdpi
$img.Save("$baseDir\app\src\main\res\mipmap-xhdpi\ic_launcher.png", [System.Drawing.Imaging.ImageFormat]::Png)
$img.Dispose()
Write-Host "Created: mipmap-xhdpi\ic_launcher.png"

$img = Resize-Image $source $xhdpi
$img.Save("$baseDir\app\src\main\res\mipmap-xhdpi\ic_launcher_round.png", [System.Drawing.Imaging.ImageFormat]::Png)
$img.Dispose()
Write-Host "Created: mipmap-xhdpi\ic_launcher_round.png"

$img = Resize-Image $source $xxhdpi
$img.Save("$baseDir\app\src\main\res\mipmap-xxhdpi\ic_launcher.png", [System.Drawing.Imaging.ImageFormat]::Png)
$img.Dispose()
Write-Host "Created: mipmap-xxhdpi\ic_launcher.png"

$img = Resize-Image $source $xxhdpi
$img.Save("$baseDir\app\src\main\res\mipmap-xxhdpi\ic_launcher_round.png", [System.Drawing.Imaging.ImageFormat]::Png)
$img.Dispose()
Write-Host "Created: mipmap-xxhdpi\ic_launcher_round.png"

$img = Resize-Image $source $xxxhdpi
$img.Save("$baseDir\app\src\main\res\mipmap-xxxhdpi\ic_launcher.png", [System.Drawing.Imaging.ImageFormat]::Png)
$img.Dispose()
Write-Host "Created: mipmap-xxxhdpi\ic_launcher.png"

$img = Resize-Image $source $xxxhdpi
$img.Save("$baseDir\app\src\main\res\mipmap-xxxhdpi\ic_launcher_round.png", [System.Drawing.Imaging.ImageFormat]::Png)
$img.Dispose()
Write-Host "Created: mipmap-xxxhdpi\ic_launcher_round.png"

$img = Resize-Image $source 432 84
$img.Save("$baseDir\app\src\main\res\drawable\ic_launcher_foreground.png", [System.Drawing.Imaging.ImageFormat]::Png)
$img.Dispose()
Write-Host "Created: drawable\ic_launcher_foreground.png"

$img = Resize-Image $source 192
$img.Save("$baseDir\app\src\main\res\drawable\ic_clash.png", [System.Drawing.Imaging.ImageFormat]::Png)
$img.Dispose()
Write-Host "Created: drawable\ic_clash.png"

$img = Create-BlackImage $source 192
$img.Save("$baseDir\app\src\main\res\drawable\ic_meta_black.png", [System.Drawing.Imaging.ImageFormat]::Png)
$img.Dispose()
Write-Host "Created: drawable\ic_meta_black.png"

$source.Dispose()
Write-Host "`nAll icons generated successfully!"
