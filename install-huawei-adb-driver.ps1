$inf = "C:\aether-build\huawei-adb-driver\android_winusb.inf"
$dev = "USB\VID_12D1&PID_107D&MI_02\7&2D87D971&1&0002"
Write-Host "Installing Android ADB driver..."
pnputil /add-driver $inf /install
Write-Host "Binding driver to Huawei ADB interface..."
pnputil /update-driver $inf $dev
Write-Host "Restarting device..."
pnputil /restart-device $dev
Write-Host "Exit code: $LASTEXITCODE"
