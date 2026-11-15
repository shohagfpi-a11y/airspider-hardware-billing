@echo off
REM DEVELOPMENT ONLY: starts locally AND loads demo data (demo logins: admin / staff / viewer, see README).
cd /d "%~dp0.."
set SPRING_PROFILES_ACTIVE=local
set SEED_DEMO=true
call mvnw.cmd spring-boot:run
