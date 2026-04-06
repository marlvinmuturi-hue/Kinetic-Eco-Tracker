# Run build and capture full output to find Kotlin errors
$ErrorActionPreference = "Continue"
$output = & .\gradlew.bat assembleDebug --no-daemon 2>&1
$output | Out-File -FilePath "build-output.txt" -Encoding utf8
Write-Host "Build output saved to build-output.txt"
# Show lines that might contain errors
$output | Select-String -Pattern "e: |error:|Unresolved|\.kt:" | ForEach-Object { Write-Host $_.Line }
