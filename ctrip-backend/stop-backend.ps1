# 以管理员运行：停止 ctrip 后端相关进程（mvn 启动器 + Spring Boot 应用）
$ErrorActionPreference = 'SilentlyContinue'
$log = 'c:\Users\王\Documents\HBuilderProjects\ctrip-backend\stop-backend-result.txt'
$lines = @()
$procs = Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='cmd.exe'" |
    Where-Object { $_.CommandLine -match 'ctrip-backend|D:\\Maven' }
foreach ($p in $procs) {
    Stop-Process -Id $p.ProcessId -Force
    $lines += "killed PID $($p.ProcessId) ($($p.Name))"
}
if (-not $procs) { $lines += 'no matching processes (already stopped)' }
$lines | Out-File $log -Encoding utf8
