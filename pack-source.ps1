

param(
    # Куди покласти zip. За замовчуванням: shaurma-lib-<дата-час>.zip у корені проєкту.
    [string]$OutputZip = "",

    # Не видаляти тимчасову staging-теку після пакування (для діагностики).
    [switch]$KeepStaging,

    # Друкувати лише підсумок, без покрокового виводу.
    [switch]$Quiet
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($root)) { $root = (Get-Location).Path }

# ── 1. Визначаємо компоненти ─────────────────────────────────────────────
# Кожен елемент: [ім'я теки в архіві, відносний шлях джерела].
$components = @(
    @("src",                 "src"),
 @("src",                 "src")
)

# Кореневі файли, що потрапляють в архів (джерела + документація).
# ВАЖЛИВО: build.bat тут НЕМАЄ навмисно — він містить РЕАЛЬНІ ключі
# (CurseForge/Groq/Gemini) і токени CDN; його місце лише на машині
# розробника. В архів йде БЕЗПЕЧНИЙ шаблон build.example.bat (плейсхолдери).
$rootFiles = @(
    "build.gradle",
    "gradle.properties",
    "settings.gradle"
)

# ── 2. Глобальні виключення (імена тек/файлів на БУДЬ-ЯКІЙ глибині) ─────
# Теки, що виключаються завжди.
$excludeDirs = @(
    "node_modules",   # npm/pnpm кеш залежностей
    "dist",           # збірки фронтенду / пакунки
    "build",      # зібрані бінарники та локальні дані
    "bin",            # будь-які bin (страховка від build\bin)
    ".git",
    ".wrangler",      # локальний кеш Cloudflare Worker
    "target",         # Rust/Java збірки
    ".gradle",
    ".idea",
    ".sqlx",
    ".turbo",
    ".cache",
    ".next",
    ".nuxt",
    ".output",
    "obj",
    "__pycache__",
    ".pytest_cache",
    ".mypy_cache",
    "vendor",         # сторонні залежності (Go/Rust)
    "bower_components",
    "venv",
    ".venv",
    "env",
    "modrith app",    # стороння довідкова кодова база
    "PrismLauncher-develop",  # C++ референс Prism Launcher
    "new build",      # тимчасова тека збірки
    ".mock"           # моки для тестів
)

# Файли, що виключаються завжди (за маскою імені).
$excludeFiles = @(
    "*.exe",
    "*.dll",
    "*.so",
    "*.dylib",
    "build.bat",      # секрети збірки (ключі/токени) — ніколи в архів!
    "*.zip",          # старі архіви / упаковані збірки
    "*.log",
    "*.tmp",
    "*.temp",
    "*.stackdump",
    "*.pdb",
    "*.pyc",
    "*.class",
    ".env",
    ".env.*",         # секрети (токени, ключі, паролі)
    "shaurma-launcher-backend.exe",  # зібраний exe (може лишитись у backend/)
    "*.syso",         # згенеровані wails ресурси
    "new build.zip"
)

# ── 3. Підготовка шляхів ────────────────────────────────────────────────
if ([string]::IsNullOrWhiteSpace($OutputZip)) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $OutputZip = Join-Path $root "shaurma-maniac-$stamp.zip"
}
$OutputZip = [System.IO.Path]::GetFullPath($OutputZip)

# Якщо цільова тека не існує (напр. -OutputZip "C:\tmp\src.zip") — створюємо.
$outDir = [System.IO.Path]::GetDirectoryName($OutputZip)
if (-not [string]::IsNullOrWhiteSpace($outDir) -and -not (Test-Path $outDir)) {
    New-Item -ItemType Directory -Path $outDir -Force | Out-Null
}

$staging = Join-Path $env:TEMP ("shaurma-source-" + [System.Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $staging -Force | Out-Null

function Write-Step($text) {
    if (-not $Quiet) { Write-Host $text -ForegroundColor Cyan }
}

# ── 4. Копіюємо компоненти через robocopy (швидко + підтримка виключень) ─
$copiedFiles = 0
$copiedBytes = [int64]0

foreach ($comp in $components) {
    $name = $comp[0]
    $src = Join-Path $root $comp[1]
    if (-not (Test-Path $src)) {
        Write-Step "  [!] пропущено (немає): $($comp[1])"
        continue
    }
    $dst = Join-Path $staging $name
    New-Item -ItemType Directory -Path $dst -Force | Out-Null

    Write-Step "  копіюю $($comp[1]) → $name ..."

    # ВАЖЛИВО: кожне виключення передаємо ОКРЕМИМ аргументом (масивом),
    # а не одним рядком з пробілами — robocopy не розбирає рядок сам.
    $rcArgs = @($src, $dst, '/E', '/XD') + $excludeDirs +
        @('/XF') + $excludeFiles +
        @('/NFL', '/NDL', '/NJH', '/NJS', '/NC', '/NS', '/NP')
    & robocopy @rcArgs | Out-Null

    if ($LASTEXITCODE -ge 8) {
        throw "robocopy: помилка копіювання компонента $($comp[1]) (код $LASTEXITCODE)"
    }
    $LASTEXITCODE = 0

    # Рахуємо, що реально скопійовано (для підсумку).
    $files = Get-ChildItem -Path $dst -Recurse -File -ErrorAction SilentlyContinue
    $copiedFiles += $files.Count
    if ($files.Count -gt 0) {
        $copiedBytes += ($files | Measure-Object -Property Length -Sum).Sum
    }
}

# Якщо нічого не скопійовано (усі компоненти відсутні/порожні) — пакувати нема чого.
if ($copiedFiles -eq 0) {
    Remove-Item $staging -Recurse -Force -ErrorAction SilentlyContinue
    throw "Нічого не скопійовано в staging — перевір, що компоненти існують у $root"
}

# ── 5. Кореневі файли ───────────────────────────────────────────────────
foreach ($f in $rootFiles) {
    $src = Join-Path $root $f
    if (Test-Path $src) {
        # Підтримка ПІДШЛЯХІВ (docs\dev\...) — створюємо теку призначення.
        $dst = Join-Path $staging $f
        $dstDir = Split-Path $dst -Parent
        if ($dstDir -and -not (Test-Path $dstDir)) { New-Item -ItemType Directory -Path $dstDir -Force | Out-Null }
        Copy-Item $src $dst -Force
        $fi = Get-Item $dst
        $copiedFiles++
        $copiedBytes += $fi.Length
    } else {
        Write-Step "  [!] кореневий файл відсутній: $f"
    }
}

# ── 6. Пакуємо в ZIP ──────────────────────────────────────────────
if (Test-Path $OutputZip) { Remove-Item $OutputZip -Force }

Write-Step ""
Write-Step "  пакую: $OutputZip ..."

# Пакуємо ВРУЧНУ через ZipArchive, а не ZipFile::CreateFromDirectory:
# .NET Framework пише імена записів зі зворотними слешами (\), що ламає
# розпакування на Linux/macOS та у веб-інтерфейсі GitHub. Тут нормалізуємо
# всі шляхи до прямих слешів (/) — так архів відкривається будь-де.
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$fs = [System.IO.File]::Open($OutputZip, [System.IO.FileMode]::Create)
try {
    $archive = New-Object System.IO.Compression.ZipArchive($fs, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        $allFiles = Get-ChildItem -Path $staging -Recurse -File -ErrorAction SilentlyContinue
        foreach ($file in $allFiles) {
            $rel = $file.FullName.Substring($staging.Length + 1).Replace('\', '/')
            $entry = $archive.CreateEntry($rel, [System.IO.Compression.CompressionLevel]::Optimal)
            $in = $file.OpenRead()
            try {
                $out = $entry.Open()
                try {
                    $in.CopyTo($out)
                } finally {
                    $out.Dispose()
                }
            } finally {
                $in.Dispose()
            }
        }
    } finally {
        $archive.Dispose()
    }
} catch {
    # Пакування впало — не лишаємо битий zip на диску.
    $fs.Dispose()
    Remove-Item $OutputZip -Force -ErrorAction SilentlyContinue
    throw
} finally {
    $fs.Dispose()
}

# ── 7. Перевірка та підсумок ────────────────────────────────────────────
$zipInfo = Get-Item $OutputZip
$zipSizeMb = [Math]::Round($zipInfo.Length / 1MB, 2)
$srcSizeMb = [Math]::Round($copiedBytes / 1MB, 2)

$zip = [System.IO.Compression.ZipFile]::OpenRead($OutputZip)
$entryCount = $zip.Entries.Count
$zip.Dispose()

Write-Step ""
Write-Step "  ── Готово ──────────────────────────────────────────"
Write-Step "  файлів у архіві : $entryCount"
Write-Step "  розмір джерела  : $srcSizeMb MB"
Write-Step "  розмір zip      : $zipSizeMb MB"
Write-Step "  архів           : $OutputZip"
Write-Step "  ──────────────────────────────────────────────────"
Write-Step "  Щоб перевірити вміст:"
Write-Step "    Expand-Archive -Path `"$OutputZip`" -DestinationPath .\check"

# Прибираємо staging, якщо не попросили лишити.
if (-not $KeepStaging) {
    Remove-Item $staging -Recurse -Force -ErrorAction SilentlyContinue
} else {
    Write-Step "  staging збережено: $staging"
}

Write-Host ""
Write-Host "Готово: $OutputZip" -ForegroundColor Green
