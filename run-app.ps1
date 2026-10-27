<#
.SYNOPSIS
  在 PowerShell 里构建并启动 Trellis 桌面应用。

.DESCRIPTION
  这是给 Windows 原生 PowerShell 用的入口。.sh 脚本是给 bash（WSL / Git Bash）的：
  在 PowerShell 里直接敲 run-app.sh，或者双击它，Windows 会按文件关联打开编辑器
  （通常是 VSCode），而不是执行脚本。那不是脚本坏了，是入口选错了。

.EXAMPLE
  .\run-app.ps1
  .\run-app.ps1 --check
#>
$ErrorActionPreference = 'Stop'

# 不声明 param 块：`--check` 这种以 - 开头的写法在 param 里会被当成参数名
# （NamedParameterNotFound），而 .sh/.cmd 那边用惯了 `--check`。收进 $args 最省事。
$Mode = if ($args.Count -gt 0) { [string]$args[0] } else { '' }

function Fail([string]$message) {
    Write-Host $message -ForegroundColor Red
    exit 1
}

$repo = Split-Path -Parent $MyInvocation.MyCommand.Path
Push-Location $repo
try {
    if (-not $env:JAVA_HOME) {
        $candidate = Join-Path $env:USERPROFILE '.jdks\jdk17'
        if (Test-Path (Join-Path $candidate 'bin\java.exe')) {
            $env:JAVA_HOME = $candidate
        }
    }
    if ($env:JAVA_HOME) {
        $env:Path = $env:JAVA_HOME + '\bin;' + $env:Path
    }
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
        Fail '没有找到 JDK：设 JAVA_HOME，或把 JDK 17 放到 %USERPROFILE%\.jdks\jdk17（见 README 的「跑桌面应用」一节）。'
    }

    $gradle = Join-Path $repo 'gradlew.bat'
    if ($Mode -eq '' -or $Mode -eq '--run' -or $Mode -eq '-run') {
        & $gradle ':app:desktop:run' '--console=plain'
        exit $LASTEXITCODE
    }
    if ($Mode -eq '--check' -or $Mode -eq '-check' -or $Mode -eq '--compile') {
        & $gradle ':app:desktop:compileKotlin' '--console=plain'
        exit $LASTEXITCODE
    }
    Write-Host '用法：.\run-app.ps1 [--check]' -ForegroundColor Yellow
    exit 2
}
finally {
    Pop-Location
}
