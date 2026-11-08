[CmdletBinding()]
param(
    [ValidateSet('Smoke', 'All', 'ZooKeeper', 'ScaleOut', 'Jini', 'RDF', 'HA', 'HAProbe')]
    [string]$TestSet = 'Smoke',
    [string]$JavaHome = 'C:\Users\Axiom\.jdks\axiomjdk-1.8.0_482',
    [string]$Maven = 'C:\Program Files\apache-maven-3.9.14\bin\mvn.cmd',
    [string]$EvidenceDirectory,
    [string]$HATest,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (!$EvidenceDirectory) {
    $EvidenceDirectory = Join-Path (Split-Path $repo) (
        'blazegraph-artifacts/cluster/' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
}
$evidence = [IO.Path]::GetFullPath($EvidenceDirectory)
if ($evidence -match '(^|[\\/])target([\\/]|$)') {
    throw 'EvidenceDirectory must be outside target directories'
}
if ((Test-Path -LiteralPath $evidence) -and
        (Get-ChildItem -LiteralPath $evidence -Force | Select-Object -First 1)) {
    throw 'EvidenceDirectory must be empty; use a fresh directory for each run'
}
[IO.Directory]::CreateDirectory($evidence) | Out-Null
$oldJavaHome = $env:JAVA_HOME
$oldPath = $env:PATH
$common = @('-B', '-ntp', '-PDevelopment,cluster-integration')
$river = $null
$runLock = $null
$ownsLock = $false
$phaseResults = [Collections.Generic.List[object]]::new()

function Invoke-Maven([string]$Name, [string[]]$Arguments) {
    $started = Get-Date
    & $Maven @common @Arguments *> (Join-Path $evidence "$Name.log")
    $code = $LASTEXITCODE
    foreach ($module in @('bigdata-zookeeper-test', 'bigdata-jini-test', 'bigdata-ha-test')) {
        $reports = Join-Path $repo "$module/target/surefire-reports"
        if (Test-Path $reports) {
            $dest = Join-Path $evidence "$Name/$module"
            [IO.Directory]::CreateDirectory($dest) | Out-Null
            Get-ChildItem $reports -File | Where-Object LastWriteTime -ge $started |
                Copy-Item -Destination $dest
        }
    }
    if ($code -ne 0) { throw "Maven $Name failed ($code). See $evidence\$Name.log" }
}

function Test-Port([int]$Port) {
    $client = [Net.Sockets.TcpClient]::new()
    try { $client.Connect('127.0.0.1', $Port); return $true }
    catch { return $false }
    finally { $client.Dispose() }
}

function Invoke-ClusterMaven([string]$Name, [string[]]$Arguments) {
    $message = $null
    try { Invoke-Maven $Name $Arguments }
    catch { $message = $_.Exception.Message }
    $testCount = $failureCount = $errorCount = $skipCount = $retryCount = 0
    $javaHomes = @()
    $reports = Join-Path $evidence $Name
    if (Test-Path $reports) {
        foreach ($report in Get-ChildItem $reports -Recurse -File -Filter 'TEST-*.xml') {
            [xml]$xml = Get-Content -LiteralPath $report.FullName -Raw
            $testCount += [int]$xml.testsuite.tests
            $failureCount += [int]$xml.testsuite.failures
            $errorCount += [int]$xml.testsuite.errors
            $skipCount += [int]$xml.testsuite.skipped
            $retryCount += $xml.SelectNodes('//flakyFailure|//flakyError|//rerunFailure|//rerunError').Count
            $javaHomes += ($xml.testsuite.properties.property | Where-Object name -eq 'java.home').value
        }
    }
    if ($null -eq $message -and $testCount -eq 0) { $message = 'No test reports were produced' }
    if ($null -eq $message -and ($failureCount -gt 0 -or $errorCount -gt 0 -or
            $skipCount -gt 0 -or $retryCount -gt 0)) {
        $message = 'Test reports contain failures, errors, skips or retry events'
    }
    $phaseResults.Add([pscustomobject]@{
        phase = $Name; passed = ($null -eq $message); error = $message
        tests = $testCount; failures = $failureCount; errors = $errorCount
        skipped = $skipCount; retryEvents = $retryCount
        javaHomes = @($javaHomes | Sort-Object -Unique)
    })
    $phaseResults | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $evidence 'phases.json') -Encoding utf8
}

Push-Location $repo
try {
    $runLock = [Threading.Mutex]::new($false, 'Local\BlazegraphE0ClusterTests')
    try { $ownsLock = $runLock.WaitOne(0) }
    catch [Threading.AbandonedMutexException] { $ownsLock = $true }
    if (!$ownsLock) { throw 'Another cluster runner owns the local test environment' }
    $env:JAVA_HOME = $JavaHome
    $env:PATH = "$JavaHome\bin;$env:PATH"
    & "$JavaHome/bin/java.exe" -version *> (Join-Path $evidence 'java-version.txt')
    if ($LASTEXITCODE -ne 0) { throw 'Cannot run the selected JDK' }
    foreach ($port in @(4160, 23333)) {
        if (Test-Port $port) { throw "River port $port is occupied; no existing process will be stopped" }
    }
    if (!$SkipBuild) {
        Invoke-Maven 'build' @('-pl', 'bigdata-ha-test,bigdata-zookeeper-test', '-am', '-DskipTests', 'install')
    }
    $cpFile = Join-Path $evidence 'classpath.txt'
    Invoke-Maven 'classpath' @('-pl', 'bigdata-ha-test',
        'org.apache.maven.plugins:maven-dependency-plugin:2.10:build-classpath',
        '-Dmdep.includeScope=test', "-Dmdep.outputFile=$cpFile")
    $cp = (Get-Content $cpFile -Raw).Trim()
    $codebase = [IO.Directory]::CreateDirectory((Join-Path $evidence 'codebase')).FullName
    foreach ($jar in $cp.Split([IO.Path]::PathSeparator)) {
        if ((Split-Path $jar -Leaf) -match '^(reggie-dl|jsk-dl)-') {
            Copy-Item -LiteralPath $jar -Destination $codebase
        }
    }
    & docker compose -p blazegraph-e0 -f "$PSScriptRoot/compose.yaml" up -d --wait *> (Join-Path $evidence 'zookeeper-up.log')
    if ($LASTEXITCODE -ne 0) { throw 'ZooKeeper ensemble did not start' }

    # CLASSPATH avoids the Windows command-line length limit. This environment
    # is inherited only by our tracked River process and restored immediately.
    $oldClasspath = $env:CLASSPATH
    try {
        $env:CLASSPATH = $cp
        $riverArgs = @(
            '-Djava.net.preferIPv4Stack=true',
            "-Djava.security.policy=$PSScriptRoot/policy.all",
            "-Dcluster.lookupConfig=$PSScriptRoot/river-lookup.config",
            "-Dcluster.codebaseDir=$codebase",
            "-Dcluster.federationGroup=bigdata.test.group-$([Net.Dns]::GetHostName())",
            'com.sun.jini.start.ServiceStarter', "$PSScriptRoot/river-start.config"
        ) | ForEach-Object { '"' + $_ + '"' }
        $river = Start-Process -FilePath "$JavaHome/bin/java.exe" -ArgumentList $riverArgs -PassThru -WindowStyle Hidden `
            -RedirectStandardOutput (Join-Path $evidence 'river.out.log') `
            -RedirectStandardError (Join-Path $evidence 'river.err.log')
    } finally { $env:CLASSPATH = $oldClasspath }
    $deadline = (Get-Date).AddSeconds(45)
    while (!(Test-Port 4160) -or !(Test-Port 23333)) {
        if ($river.HasExited -or (Get-Date) -gt $deadline) { throw 'River bootstrap failed; see river logs' }
        Start-Sleep -Milliseconds 200
    }
    $cluster = @('-DfailIfNoTests=true',
        '-Dtest.jini.groups=',
        '-Dtest.zookeeper.host=127.0.0.1', '-Dtest.zookeeper.clientPort=12181',
        '-Dtest.zookeeper.servers=127.0.0.1:12181,127.0.0.1:12182,127.0.0.1:12183',
        "-Dtest.zookeeper.composeFile=$PSScriptRoot/compose.yaml", 'verify')
    if ($TestSet -in @('Smoke', 'All', 'ZooKeeper')) {
        Invoke-ClusterMaven 'zookeeper' (@('-pl', 'bigdata-zookeeper-test',
            '-Dtest=com.bigdata.zookeeper.TestAll,com.bigdata.quorum.zk.TestAll') + $cluster)
    }
    if ($TestSet -in @('Smoke', 'All', 'ScaleOut', 'Jini')) {
        Invoke-ClusterMaven 'scaleout' (@('-pl', 'bigdata-jini-test',
            '-Dtest=com.bigdata.jini.TestAll,com.bigdata.jini.start.config.TestServiceConfiguration') + $cluster)
    }
    if ($TestSet -in @('All', 'ScaleOut', 'RDF')) {
        Invoke-ClusterMaven 'scaleout-rdf' (@('-pl', 'bigdata-jini-test',
            '-Dtest=com.bigdata.rdf.store.TestScaleOutTripleStoreWithJiniFederation') + $cluster)
    }
    if ($TestSet -in @('Smoke', 'All', 'HA', 'HAProbe')) {
        $haSelection = if ($HATest) { $HATest } elseif ($TestSet -in @('Smoke', 'HAProbe')) {
            'com.bigdata.journal.jini.ha.TestHA3JournalServer#testStartABC_restartC'
        } else { 'com.bigdata.journal.jini.ha.TestAll' }
        Invoke-ClusterMaven 'ha' (@('-pl', 'bigdata-ha-test', "-Dtest=$haSelection") + $cluster)
    }
    if ($phaseResults.Where({!$_.passed}).Count -gt 0) {
        throw "Cluster test failures. See $evidence\phases.json"
    }
    Write-Output "Cluster tests passed. Evidence: $evidence"
} finally {
    try {
        if ($null -ne $river -and !$river.HasExited) {
            $river.Kill()
            $river.WaitForExit()
        }
        if ($ownsLock) {
            & docker compose -p blazegraph-e0 -f "$PSScriptRoot/compose.yaml" logs --no-color *> (Join-Path $evidence 'ensemble.log')
        }
    } finally {
        if ($ownsLock) { $runLock.ReleaseMutex() }
        if ($null -ne $runLock) { $runLock.Dispose() }
        $env:JAVA_HOME = $oldJavaHome
        $env:PATH = $oldPath
        Pop-Location
    }
}
