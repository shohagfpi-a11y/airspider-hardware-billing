@echo off
title Hardware Billing - reset demo data
cd /d "%~dp0"
echo.
echo  This ERASES all local test data on this PC (the "data" folder) and starts again
echo  with fresh DEMO data: the reference invoice INV-12946, AirSpider logo, demo stamp.
echo.
choice /c YN /n /m "  Erase local test data and load the demo? [Y/N] "
if errorlevel 2 exit /b 0

echo  Stopping the local server...
powershell -NoProfile -Command "foreach($p in 8080,54329){ Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue } }"
timeout /t 3 >nul
if exist "data" rmdir /s /q "data"

set SPRING_PROFILES_ACTIVE=local
set SEED_DEMO=true
echo  Starting with demo data... your browser opens when it is ready.
start "" /min powershell -NoProfile -WindowStyle Hidden -Command "for($i=0;$i -lt 150;$i++){ try { Invoke-WebRequest http://localhost:8080/login -UseBasicParsing -TimeoutSec 2 | Out-Null; Start-Process 'http://localhost:8080'; break } catch { Start-Sleep -Seconds 2 } }"
call "%~dp0mvnw.cmd" spring-boot:run
pause
