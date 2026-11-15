@echo off
REM Starts the app locally with an embedded PostgreSQL (nothing to install except Java 21).
REM Open http://localhost:8080 - first run shows the setup page; the setup token is printed in this window.
cd /d "%~dp0.."
set SPRING_PROFILES_ACTIVE=local
set SEED_DEMO=false
call mvnw.cmd spring-boot:run
