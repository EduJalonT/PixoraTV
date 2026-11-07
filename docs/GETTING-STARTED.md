# Primeros pasos

## 1. Requisitos

- Chromecast con Google TV y un PC con Windows en la misma red.
- `adb` (Android platform-tools). El paquete de la release lo incluye en `platform-tools\`.

## 2. Activar la depuración por ADB en el Chromecast

1. Ajustes > Sistema > Información > pulsa 7 veces **Compilación del SO Android TV** hasta que avise
   de que eres desarrollador.
2. Ajustes > Sistema > **Opciones para desarrolladores** > activa **Depuración USB**.
3. Anota la IP del Chromecast: Ajustes > Red e Internet > tu red.

## 3. Conectar

```powershell
.\platform-tools\adb.exe connect <IP-del-Chromecast>:5555
```

Acepta en la TV el aviso «¿Permitir la depuración?» (marca «Permitir siempre desde este ordenador»).
Comprueba la conexión con `.\platform-tools\adb.exe devices`: debe aparecer como `device`.

## 4. Instalar

```powershell
powershell -ExecutionPolicy Bypass -File .\instalar.ps1
```

El script:

1. instala el APK;
2. activa su servicio de accesibilidad (doble pulsación de Inicio y protección de la pantalla de
   inicio), añadiéndolo a los que ya hubiera;
3. lo asigna como pantalla de inicio (`cmd role add-role-holder` y `cmd package set-home-activity`);
4. pulsa Inicio y comprueba qué queda en pantalla.

Guarda el estado previo en `estado-anterior.txt`. Después reinicia (`adb reboot`) y comprueba que
el Chromecast arranca en el launcher.

`-DesactivarGoogleTV` desactiva además Google TV Home (sin desinstalarlo). Es un último recurso: con
él se pierde su panel de ajustes rápidos.

## 5. Comprobaciones recomendadas

| Prueba | Resultado esperado |
|---|---|
| Inicio | Abre el launcher |
| Reinicio | Arranca en el launcher, sin pasar por Google TV |
| Inicio dos veces | Abre el panel de ajustes rápidos |
| Mantener Inicio | Abre el panel y no se cierra solo |
| Botón lápiz | Abre las opciones del launcher |

Si algo falla, guarda el registro y adjúntalo al informe:

```powershell
.\platform-tools\adb.exe logcat -d > log.txt
```

## 6. Scripts de medición

Los scripts `scripts/pixora-*.sh` miden el launcher por ADB (latencia de teclas, arranque en frío,
CPU en reposo, número de vistas). Se ejecutan desde Git Bash; cada uno explica en su cabecera qué
mide, cómo se usa y si cambia algo en el dispositivo. Por ejemplo:

```bash
PIXORA_TV_HOST=<IP-del-Chromecast> bash scripts/pixora-key-latency.sh prueba
```
