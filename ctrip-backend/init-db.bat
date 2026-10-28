@echo off
chcp 65001 >nul
REM =================================================================
REM  初始化数据库：建库建表 + 导入真实数据（车次/航班/酒店/景点）
REM  前提: 已运行 reset-mysql-password.bat（root 密码 = Ctrip@123456）
REM  注意: 重复执行会重建所有表（订单数据将被清空）
REM =================================================================
cd /d %~dp0
"C:\mysql-8.0.44-winx64\bin\mysql.exe" -u root -pCtrip@123456 --default-character-set=utf8mb4 < sql\init.sql
if errorlevel 1 (
    echo 导入失败！请先确认已以管理员身份运行 reset-mysql-password.bat
    pause
    exit /b 1
)
echo.
echo 数据库 ctrip_travel 初始化完成！
pause
