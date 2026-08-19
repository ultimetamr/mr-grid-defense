param(
    [Parameter(Mandatory = $true)]
    [string]$InputDirectory,
    [Parameter(Mandatory = $true)]
    [string]$OutputDirectory,
    [int]$MaximumDimension = 1024
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

function Align-Four([int64]$Value) {
    return ($Value + 3) -band -4
}

function Resize-Png([byte[]]$SourceBytes, [int]$Limit) {
    $sourceStream = [IO.MemoryStream]::new($SourceBytes, 0, $SourceBytes.Length, $false, $true)
    try {
        $sourceImage = [Drawing.Image]::FromStream($sourceStream)
        try {
            $ratio = [Math]::Min(1.0, [Math]::Min($Limit / $sourceImage.Width, $Limit / $sourceImage.Height))
            $width = [Math]::Max(1, [int][Math]::Round($sourceImage.Width * $ratio))
            $height = [Math]::Max(1, [int][Math]::Round($sourceImage.Height * $ratio))
            $bitmap = [Drawing.Bitmap]::new($width, $height, [Drawing.Imaging.PixelFormat]::Format32bppArgb)
            try {
                $graphics = [Drawing.Graphics]::FromImage($bitmap)
                try {
                    $graphics.CompositingMode = [Drawing.Drawing2D.CompositingMode]::SourceCopy
                    $graphics.CompositingQuality = [Drawing.Drawing2D.CompositingQuality]::HighQuality
                    $graphics.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
                    $graphics.PixelOffsetMode = [Drawing.Drawing2D.PixelOffsetMode]::HighQuality
                    $graphics.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::HighQuality
                    $graphics.DrawImage($sourceImage, 0, 0, $width, $height)
                } finally {
                    $graphics.Dispose()
                }
                $output = [IO.MemoryStream]::new()
                try {
                    $bitmap.Save($output, [Drawing.Imaging.ImageFormat]::Png)
                    return [byte[]]$output.ToArray()
                } finally {
                    $output.Dispose()
                }
            } finally {
                $bitmap.Dispose()
            }
        } finally {
            $sourceImage.Dispose()
        }
    } finally {
        $sourceStream.Dispose()
    }
}

function Read-UInt32([byte[]]$Bytes, [int]$Offset) {
    return [BitConverter]::ToUInt32($Bytes, $Offset)
}

function Slice-Bytes([byte[]]$Bytes, [int]$Offset, [int]$Length) {
    $result = [byte[]]::new($Length)
    [Array]::Copy($Bytes, $Offset, $result, 0, $Length)
    return $result
}

function Optimize-Glb([IO.FileInfo]$InputFile, [string]$OutputPath, [int]$Limit) {
    $bytes = [IO.File]::ReadAllBytes($InputFile.FullName)
    if ([Text.Encoding]::ASCII.GetString($bytes, 0, 4) -ne "glTF") {
        throw "Not a GLB file: $($InputFile.FullName)"
    }
    if ((Read-UInt32 $bytes 4) -ne 2) {
        throw "Unsupported GLB version: $($InputFile.FullName)"
    }

    $jsonLength = [int](Read-UInt32 $bytes 12)
    if ([Text.Encoding]::ASCII.GetString($bytes, 16, 4) -ne "JSON") {
        throw "Missing JSON chunk: $($InputFile.FullName)"
    }
    $jsonText = [Text.Encoding]::UTF8.GetString($bytes, 20, $jsonLength).TrimEnd([char]0, [char]32)
    $document = $jsonText | ConvertFrom-Json
    if (@($document.buffers).Count -ne 1) {
        throw "Only single-buffer GLB files are supported: $($InputFile.FullName)"
    }

    $binHeaderOffset = 20 + $jsonLength
    $binLength = [int](Read-UInt32 $bytes $binHeaderOffset)
    if ([Text.Encoding]::ASCII.GetString($bytes, $binHeaderOffset + 4, 4) -ne "BIN`0") {
        throw "Missing BIN chunk: $($InputFile.FullName)"
    }
    $binOffset = $binHeaderOffset + 8

    $replacementByView = @{}
    foreach ($image in @($document.images)) {
        if ($image.mimeType -ne "image/png" -or $null -eq $image.bufferView) {
            throw "Only embedded PNG textures are supported: $($InputFile.FullName)"
        }
        $viewIndex = [int]$image.bufferView
        $view = $document.bufferViews[$viewIndex]
        $viewOffset = if ($null -eq $view.byteOffset) { 0 } else { [int]$view.byteOffset }
        $sourceImage = Slice-Bytes $bytes ($binOffset + $viewOffset) ([int]$view.byteLength)
        $replacementByView[$viewIndex] = Resize-Png $sourceImage $Limit
    }

    $newBin = [IO.MemoryStream]::new()
    try {
        $orderedViews =
            for ($index = 0; $index -lt @($document.bufferViews).Count; $index++) {
                $view = $document.bufferViews[$index]
                [PSCustomObject]@{
                    Index = $index
                    Offset = if ($null -eq $view.byteOffset) { 0 } else { [int]$view.byteOffset }
                    Length = [int]$view.byteLength
                }
            }
        $orderedViews = $orderedViews | Sort-Object Offset
        $previousEnd = 0
        foreach ($item in $orderedViews) {
            if ($item.Offset -lt $previousEnd) {
                throw "Overlapping GLB buffer views are not supported: $($InputFile.FullName)"
            }
            while (($newBin.Position % 4) -ne 0) { $newBin.WriteByte(0) }
            $view = $document.bufferViews[$item.Index]
            $view.byteOffset = [int64]$newBin.Position
            [byte[]]$payload =
                if ($replacementByView.ContainsKey($item.Index)) {
                    $replacementByView[$item.Index]
                } else {
                    Slice-Bytes $bytes ($binOffset + $item.Offset) $item.Length
                }
            $view.byteLength = $payload.Length
            $newBin.Write($payload, 0, $payload.Length)
            $previousEnd = $item.Offset + $item.Length
        }
        while (($newBin.Position % 4) -ne 0) { $newBin.WriteByte(0) }
        $document.buffers[0].byteLength = [int64]$newBin.Length

        $newJson = $document | ConvertTo-Json -Depth 100 -Compress
        [byte[]]$jsonBytes = [Text.Encoding]::UTF8.GetBytes($newJson)
        $paddedJsonLength = [int](Align-Four $jsonBytes.Length)
        $totalLength = 12 + 8 + $paddedJsonLength + 8 + [int]$newBin.Length

        $output = [IO.File]::Open($OutputPath, [IO.FileMode]::Create, [IO.FileAccess]::Write)
        $writer = [IO.BinaryWriter]::new($output)
        try {
            $writer.Write([uint32]0x46546C67)
            $writer.Write([uint32]2)
            $writer.Write([uint32]$totalLength)
            $writer.Write([uint32]$paddedJsonLength)
            $writer.Write([uint32]0x4E4F534A)
            $writer.Write($jsonBytes)
            for ($index = $jsonBytes.Length; $index -lt $paddedJsonLength; $index++) {
                $writer.Write([byte]0x20)
            }
            $writer.Write([uint32]$newBin.Length)
            $writer.Write([uint32]0x004E4942)
            $newBin.Position = 0
            $newBin.CopyTo($output)
        } finally {
            $writer.Dispose()
            $output.Dispose()
        }
    } finally {
        $newBin.Dispose()
    }
}

$inputRoot = (Resolve-Path -LiteralPath $InputDirectory).Path
$outputRoot = [IO.Path]::GetFullPath($OutputDirectory)
[IO.Directory]::CreateDirectory($outputRoot) | Out-Null

Get-ChildItem -LiteralPath $inputRoot -Filter "*.glb" -File | ForEach-Object {
    $outputPath = Join-Path $outputRoot $_.Name
    Optimize-Glb $_ $outputPath $MaximumDimension
    Write-Output "$($_.Name): $($_.Length) -> $((Get-Item -LiteralPath $outputPath).Length) bytes"
}
