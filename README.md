# ViaMDM — navegador Android con túnel zrok hacia Mister Download Manager

Navegador ligero basado en WebView (estilo Via Browser) que añade al navegador tres cosas del
servidor: **descargar en el servidor MDM lo que se toque** (enlace, pulsación larga, compartir
desde otra app), **navegar con la IP del servidor** (SOCKS5 por el túnel) y **el explorador de
archivos del servidor** (WebDAV). Todo por zrok/OpenZiti: cada conexión con su token privado y
su puerto local configurable, sin puertos abiertos en el servidor.

## Las tres conexiones (ajustables en ambas apps)

| Conexión | Qué es | Escucha local en el móvil | Token |
|---|---|---|---|
| api | El control remoto de MDM (emparejar, `/api/add`, cola) | `127.0.0.1:8463` (editable) | share privado `tcpTunnel` |
| socks | La salida a internet del servidor | `127.0.0.1:9191` (editable) | share privado `socks` |
| drive | El explorador de archivos (WebDAV) | `127.0.0.1:8464` (editable) | share privado `drive` |

El servidor (fork de [misterdownloadmanager](https://github.com/aldan9610/misterdownloadmanager),
rama con la crate `mdm-tunnel`) publica las tres reservas con la CLI `zrok2`, guarda sus tokens
y muestra un QR `mdm-tunnel://setup?api=…&socks=…&drive=…` que esta app lee en Ajustes →
**Leer la configuración del QR**.

## Cómo se usa (primera vez)

1. **Servidor**: abre MDM, Options → Remote → **zrok tunnel…**, pega el token de cuenta de
   zrok, activa el túnel y guarda con OK. La ventana muestra los tres tokens y el QR.
2. **Móvil**: instala la APK, Ajustes → pega el **token de cuenta de zrok** (cada app tiene el
   suyo) y lee el QR del servidor (o pega los tokens a mano, y elige los puertos locales).
3. Ajustes → **Servidor MDM** (o menú del navegador → *Servidor MDM*): empareja con el código
   de 8 dígitos de Options → Remote → Pair a device.
4. Navega. El chip de la barra y la notificación dicen el estado del túnel. El interruptor de
   modo (`direct` / `server` en Ajustes) cambia la IP con la que sales: la del móvil o la del
   servidor (el DNS viaja por el túnel; el servidor resuelve).

## Estructura

```
tunnel/                 módulo Go (SDK de zrok) → AAR con gomobile (CI)
  tunnel.go             Enable() y ListenTCP(token, "127.0.0.1:puerto") por conexión
app/                    la APK (Kotlin, WebView)
  MainActivity.kt       el navegador: barra única, pestañas, «enviar a MDM», modo nocturno
  MdmClient.kt          API de MDM con pin TLS (TOFU) + puente HTTP CONNECT→SOCKS5 + ProxyController
  TunnelService.kt      servicio en primer plano con las tres escuchas
  TunnelProvider.kt     AAR (gomobile) o binario zrok en jniLib — el que haya
  ExplorerActivity.kt   el explorador: PROPFIND/GET/PUT/MKCOL/DELETE/MOVE sobre el drive
  SettingsActivity.kt   tokens, puertos, endpoint, modo de red, emparejamiento, QR
.github/workflows/      build de la AAR (gomobile) y de la APK firmada (release)
```

## Build

La CI construye la AAR y la APK en cada push a `main` y firma la APK de release con el
keystore de los secretos del repositorio. En local:

```bash
make aar        # gomobile bind -o app/libs/viamdm-tunnel.aar ./tunnel
./gradlew assembleRelease
```

El binario alternativo (proveedor `binary`) se empaqueta como `app/src/main/jniLibs/arm64-v8a/libzrok.so`
con el binario `zrok2` de [openziti/zrok releases](https://github.com/openziti/zrok/releases).
