@echo off
chcp 65001 >nul
REM =================================================================
REM  初始化数据库：建库建表 + 导入真实数据（车次/航班/酒店/景点）
REM  前提: 已运行 reset-mysql-password.bat
REM  注意: 重复执行会重建所有表（订单数据将被清空！）
REM
REM  口令来源：环境变量 CTRIP_DB_PASSWORD，未设置则交互输入。
REM  不再使用 -pXxx（明文会出现在命令行、进程列表与 dos 历史里）。
REM =================================================================
setlocal enabledelayedexpansion
cd /d %~dp0

if "%CTRIP_DB_PASSWORD%"=="" (
    set /p "DBPWD=请输入 MySQL root 密码（输入不显示）: "
) else (
    set "DBPWD=%CTRIP_DB_PASSWORD%"
)
if "!DBPWD!"=="" (
    echo 密码为空，已终止。
    pause & exit /b 1
)

echo 正在导入 sql\init.sql ... 重复执行会重建所有表，订单数据将被清空
choice /c YN /m 确认继续？
if errorlevel 2 (
    echo 已取消，数据库未改动。
    pause & exit /b 1
)

set "MYSQL_PWD=!DBPWD!"
"C:\mysql-8.0.44-winx64\bin\mysql.exe" -u root --default-character-set=utf8mb4 < sql\init.sql
if errorlevel 1 (
    echo 导入失败！请先确认已以管理员身份运行 reset-mysql-password.bat
    pause
    exit /b 1
)
echo.
echo 数据库 ctrip_travel 初始化完成！
endlocal
pause
