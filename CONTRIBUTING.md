# Contribuir a Pixora TV

## El launcher

Vive en `launcher/` y es deliberadamente pequeño: Java puro, AndroidX RecyclerView y
DynamicAnimation, sin Kotlin ni Compose. El objetivo es un Chromecast con Google TV (2 GB de RAM),
así que:

- **Nada de blur en tiempo real, fondos de vídeo, sondeos periódicos ni servicios en segundo plano
  que no hagan falta.**
- Lo que dependa del dispositivo va en `app/src/main/res/values/config.xml`, no en el código.
- La navegación con el D-pad debe ser determinista: prueba arriba, abajo, izquierda y derecha desde
  **todos los bordes**.
- Todo texto visible va en `res/values*/strings.xml` (ver [docs/LOCALIZATION.md](docs/LOCALIZATION.md)).
- Valídalo en un Chromecast real, no solo en un emulador. Lo más sencillo es inyectar teclas y
  capturar la pantalla:
  ```bash
  adb shell input keyevent KEYCODE_DPAD_DOWN
  adb exec-out screencap -p > captura.png
  ```

**Compilar:**
```powershell
cd launcher
.\gradlew.bat testDebugUnitTest assembleRelease
```

**Encaja:** mejoras pequeñas y opcionales, accesibilidad con el mando, traducciones.
**No encaja:** filas de recomendaciones, anuncios, telemetría, animaciones continuas.

## Scripts y herramientas

- `scripts/pixora-common.ps1` / `scripts/pixora-lib.sh`: funciones compartidas (IP, ADB, registros).
- `scripts/pixora-bench.sh`, `scripts/pixora-key-latency.sh`, `scripts/pixora-launcher-bench.sh`: medición
  repetible del launcher.
- `scripts/pixora-backup.ps1` / `scripts/pixora-restore.ps1`: instantánea y restauración del estado.
- `tools/disable*` / `tools/restore*`: debloat reversible (`pm disable-user`, nunca desinstalar).

> **Nunca propongas desinstalar paquetes del sistema (`pm uninstall`).** Todo se hace con
> `pm disable-user --user 0`, que se puede revertir.

## Pull requests

- Un tema por PR; mejor pequeño.
- Indica en qué dispositivo lo probaste (modelo y versión de Google TV).
- Ninguna dependencia nueva en el launcher sin hablarlo antes en una issue.

Lista rápida antes de abrir un PR:

- [ ] Probado en un dispositivo real
- [ ] `.\gradlew.bat testDebugUnitTest lintRelease` sin errores
- [ ] Sin regresión de latencia (`scripts/pixora-key-latency.sh`, 3 series o más)
- [ ] Capturas antes/después si cambia la interfaz
- [ ] `tools/secret-scan.ps1` sin avisos (ni claves ni contraseñas)
- [ ] Documentación actualizada si cambia algo visible

Al contribuir aceptas que tu trabajo se publica con la licencia MIT del repositorio.
