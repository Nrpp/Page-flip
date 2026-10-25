# Page Flip

Pasa páginas en **KOReader (Kindle)** con los **botones de volumen de tu móvil Android**, incluso con la pantalla del móvil apagada.

- **Volumen +** → página siguiente
- **Volumen −** → página anterior

Tiene dos partes:

| Parte | Dónde | Qué hace |
|---|---|---|
| `koreader/pageflip.koplugin` | Kindle (KOReader) | Abre un pequeño servidor en la wifi y pasa página cuando recibe una orden. |
| `android/` → `PageFlip.apk` | Móvil Android | Se conecta al Kindle y convierte los botones de volumen en órdenes de pasar página. |

## Instalación

### 1. Plugin en el Kindle
1. Descomprime `dist/pageflip.koplugin.zip`.
2. Conecta el Kindle por USB y copia la carpeta `pageflip.koplugin` dentro de `koreader/plugins/`.
3. Reinicia KOReader.

### 2. App en Android
1. Instala `dist/PageFlip.apk` (tendrás que permitir «instalar apps de origen desconocido»).
2. Ábrela y acepta el permiso de notificaciones.
3. Recomendado: pulsa **«Permitir uso en segundo plano (batería)»** para que el sistema no corte la conexión con la pantalla apagada.

## Uso
1. En el Kindle, abre un libro en KOReader → menú superior → pestaña ⚙ → **Red** → **Page Flip (mando remoto)** → **Activar mando remoto**. Te mostrará la IP del Kindle. (Se queda activado para los siguientes libros.)
2. En el móvil (en la **misma wifi** que el Kindle), pulsa **«Buscar Kindle en la red»** o escribe la IP a mano, y pulsa **«Conectar»**.
3. Apaga la pantalla del móvil y usa los botones de volumen.

Para recuperar el control normal del volumen pulsa **Detener** (en la app o en la notificación). Si cierras la app desde «recientes» también se detiene.

> 💡 Si no hay wifi (por ejemplo, de viaje), activa el **punto de acceso** del móvil y conecta el Kindle a él. Funciona igual.

## Problemas habituales
- **No encuentra el Kindle**: comprueba que el Kindle tiene el wifi encendido y el mando activado, y que ambos están en la misma red. Algunas redes (wifi de invitados, hoteles) aíslan los dispositivos entre sí: usa el punto de acceso del móvil.
- **Con la pantalla apagada no pasa páginas**: si hay música u otra app de audio reproduciéndose, Android le da los botones de volumen a esa app. Páusala. En algunos móviles (Xiaomi, Huawei, Samsung…) hay que desactivar además el ahorro de batería para la app.
- **Se desconecta al rato**: cuando el Kindle se suspende apaga el wifi. La app se reconecta sola al despertar el Kindle.
- **Prueba rápida sin la app**: desde el navegador de cualquier dispositivo en la misma red, abre `http://IP-DEL-KINDLE:8134/next`.

## Cómo funciona
- **Kindle**: el plugin escucha en el puerto TCP **8134** (configurable) y UDP **8135** (descubrimiento). En Kindle abre esos puertos en el cortafuegos (`iptables`) mientras está activo. Cada orden genera el evento `GotoViewRel` de KOReader, el mismo que usan los botones físicos, así que funciona con EPUB, PDF, CBZ, etc.
- **Android**: un servicio en primer plano registra una `MediaSession` con control de volumen *remoto* (como cuando controlas el volumen de un Chromecast). Android entrega a esa sesión las pulsaciones de volumen aunque la pantalla esté apagada; la app las traduce en `NEXT`/`PREV` y las envía por una conexión TCP permanente.

Protocolo (texto, una orden por línea): `NEXT`, `PREV`, `PING` → `PONG`, `HELLO` → `PAGEFLIP 1`.

## Compilar
```sh
cd android
./gradlew assembleRelease   # → app/build/outputs/apk/release/app-release.apk
```
Requiere JDK 17+ y el Android SDK (platform 35). El workflow de GitHub Actions (`.github/workflows/build.yml`) compila el APK y empaqueta el plugin en cada push.
