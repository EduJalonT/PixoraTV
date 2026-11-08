# Pixora TV

**Una nueva experiencia para Android / Google TV.**

Launcher ligero para **Chromecast con Google TV** y **Android TV** (Android 9 o superior), basado en
[CleanYourTV / tv-vibehack](https://github.com/Gathin23/cleanyourtv) (licencia MIT).

En Google TV sustituye a Google TV Home como pantalla de inicio sin desactivarlo: Google TV Home sigue
instalado como mecanismo de recuperación y conserva su panel de ajustes rápidos. En Android TV es una
app de inicio normal.

> Versión **2026.11.07.015**. Probada en Chromecast con Google TV (Android 14) y en un televisor con
> Android TV 9, incluidas la actualización desde la propia TV y la Ligue 1; que el botón de Bluetooth abra
> «Mandos y accesorios» desde la app aún no se ha probado. Consulta [docs/KNOWN-ISSUES.md](docs/KNOWN-ISSUES.md) antes de instalarlo.
>
> Las versiones siguen la fecha: `AAAA.MM.DD.NNN` (versionCode `AAMMDDNNN`).

## Capturas

![Pantalla de inicio: cabecera, barra de iconos, noticias y dock](docs/screenshots/inicio.png)

![Cabecera: dispositivo · hora · fecha · ciudad – clima](docs/screenshots/cabecera.png)

![Marcadores: LigaPro, Copa Libertadores y Eliminatorias con logos, escudos y banderas](docs/screenshots/marcadores.png)

| Panel de opciones | Menú de entradas (Android TV) |
|---|---|
| ![Panel de opciones: Perfil, Apariencia, Clima, Noticias, Marcadores y Tarjetas de entrada](docs/screenshots/panel.png) | ![Menú de entradas con HDMI, AV y TV en vivo](docs/screenshots/entradas.png) |

![Ajustes de noticias con un feed RSS](docs/screenshots/noticias.png)

## Qué hace

| Función | Cómo se usa |
|---|---|
| Opciones del launcher | Botón con icono de lápiz en la barra superior (el mando del Chromecast no tiene tecla Menu): panel lateral al estilo de los Ajustes de Google TV, con secciones (Perfil, Apariencia, Clima, Noticias, Apps y dock, Actualizaciones, Recuperación), interruptores y valores que se cambian con Izquierda/Derecha y se ven al momento. Atrás vuelve a las secciones y, desde ahí, cierra |
| Ajustes rápidos del sistema | Solo Google TV: pulsar **Inicio dos veces** (menos de 0,5 s) abre el panel nativo, también desde dentro de otra app |
| Pantalla de inicio | Google TV: el servicio de accesibilidad atiende la tecla Inicio y abre Pixora TV sin pasar por Google TV, y la sustituye también al arrancar, tras una actualización, al despertar la TV y al salir de los resultados del asistente. Android TV: app de inicio predeterminada, sin servicio |
| Asistente de voz | Botón de micrófono; los resultados de búsqueda de Google TV se quedan en pantalla |
| Cabecera | Iconos de Font Awesome: nombre real del dispositivo · hora · fecha · ciudad – clima con su icono |
| Saludo | «Buenos días / Buenas tardes / Buenas noches», con tu nombre o «a todos», según la hora del dispositivo |
| Noticias RSS | Hasta 5 feeds propios (Opciones > Noticias); la fila se pliega al bajar a las apps |
| Salvapantallas | Botón con icono de luna: noticias a pantalla completa (solo con la fila de noticias activa) |
| Marcadores en vivo | Botón con icono de trofeo (punto rojo si hay algo en vivo): hasta 10 seguimientos (ligas, un equipo de una liga, selecciones de fútbol por confederación o una selección, NBA, NFL, MLB, NHL, F1) elegidos en Opciones > Marcadores, con el logo de cada competición o confederación, el escudo de cada equipo y la bandera de cada piloto. Datos de ESPN; API-Football con tu propia clave si ESPN no responde; OpenF1 para el orden en vivo de la F1 cuando responde. Desactivado por defecto |
| Barra superior | Todos los botones son iconos del mismo tamaño: contenido (entradas, marcadores, noticias) y después sistema (voz, Wi-Fi, Bluetooth, Ajustes, opciones); el nombre de la red ya no se imprime. Bluetooth abre en Google TV «Mandos y accesorios» (emparejar y ver los dispositivos) y en Android TV los ajustes de Bluetooth; no aparece en las TVs sin Bluetooth |
| Dock y apps | Mantener OK sobre un icono > Mover; todo el dock se mueve como una fila |
| Entradas | Solo si el dispositivo tiene entradas: HDMI, AV y la app de TV del fabricante (lista `live_tv_packages` en `config.xml`) |
| Actualizaciones | Panel del lápiz > Actualizaciones > **Buscar actualizaciones ahora**: busca la última release de GitHub, la descarga, comprueba su SHA-256 y abre el instalador de Android. Con la búsqueda automática activada (por defecto) avisa al volver al Inicio, como mucho cada 12 horas |
| Recuperación | Panel del lápiz > Recuperación: reactivar el inicio original, abrir Ajustes, restaurar los valores del Inicio (pide confirmación) y cerrar |

El diseño, los fondos, el salvapantallas de reloj y el lector de noticias son los del launcher original.

## Instalar

Ver [docs/GETTING-STARTED.md](docs/GETTING-STARTED.md). En resumen, con el paquete de la release
(APK, `instalar.ps1`, `revertir.ps1`, `reparar-inicio.ps1` y `adb`):

```powershell
.\platform-tools\adb.exe connect <IP-de-la-TV>:<puerto>
powershell -ExecutionPolicy Bypass -File .\instalar.ps1
```

Los scripts detectan si es Google TV o Android TV y solo activan el servicio en Google TV.
Para deshacerlo: `revertir.ps1`. Más detalles en [docs/RECOVERY.md](docs/RECOVERY.md).

## Compilar

Requisitos: JDK 17 o superior y Android SDK (platform 34, build-tools 34.0.0).

```powershell
cd launcher
.\gradlew.bat assembleRelease
```

Sin `launcher/keystore.properties` y `launcher/release.jks` el APK se firma con la clave de
depuración local. **Esos dos archivos no se suben nunca al repositorio** (están en `.gitignore`).

## Estructura

| Carpeta | Contenido |
|---|---|
| `launcher/` | El launcher (Android, Java) |
| `scripts/` | Herramientas ADB de medición y prueba (`pixora-*`); ver la cabecera de cada script |
| `tools/` | Debloat reversible genérico heredado de CleanYourTV (`disable`, `restore`, `tv.py`) |
| `examples/` | Listas de debloat de otros televisores, como ejemplo del formato |
| `tests/` | Tests de los scripts y herramientas (Python y bash) |
| `docs/` | Documentación |

## Documentación

- [Primeros pasos](docs/GETTING-STARTED.md)
- [Recuperación](docs/RECOVERY.md)
- [Problemas conocidos](docs/KNOWN-ISSUES.md)
- [Personalización](docs/PERSONALIZATION.md) y [presets de experiencia](docs/EXPERIENCE-PRESETS.md)
- [Tarjetas de entradas](docs/INPUT-CARDS.md)
- [Traducciones](docs/LOCALIZATION.md)
- [Procedencia del código](docs/PROVENANCE.md) y [proyectos relacionados](docs/RELATED-PROJECTS.md)
- [Cómo contribuir](CONTRIBUTING.md)

## Licencia y créditos

MIT. Ver [LICENSE](LICENSE), [NOTICE.md](NOTICE.md) y [launcher/NOTICE.md](launcher/NOTICE.md).
Proyecto no afiliado a Google. Las marcas pertenecen a sus propietarios.
