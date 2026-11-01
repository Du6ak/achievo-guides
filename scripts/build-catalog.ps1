param([string]$Repository = (Split-Path $PSScriptRoot -Parent), [string]$Output = '', [string]$Timestamp = '1970-01-01T00:00:00Z')
$ErrorActionPreference = 'Stop'
if (!$Output) { $Output = Join-Path $Repository 'public' }
$guideTool = Join-Path (Split-Path $PSScriptRoot -Parent) 'tools/guide-tools.jar'
if (!(Test-Path -LiteralPath $guideTool)) { throw 'Build tooling: .\gradlew.bat guideTools inside achievo-guides (or communityGuideTools inside Achievo)' }
& java -jar $guideTool build $Repository $Output "--timestamp=$Timestamp"
exit $LASTEXITCODE
