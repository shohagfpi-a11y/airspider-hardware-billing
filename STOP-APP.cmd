@echo off
title Hardware Billing - stop
echo Stopping the local server and its test database...
powershell -NoProfile -Command "foreach($p in 8080,54329){ Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue } }"
echo Done. Your data is kept in the "data" folder.
timeout /t 3 >nul
