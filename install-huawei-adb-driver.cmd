@echo off
set INF=C:\aether-build\huawei-adb-driver\android_winusb.inf
set DEV=USB\VID_12D1^&PID_107D^&MI_02\7^&2D87D971^&1^&0002
echo Installing Android ADB driver for Huawei...
pnputil /add-driver "%INF%" /install
echo Updating device...
pnputil /update-driver "%INF%" "%DEV%"
echo Restarting ADB interface...
pnputil /restart-device "%DEV%"
echo Done.
pause
