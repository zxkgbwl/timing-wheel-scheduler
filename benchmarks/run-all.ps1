# Runs the load test and the memory probe for each candidate, ONE JVM PER CANDIDATE, and collects
# the CSV lines into results\load.csv and results\memory.csv.
#
#   .\run-all.ps1
#   .\run-all.ps1 -Rate 500000 -Seconds 30 -Impls wheel,netty -Heap 8g
#
# Add "redis" to -Impls only with a Redis running (docker run --rm -p 6379:6379 redis:7).
param(
    # candidates
    [string[]]$Impls = @('noop', 'wheel', 'wheel-pool', 'wheel-disruptor', 'delayqueue', 'stpe', 'netty'),
    #200000 per second
    [int]$Rate = 200000,
    #last 20 seconds
    [int]$Seconds = 20,
    #cpu
    [int]$Producers = 4,
    #total tasks
    [int]$Tasks = 1000000,
    #how many percent of tasks will be cancelled
    [double]$CancelFraction = 0.9,
    #memory
    [string]$Heap = '4g',
    #GC solution G1 for everyone
    [string]$JvmFlags = '-XX:+UseG1GC',      # keep identical for every candidate
    # dependenacy
    [string]$Jar = (Join-Path $PSScriptRoot 'target\benchmarks.jar')
)
#test will be stoped when any error happens
$ErrorActionPreference = 'Stop'
#form the abstract path of results, Join-Path win: "\", linux/max: "/"
$out = Join-Path $PSScriptRoot 'results'
#force crate the direcotry of $out and not print on the console
New-Item -ItemType Directory -Force $out | Out-Null
#remove any temporary file from last test, ccontinue if the file not exist
Remove-Item (Join-Path $out 'load-*.txt'), (Join-Path $out 'memory-*.txt') -ErrorAction SilentlyContinue
#iterate every impl
foreach ($impl in $Impls) {

    Write-Host "=== load   : $impl"
    java "-Xms$Heap" "-Xmx$Heap" $JvmFlags -cp $Jar io.timewheel.bench.LoadTest `
        --mode=load --impl=$impl --rate=$Rate --seconds=$Seconds --producers=$Producers |
        Tee-Object -FilePath (Join-Path $out "load-$impl.txt")
    if ($impl -ne 'noop') {
        Write-Host "=== memory : $impl"
        java "-Xms$Heap" "-Xmx$Heap" $JvmFlags -cp $Jar io.timewheel.bench.LoadTest `
            --mode=memory --impl=$impl --tasks=$Tasks --cancelFraction=$CancelFraction |
            Tee-Object -FilePath (Join-Path $out "memory-$impl.txt")
    }
}

function Collect([string]$prefix, [string]$target) {
    $files = Get-ChildItem (Join-Path $out "$prefix-*.txt")
    $header = (Select-String -Path $files -Pattern '^CSV_HEADER,' | Select-Object -First 1).Line -replace '^CSV_HEADER,', ''
    $rows = Select-String -Path $files -Pattern '^CSV,' | ForEach-Object { $_.Line -replace '^CSV,', '' }
    @($header) + $rows | Set-Content $target
}
Collect 'load' (Join-Path $out 'load.csv')
Collect 'memory' (Join-Path $out 'memory.csv')
Write-Host "`nwrote results\load.csv and results\memory.csv"
