param([int]$DurationSeconds = 30, [int]$IntervalMs = 200)

# 与 JMeter 同机运行时，客户端和服务端 TCP 状态分别按本地/远端 8081 端口计数。
$end = (Get-Date).AddSeconds($DurationSeconds)
$jmeterPid = $null
$previousCpu = $null
$previousTime = $null
$machineCounter = [System.Diagnostics.PerformanceCounter]::new('Processor', '% Processor Time', '_Total')
[void]$machineCounter.NextValue()
"time_ms,machine_cpu_pct,jmeter_pid,jmeter_cpu_pct,jmeter_working_set_bytes,client_established,client_time_wait,client_syn_sent,server_established,server_time_wait,server_syn_received"
while ((Get-Date) -lt $end) {
    if (-not $jmeterPid) {
        $jmeterPid = (Get-CimInstance Win32_Process |
            Where-Object { $_.Name -match '^javaw?\.exe$' -and $_.CommandLine -match '(?i)jmeter' } |
            Select-Object -First 1 -ExpandProperty ProcessId)
    }
    $now = Get-Date
    $cpu = -1
    $memory = -1
    if ($jmeterPid) {
        $process = Get-Process -Id $jmeterPid -ErrorAction SilentlyContinue
        if ($process) {
            $memory = $process.WorkingSet64
            if ($null -ne $previousCpu) {
                $elapsed = ($now - $previousTime).TotalSeconds
                if ($elapsed -gt 0) { $cpu = [math]::Round(100 * ($process.CPU - $previousCpu) / $elapsed, 1) }
            }
            $previousCpu = $process.CPU
            $previousTime = $now
        } else { $jmeterPid = $null; $previousCpu = $null }
    }
    $lines = netstat -ano -p tcp
    $client = @{}; $server = @{}
    foreach ($line in $lines) {
        if ($line -notmatch '^\s*TCP\s+(\S+)\s+(\S+)\s+(\S+)') { continue }
        $local = $Matches[1]; $remote = $Matches[2]; $state = $Matches[3]
        if ($remote -match ':8081$') { $client[$state] = 1 + [int]$client[$state] }
        if ($local -match ':8081$') { $server[$state] = 1 + [int]$server[$state] }
    }
    $stamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $machineCpu = [math]::Round($machineCounter.NextValue(), 1)
    "$stamp,$machineCpu,$jmeterPid,$cpu,$memory,$([int]$client['ESTABLISHED']),$([int]$client['TIME_WAIT']),$([int]$client['SYN_SENT']),$([int]$server['ESTABLISHED']),$([int]$server['TIME_WAIT']),$([int]$server['SYN_RECEIVED'])"
    Start-Sleep -Milliseconds $IntervalMs
}
$machineCounter.Dispose()
