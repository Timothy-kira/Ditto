# Installs the Android ADB WinUSB driver and binds it to a connected phone's
# ADB interface.
#
# The .inf path and the target device are both resolved at runtime: the .inf
# comes from this script's own directory, and the device is matched by USB
# protocol rather than by a fixed instance id. Nothing here is tied to a
# particular phone, vendor, machine, or USB port.
#
# Run from an elevated prompt - pnputil needs administrator rights.

$ErrorActionPreference = "Stop"

$inf = Join-Path $PSScriptRoot "android_winusb.inf"
if (-not (Test-Path $inf)) {
    throw "android_winusb.inf not found next to this script: $inf"
}

# Google defines the ADB endpoint as a USB interface with class FF, subclass 42,
# protocol 01. Every Android device exposes it under that triple, so matching on
# it is vendor- and model-independent - unlike VID/PID/MI, which differ per
# device and shift when the phone changes USB mode (charge-only / MTP / PTP).
#
# The triple lives in the device's CompatibleIDs, not its InstanceId: a bound
# device enumerates as "USB\VID_xxxx&PID_xxxx&MI_02\<port-specific>", and only
# the compatible-id list carries "USB\Class_ff&SubClass_42&Prot_01". Matching
# InstanceId against the triple silently finds nothing.
$AdbCompatibleId = 'Class_ff&SubClass_42&Prot_01'

function Get-AdbInterface {
    Get-PnpDevice -PresentOnly -ErrorAction SilentlyContinue | Where-Object {
        $ids = (Get-PnpDeviceProperty -InstanceId $_.InstanceId `
                    -KeyName DEVPKEY_Device_CompatibleIds `
                    -ErrorAction SilentlyContinue).Data
        $ids -and (($ids -join ';') -match $AdbCompatibleId)
    }
}

Write-Host "Installing driver package..."
pnputil /add-driver $inf /install

# /install binds the package to whatever Windows can match on its own. The pass
# below is for interfaces already sitting on a wrong or missing driver, which
# Windows will not re-match unless told to.
$targets = @(Get-AdbInterface)

if ($targets.Count -eq 0) {
    Write-Warning "No ADB interface found. Check that the phone is connected, USB debugging is on, and it is not in charge-only mode."
    Write-Warning "The driver package is installed regardless, so Windows may bind it on its own once the phone re-enumerates."
    exit 0
}

foreach ($dev in $targets) {
    Write-Host "Binding driver to $($dev.FriendlyName) [$($dev.InstanceId)]"
    pnputil /update-driver $inf $dev.InstanceId
    pnputil /restart-device $dev.InstanceId
}

Write-Host "Done."
