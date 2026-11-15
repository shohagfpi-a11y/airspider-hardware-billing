@echo off
title Hardware Billing - local server
cd /d "%~dp0"
echo.
echo  ==========================================================
echo   Hardware Billing  -  local test server
echo  ==========================================================
echo.

where java >nul 2>nul
if errorlevel 1 (
  echo  [!] Java 21 was not found. Install Java 21 ^(Temurin^) and try again.
  echo.
  pause
  exit /b 1
)

REM port 8080 already used? (the app is probably already running)
netstat -ano | findstr /R /C:":8080 .*LISTENING" >nul
if not errorlevel 1 (
  echo  [!] Something is already running on port 8080 - the app may already be started.
  echo      Opening it in your browser. To restart it, run STOP-APP.cmd first.
  start "" http://localhost:8080
  echo.
  pause
  exit /b 0
)

set SPRING_PROFILES_ACTIVE=local
set SEED_DEMO=false

REM first run only: offer sample data
if not exist "data\pg" (
  echo  First run on this PC. Load SAMPLE DATA ^(demo company, customers, products, invoices^)?
  echo    Y = yes, with demo logins   admin / staff / viewer   ^(see README^)
  echo    N = no, start empty and use the real setup page ^(setup token is shown below^)
  echo.
  choice /c YN /n /m "  Load sample data? [Y/N] "
  if not errorlevel 2 set SEED_DEMO=true
  echo.
)

echo  Starting... the first start takes a minute ^(downloads/prepares things^).
echo  Your browser opens by itself at http://localhost:8080 when the app is ready.
echo  Keep this window open while you test. Close it, or run STOP-APP.cmd, to stop.
echo.

start "" /min powershell -NoProfile -WindowStyle Hidden -Command "for($i=0;$i -lt 150;$i++){ try { Invoke-WebRequest http://localhost:8080/login -UseBasicParsing -TimeoutSec 2 | Out-Null; Start-Process 'http://localhost:8080'; break } catch { Start-Sleep -Seconds 2 } }"

call "%~dp0mvnw.cmd" spring-boot:run

echo.
echo  The server has stopped.
pause
