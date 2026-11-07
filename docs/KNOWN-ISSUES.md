# Problemas conocidos

La base de Pixora TV se ha usado en dos Chromecast con Google TV (Android 14) y en un televisor TCL con
Android TV 9. Sus funciones (instalación, arranque tras reiniciar, panel de opciones, noticias y
marcadores, con logos y selecciones) se han probado en un Chromecast con Google TV con la versión
2026.11.07.009; el actualizador y Android TV 9 aún no se han probado.

## Google TV (Chromecast)

- **Google TV atiende Inicio por su cuenta.** Ignora la app de inicio predeterminada, así que Pixora TV
  depende de su servicio de accesibilidad, que recibe la tecla Inicio del mando y abre el launcher.
  Sin ese servicio, Inicio lleva a Google TV.
- **Momentos sin aviso.** Al arrancar, tras actualizar Pixora TV y al despertar la TV, Google TV Home puede
  quedar delante sin que el sistema lo anuncie. El servicio mira entonces qué app está delante (solo
  su nombre de paquete) y la sustituye. Al arrancar se sigue viendo Google TV un instante, hasta que
  el servicio se inicia.
- **Asistente de voz.** Los resultados de búsqueda se muestran dentro de Google TV Home; Pixora TV los deja
  en pantalla mientras estás en ellos. Al salir con Atrás, al abrir otra app o al volver a Pixora TV,
  Google TV Home se sustituye de nuevo. Por eso, Atrás desde una ficha abierta desde los resultados
  vuelve a Pixora TV, no a los resultados.
- **Mantener pulsado Inicio** abre el panel nativo con un temporizador (el filtro de teclas no recibe
  repeticiones); con el mando real aún no se ha comprobado en todos los casos.
- **Teclas enviadas por `adb shell input`** no pasan por el servicio de accesibilidad: para probar
  Inicio o la doble pulsación hay que usar el mando.
- **Salvapantallas del sistema.** Una app no puede iniciarlo en Google TV; el botón de luna abre el
  salvapantallas propio de Pixora TV (noticias a pantalla completa).
- **Sin el servicio de accesibilidad** la doble pulsación abre los Ajustes de Android en lugar del
  panel. El launcher avisa una vez al día.
- **Nivel 2 (`instalar.ps1 -DesactivarGoogleTV`)** elimina Google TV del todo, pero se pierde el
  panel de ajustes rápidos, que forma parte de Google TV Home.

## Android TV

- **Sin doble pulsación ni vigilante del inicio.** El sistema respeta la app de inicio predeterminada,
  así que Pixora TV no los necesita y los apaga aunque el servicio esté activado.
- **Entradas.** Algunos televisores con Android TV 9 no anuncian sus entradas de la forma estándar;
  Pixora TV usa entonces la app de TV del fabricante si está en la lista `live_tv_packages`
  (`launcher/app/src/main/res/values/config.xml`; hoy, la de TCL).

## Ambos

- **Entradas.** Sin entradas propias (Chromecast), el botón y la fila «Tarjetas de entrada» se ocultan.
- **Actualizador.** Toma la última release publicada de `EduJalonT/PixoraTV` en GitHub. Android
  siempre pide confirmar la instalación en su propia pantalla (en Android 12 o superior puede dejar
  de pedirla cuando la versión anterior la instaló el propio Pixora TV). Necesita el permiso «Instalar
  apps desconocidas» para Pixora TV: `instalar.ps1` lo concede; si falta, el launcher lleva al ajuste.
  Un APK firmado con otra clave se rechaza. Tras instalarse, Google TV puede mostrar un instante
  Google TV Home hasta que el servicio vuelve a arrancar.
- **Nombre del dispositivo.** Se lee de `Settings.Global.DEVICE_NAME`; si el sistema no lo da, se
  usa el nombre Bluetooth o el modelo.
