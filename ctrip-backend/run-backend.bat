@echo off
chcp 65001 >nul
REM =================================================================
REM  一键启动后端服务（Spring Boot + MySQL）
REM  前提: 1.已运行 reset-mysql-password.bat  2.已运行 init-db.bat
REM  启动后访问 http://localhost:8080/api/hotels?city=北京 验证
REM =================================================================
set "JAVA_HOME=C:\Program Files\Java\jdk-21"
set "MVN=D:\Maven\apache-maven-3.9.9\bin\mvn.cmd"
set "REPO=D:\Maven\m2repo"
cd /d %~dp0

echo 正在启动悦途预订平台后端 (端口 8080)...
echo 首次启动需要编译，请稍候；停止请按 Ctrl+C
call "%MVN%" -B -q "-Dmaven.repo.local=%REPO%" spring-boot:run
pause
