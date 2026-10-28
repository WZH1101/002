@echo off
chcp 65001 >nul
REM =================================================================
REM  Reset MySQL80 root password to Ctrip@123456  (v3 fixed)
REM  Run as Administrator. Log: reset.log
REM  Fix v3: client uses --protocol=memory (not unsupported --shared-memory)
REM =================================================================
set "LOG=%~dp0reset.log"
set "MYSQLDIR=C:\mysql-8.0.44-winx64\bin"
echo ===== %date% %time% start =====> "%LOG%"

net session >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Not administrator. Right-click =^> Run as administrator
    pause & exit /b 1
)

echo [1/6] Stop service and clean leftovers...
net stop MySQL80 >> "%LOG%" 2>&1
taskkill /f /im mysqld.exe >> "%LOG%" 2>&1
timeout /t 3 /nobreak >nul

echo [2/6] Start maintenance mysqld (skip-grant-tables, wait 12s)...
start "mysqld-maintenance" /min "%MYSQLDIR%\mysqld.exe" --skip-grant-tables --shared-memory
timeout /t 12 /nobreak >nul

echo [3/6] Reset password via shared memory protocol...
"%MYSQLDIR%\mysql.exe" --protocol=memory -u root -e "FLUSH PRIVILEGES; ALTER USER 'root'@'localhost' IDENTIFIED BY 'Ctrip@123456';" >> "%LOG%" 2>&1
if errorlevel 1 (
    echo [ERROR] ALTER USER failed. Send this window + reset.log to assistant.
    taskkill /f /im mysqld.exe >> "%LOG%" 2>&1
    timeout /t 3 /nobreak >nul
    net start MySQL80 >> "%LOG%" 2>&1
    pause & exit /b 1
)

echo [4/6] Stop maintenance instance...
taskkill /f /im mysqld.exe >> "%LOG%" 2>&1
timeout /t 5 /nobreak >nul

echo [5/6] Start MySQL80 service normally...
net start MySQL80 >> "%LOG%" 2>&1
timeout /t 5 /nobreak >nul

echo [6/6] Verify new password...
"%MYSQLDIR%\mysql.exe" -u root -pCtrip@123456 -e "SELECT 'PASSWORD_VERIFY_OK';" >> "%LOG%" 2>&1
if errorlevel 1 (
    echo [ERROR] Verify failed. Send this window + reset.log to assistant.
    pause & exit /b 1
)

echo.
echo ==================================================
echo   DONE. root password = Ctrip@123456  (verified)
echo ==================================================
pause
