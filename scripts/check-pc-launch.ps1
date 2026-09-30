# A build that starts and immediately dies is the failure mode nobody catches:
# the file exists, the hash matches, and the user double-clicks nothing.
#
# This launches the built client and waits for an actual top-level window owned
# by its process. Not a process - a process is what the jpackage launcher leaves
# behind when it dies before the JVM, which looks exactly like a healthy start
# from the outside.
#
# Usage:  powershell -File scripts\check-pc-launch.ps1 [-TimeoutSec 90]
# Exit:   0 window appeared, 1 it did not, 2 there is no build to check.

[CmdletBinding()]
param([int]$TimeoutSec = 90)

$ErrorActionPreference = 'Continue'

$image = Join-Path $PSScriptRoot '..\OpenFluxPC\build\compose\binaries\main\app\OpenFlux\OpenFlux.exe'
if (-not (Test-Path $image)) {
    Write-Error "no build to check: $image`nbuild it first: gradlew.bat :OpenFluxPC:createDistributable"
    exit 2
}

# EnumWindows over every top-level window, per process. Get-Process's
# MainWindowHandle reports the same window for several processes and misses
# windows that are not yet visible, so it cannot answer "did the app open".
$api = @'
using System; using System.Text; using System.Runtime.InteropServices; using System.Collections.Generic;
public static class OpenFluxWindows {
  [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc cb, IntPtr p);
  [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr h, out RECT r);
  public struct RECT { public int L, T, R, B; }
  delegate bool EnumProc(IntPtr h, IntPtr p);
  public static List<string> ForProcess(uint target) {
    var found = new List<string>();
    EnumWindows((h, p) => {
      uint pid; GetWindowThreadProcessId(h, out pid);
      if (pid != target) return true;
      var title = new StringBuilder(256); GetWindowText(h, title, 256);
      RECT r; GetWindowRect(h, out r);
      int w = r.R - r.L, ht = r.B - r.T;
      // Skip the zero-sized helper windows AWT and the IME leave lying around.
      if (w > 200 && ht > 200)
        found.Add(string.Format("\"{0}\" {1}x{2} visible={3}", title, w, ht, IsWindowVisible(h)));
      return true;
    }, IntPtr.Zero);
    return found;
  }
}
'@
Add-Type -TypeDefinition $api -Language CSharp

Write-Host "launching $image"
$proc = Start-Process -FilePath $image -PassThru
$watch = [Diagnostics.Stopwatch]::StartNew()

try {
    while ($watch.Elapsed.TotalSeconds -lt $TimeoutSec) {
        Start-Sleep -Seconds 3
        $proc.Refresh()
        if ($proc.HasExited) {
            Write-Host "FAIL: exited after $([int]$watch.Elapsed.TotalSeconds)s with code $($proc.ExitCode)"
            exit 1
        }
        $wins = [OpenFluxWindows]::ForProcess([uint32]$proc.Id)
        if ($wins.Count -gt 0) {
            $wins | ForEach-Object { Write-Host "  window: $_" }
            Write-Host ("PASS: window appeared after {0:N0}s, process at {1:N0} MB" -f `
                $watch.Elapsed.TotalSeconds, ($proc.WorkingSet64 / 1MB))
            exit 0
        }
    }

    # Timed out. Say what the process looks like, because "no window" on its own
    # sends people looking in the wrong place: a launcher that died before the
    # JVM and an app that started and never composed look identical from outside.
    $proc.Refresh()
    $mb = [math]::Round($proc.WorkingSet64 / 1MB)
    $mods = @($proc.Modules | ForEach-Object { $_.ModuleName })
    Write-Host "FAIL: no window after ${TimeoutSec}s"
    Write-Host "  process alive, $mb MB, $($mods.Count) modules"
    if ($mods -contains 'jvm.dll') {
        Write-Host "  the JVM loaded, so the launcher worked and the app did not finish starting"
    } else {
        Write-Host "  jvm.dll never loaded: the jpackage launcher itself did not start"
        Write-Host "  (antivirus and endpoint security are known to block it; try a signed build)"
    }
    exit 1
}
finally {
    if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
}
