# Clones the pinned toolkits into external/ and re-applies our patches.
# Safe to re-run: each repo is reset to its pin before patching.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$ext  = Join-Path $root 'external'
New-Item -ItemType Directory -Force $ext | Out-Null

$pins = @(
    @{ name = 'pcrecomp';         url = 'https://github.com/sp00nznet/pcrecomp.git';        rev = '35548b8d0fd8aa853da67edcf7cd6134f6b9cdc7'; patches = 'patches/pcrecomp' },
    @{ name = 'FFNx';             url = 'https://github.com/julianxhokaxhiu/FFNx.git';      rev = '0adf0b32';                                 patches = 'patches/ffnx' },
    @{ name = 'nfsu2-recompiled'; url = 'https://github.com/codepdbh/nfsu2-recompiled.git'; rev = 'main';                                     patches = $null }
)

foreach ($p in $pins) {
    $dir = Join-Path $ext $p.name
    if (-not (Test-Path $dir)) { git clone -q $p.url $dir; if (-not $?) { throw "clone $($p.name) failed" } }
    git -C $dir fetch -q origin
    git -C $dir reset -q --hard
    git -C $dir clean -qfd
    git -C $dir checkout -q $p.rev
    if (-not $?) { throw "checkout $($p.name)@$($p.rev) failed" }
    if ($p.patches) {
        $pd = Join-Path $root $p.patches
        Get-ChildItem $pd -Filter '*.patch' -ErrorAction SilentlyContinue | Sort-Object Name | ForEach-Object {
            git -C $dir apply $_.FullName
            if (-not $?) { throw "patch $($_.Name) failed on $($p.name)" }
            Write-Host "  applied $($p.name)/$($_.Name)"
        }
    }
    Write-Host "$($p.name) @ $(git -C $dir rev-parse --short HEAD)"
}
