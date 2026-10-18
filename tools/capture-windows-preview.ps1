param([int]$WindowProcessId,[string]$OutputPath)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Drawing
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class PreviewCapture {
 [StructLayout(LayoutKind.Sequential)] public struct Rect { public int Left,Top,Right,Bottom; }
 [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hwnd,out Rect rect);
 [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr hwnd,IntPtr hdc,uint flags);
}
'@
$p=Get-Process -Id $WindowProcessId
$r=New-Object PreviewCapture+Rect
if (![PreviewCapture]::GetWindowRect($p.MainWindowHandle,[ref]$r)) { throw 'Window bounds unavailable' }
$bitmap=New-Object System.Drawing.Bitmap(($r.Right-$r.Left),($r.Bottom-$r.Top))
$g=[System.Drawing.Graphics]::FromImage($bitmap)
$hdc=$g.GetHdc()
try { if (![PreviewCapture]::PrintWindow($p.MainWindowHandle,$hdc,2)) { throw 'Window capture unavailable' } }
finally { $g.ReleaseHdc($hdc);$g.Dispose() }
try { $bitmap.Save($OutputPath,[System.Drawing.Imaging.ImageFormat]::Png) }
finally { $bitmap.Dispose() }
