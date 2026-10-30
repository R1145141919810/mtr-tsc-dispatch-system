# Builds the dispatch service with the JDK that ships in tools/ (or JAVA_HOME).
#   .\build.ps1            compile + package + run the self test
#   .\build.ps1 -SkipTest  compile + package only
param([switch]$SkipTest)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

$candidates = @(@(
  $env:JAVA_HOME,
  (Join-Path (Split-Path -Parent $root) 'tools\jdk\jdk-17.0.20.1+1')
) | Where-Object { $_ -and (Test-Path (Join-Path $_ 'bin\javac.exe')) })

if ($candidates.Count -eq 0) { throw 'No JDK found. Set JAVA_HOME to a JDK 17 or newer.' }
$jdk = [string]$candidates[0]
$javac = Join-Path $jdk 'bin\javac.exe'
$jar = Join-Path $jdk 'bin\jar.exe'
$java = Join-Path $jdk 'bin\java.exe'

# Keep the artifact name in step with Config.VERSION (org/tsc/dispatch/config/Config.java).
$version = '1.1.0'
$classes = Join-Path $root 'build\classes'
$artifact = Join-Path $root "build\tsc-dispatch-$version.jar"
New-Item -ItemType Directory -Force -Path $classes | Out-Null
Remove-Item (Join-Path $classes '*') -Recurse -Force -ErrorAction SilentlyContinue

$sources = Get-ChildItem -Recurse -Filter *.java (Join-Path $root 'src') | ForEach-Object { $_.FullName }
Write-Host ("compiling {0} sources with {1}" -f $sources.Count, $jdk)
& $javac --release 17 -encoding UTF-8 -Xlint:all -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'compilation failed' }

& $jar --create --file $artifact --main-class org.tsc.dispatch.Main -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'packaging failed' }
Write-Host ("built {0} ({1:n0} bytes)" -f $artifact, (Get-Item $artifact).Length)

if (-not $SkipTest) {
  & $java -jar $artifact selftest
  exit $LASTEXITCODE
}
