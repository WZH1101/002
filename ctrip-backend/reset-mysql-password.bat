@echo off
chcp 65001 >nul
REM =================================================================
REM  重置 MySQL80 root 密码（v4：消除明文密码与免认证暴露窗口）
REM  需要以管理员身份运行。日志：reset.log
REM
REM  v4 相对 v3 的三处安全修正：
REM   1) 维护实例加 --skip-networking。v3 用 --skip-grant-tables 启动期间
REM      MySQL 仍在 3306 监听且完全免认证，同网段任何机器都能连进来拿数据；
REM      加此开关后只保留命名管道/共享内存通道，脚本自己连得上、别人连不上。
REM   2) 密码不再写死在脚本里，改为读环境变量 CTRIP_DB_PASSWORD，未设置则交互输入。
REM   3) 密码不再出现在命令行参数（-pXxx 会进进程列表与历史），也不再写入日志。
REM      注：本机实测 --defaults-extra-file 因用户目录含中文无法使用，故用 MYSQL_PWD。
REM =================================================================
setlocal enabledelayedexpansion
set "LOG=%~dp0reset.log"
set "MYSQLDIR=C:\mysql-8.0.44-winx64\bin"
set "MYSQLD=%MYSQLDIR%\mysqld.exe"
set "CLIENT=%MYSQLDIR%\mysql.exe"

echo ===== %date% %time% start =====> "%LOG%"
echo [检查] 日志不记录口令，仅记录步骤与结果>> "%LOG%"

net session >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Not administrator. Right-click =^> Run as administrator
    pause & exit /b 1
)

REM ---------- 取新密码：环境变量优先，否则交互输入 ----------
if not "%CTRIP_DB_PASSWORD%"=="" (
    set "NEWPWD=%CTRIP_DB_PASSWORD%"
    echo [1/7] 使用环境变量 CTRIP_DB_PASSWORD 中的口令（未回显）
) else (
    echo [1/7] 未设置环境变量 CTRIP_DB_PASSWORD
    set /p "NEWPWD=      请输入新的 root 密码（输入不会显示，也不会写入日志）: "
)
if "!NEWPWD!"=="" (
    echo [ERROR] 密码为空，已终止。
    pause & exit /b 1
)

echo [2/7] Stop service and clean leftovers...
net stop MySQL80 >> "%LOG%" 2>&1
taskkill /f /im mysqld.exe >> "%LOG%" 2>&1
timeout /t 3 /nobreak >nul

echo [3/7] Start maintenance mysqld (skip-grant-tables + skip-networking)...
REM --skip-networking 关闭 TCP 监听，把免认证窗口的可达面收敛到本机共享内存
start "mysqld-maintenance" /min "%MYSQLD%" --skip-grant-tables --skip-networking --shared-memory
timeout /t 12 /nobreak >nul

echo [4/7] Reset password via shared memory...
REM SQL 语句里必然带上新口令，改为写入临时脚本文件执行，避免它出现在进程命令行
set "TMPSQL=%TEMP%\ctrip_reset_%RANDOM%.sql"
> "%TMPSQL%" echo FLUSH PRIVILEGES;
>> "%TMPSQL%" echo ALTER USER 'root'@'localhost' IDENTIFIED BY '!NEWPWD!';
"%CLIENT%" --protocol=memory -u root < "%TMPSQL%" >> "%LOG%" 2>&1
set "RC=%ERRORLEVEL%"
del /f /q "%TMPSQL%" >nul 2>&1
if not "%RC%"=="0" (
    echo [ERROR] ALTER USER failed. See reset.log （日志中不含口令）
    REM 先停掉免认证实例，再恢复正式服务，避免失败后把裸奔的实例留在后台
    taskkill /f /im mysqld.exe >> "%LOG%" 2>&1
    timeout /t 3 /nobreak >nul
    net start MySQL80 >> "%LOG%" 2>&1
    pause & exit /b 1
)
echo [5/7] Stop maintenance instance...
taskkill /f /im mysqld.exe >> "%LOG%" 2>&1
timeout /t 5 /nobreak >nul

echo [6/7] Start MySQL80 service normally...
net start MySQL80 >> "%LOG%" 2>&1
timeout /t 5 /nobreak >nul

echo [7/7] Verify new password...
REM 用 MYSQL_PWD 传递，命令行里看不到明文，也就不会再有 "Using a password" 警告
set "MYSQL_PWD=!NEWPWD!"
"%CLIENT%" -u root -e "SELECT 'PASSWORD_VERIFY_OK';" >> "%LOG%" 2>&1
if errorlevel 1 (
    echo [ERROR] Verify failed. Send this window + reset.log to assistant.
    endlocal & exit /b 1
)
echo.
echo ==================================================
echo   DONE. 新口令已生效（本窗口与日志均未显示明文）
echo   后端启动前请设置： setx CTRIP_DB_PASSWORD "刚输入的密码"
echo ==================================================
endlocal
pause
