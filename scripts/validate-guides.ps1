param([string]$Repository = (Split-Path $PSScriptRoot -Parent))
$ErrorActionPreference = 'Stop'
$guideTool = Join-Path (Split-Path $PSScriptRoot -Parent) 'tools/guide-tools.jar'
if (!(Test-Path -LiteralPath $guideTool)) { throw 'Build tooling: .\gradlew.bat guideTools inside achievo-guides (or communityGuideTools inside Achievo)' }
& java -jar $guideTool validate $Repository
exit $LASTEXITCODE
