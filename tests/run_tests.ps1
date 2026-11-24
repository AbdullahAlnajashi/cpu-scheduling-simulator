# Compiles the simulator and the tests, then runs them.
# Usage (from the project folder):
#   powershell -ExecutionPolicy Bypass -File tests\run_tests.ps1          # scenario tests
#   powershell -ExecutionPolicy Bypass -File tests\run_tests.ps1 60       # + 60 random job files x 6 modes
param([int]$RandomFiles = 0)

$root  = Split-Path -Parent $PSScriptRoot
$build = Join-Path $root "build"
$app   = Join-Path $build "app"
$tst   = Join-Path $build "tests"
New-Item -ItemType Directory -Force $app, $tst | Out-Null

& javac -d $app (Get-ChildItem (Join-Path $root "src\*.java")).FullName
if (-not $?) { Write-Host "Simulator does not compile"; exit 1 }
& javac -d $tst (Join-Path $PSScriptRoot "SimulatorTest.java")
if (-not $?) { Write-Host "Tests do not compile"; exit 1 }

& java -cp $tst SimulatorTest $app $RandomFiles
exit $LASTEXITCODE
