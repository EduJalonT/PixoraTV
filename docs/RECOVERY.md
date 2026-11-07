# Recuperación

Google TV Home (`com.google.android.apps.tv.launcherx`) nunca se desinstala: es la vía de vuelta.

| Situación | Qué hacer |
|---|---|
| Google TV vuelve a abrirse al pulsar Inicio | Acepta el aviso «Google TV ha recuperado la pantalla de inicio» > Restablecer, o ejecuta `reparar-inicio.ps1` |
| El servicio de accesibilidad se desactivó | Acepta el aviso del launcher > Abrir Accesibilidad, o ejecuta `reparar-inicio.ps1` |
| Quiero volver a Google TV | `revertir.ps1` (el launcher queda como app) o `revertir.ps1 -Desinstalar` |
| Sin ordenador | Botón del lápiz > «Reactivar la pantalla de inicio original» (al final del panel) abre la ficha de Google TV Home en Ajustes |
| El launcher falla o la pantalla queda negra | Comandos manuales de abajo |

## Comandos manuales

```text
adb shell pm enable --user 0 com.google.android.apps.tv.launcherx
adb shell cmd role add-role-holder --user 0 android.app.role.HOME com.google.android.apps.tv.launcherx
adb shell settings put secure enabled_accessibility_services <la lista sin app.pixora.tv/com.edujalont.tvhome.QuickPanelService>
adb uninstall app.pixora.tv
adb reboot
```

`adb uninstall` es opcional: sin él, el launcher queda instalado como una app más.

## Debloat

`tools/disable.sh` / `tools/disable.ps1` y `tools/restore.sh` / `tools/restore.ps1` desactivan y
reactivan paquetes de una lista (`pm disable-user`, nunca desinstalan). `tools/disable` guarda antes
el estado del dispositivo para que `restore` deshaga exactamente lo hecho. No desactives Google TV
Home con ellos: sin él se pierde el panel de ajustes rápidos y la vía de recuperación.
