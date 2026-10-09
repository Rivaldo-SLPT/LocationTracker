# Mueve el GPS del emulador por una ruta en Cusco, en bucle.
# Uso: powershell -ExecutionPolicy Bypass -File simular_ruta.ps1
# Ctrl+C para detenerlo.

param(
    [string]$Emulator = "emulator-5556",
    [int]$SecondsPerPoint = 20
)

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

# Plaza de Armas -> San Blas -> Sacsayhuamán -> San Pedro -> Plaza de Armas
# (longitud, latitud: es el orden que espera "geo fix")
$route = @(
    @(-71.97858, -13.51664),
    @(-71.97648, -13.51603),
    @(-71.97505, -13.51540),
    @(-71.97470, -13.51395),
    @(-71.97690, -13.51150),
    @(-71.98210, -13.50890),
    @(-71.98400, -13.51280),
    @(-71.98470, -13.51690),
    @(-71.98330, -13.51930),
    @(-71.98080, -13.51830)
)

# Puntos intermedios para que el movimiento sea continuo.
$stepsBetween = 4

while ($true) {

    for ($i = 0; $i -lt $route.Count; $i++) {

        $from = $route[$i]
        $to = $route[($i + 1) % $route.Count]

        for ($k = 0; $k -lt $stepsBetween; $k++) {

            $t = $k / $stepsBetween
            $lon = $from[0] + ($to[0] - $from[0]) * $t
            $lat = $from[1] + ($to[1] - $from[1]) * $t

            $lonText = $lon.ToString([Globalization.CultureInfo]::InvariantCulture)
            $latText = $lat.ToString([Globalization.CultureInfo]::InvariantCulture)

            & $adb -s $Emulator emu geo fix $lonText $latText | Out-Null
            Write-Host "$(Get-Date -Format HH:mm:ss)  lat=$latText  lon=$lonText"

            Start-Sleep -Seconds $SecondsPerPoint
        }
    }
}
