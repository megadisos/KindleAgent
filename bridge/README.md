# Puente de Jarvis para Windows

Este programa corre en tu PC y conecta la tableta con Claude usando tu **plan Pro** a través de Claude Code. No usa la API de pago.

```
Fire 7 ──(audio por wifi)──▶ PC: Whisper (voz a texto) ──▶ Claude Code (tu Pro) ──▶ respuesta ──▶ la tableta la dice
```

## Instalar (una sola vez)

1. **Python**: descárgalo de https://www.python.org/downloads/ e instálalo. En la primera pantalla marca **"Add python.exe to PATH"**.
2. **Claude Code**: abre PowerShell y ejecuta:
   ```
   irm https://claude.ai/install.ps1 | iex
   ```
   Luego cierra y abre PowerShell, escribe `claude` y sigue los pasos para **iniciar sesión con tu cuenta de Claude** (la del plan Pro). Cuando veas el chat de Claude Code ya puedes salir con `/exit`.

   Si PowerShell dice que **`claude` no se reconoce**, el instalador no lo agregó a la ruta. Pega esto, cierra y vuelve a abrir PowerShell:
   ```
   [Environment]::SetEnvironmentVariable("Path", [Environment]::GetEnvironmentVariable("Path","User") + ";$env:USERPROFILE\.local\bin", "User")
   ```
   Si Claude Code pide **Git**, instálalo desde https://git-scm.com con las opciones por defecto.
3. **Este repo**: descárgalo con el botón verde **Code → Download ZIP** en GitHub y descomprímelo, por ejemplo en `Documentos\KindleAgent`.

## Usar

1. Entra a la carpeta `bridge` y haz doble clic en **`iniciar_jarvis.bat`**. La primera vez instala lo necesario y descarga Whisper (unos minutos).
2. Si Windows pregunta por el **firewall**, permite el acceso en **redes privadas**.
3. La ventana muestra algo así:
   ```
   En la tableta escribe:  IP 192.168.1.20   puerto 8737   código 123456
   ```
4. En la tableta, toca el engranaje ⚙ (arriba a la derecha) o mantén presionada la cara, escribe esa IP, el puerto y el código, y toca **Conectar**.
5. Toca la cara, habla, y espera la respuesta.

Deja la ventana abierta mientras uses a Jarvis. En la ventana verás lo que entendió y lo que respondió.

## Opciones

Puedes pasar opciones al `.bat` desde una terminal, por ejemplo `iniciar_jarvis.bat --model haiku`:

- `--port 8738`: usa otro puerto si la ventana dice que el 8737 está ocupado. En la tableta cambia el puerto al mismo número.
- `--model haiku`: modelo por defecto si la tableta no elige uno. Normalmente lo eliges en la tableta.
- `--whisper base`: reconocimiento de voz más rápido pero menos preciso. `medium` es más preciso pero más lento. Por defecto `small`.
- `--device cuda`: usa tu tarjeta NVIDIA para Whisper, si tienes una.

## Darle acceso a tus proyectos

Por defecto Jarvis está encerrado en su propia carpeta. Abre una terminal en la carpeta de Jarvis y ejecuta, una sola vez por carpeta:

```
.\iniciar_jarvis.bat --add-folder "E:\ruta\proyecto"        (solo lectura)
.\iniciar_jarvis.bat --add-folder-edit "E:\ruta\proyecto"   (puede editarlo en modo edición)
.\iniciar_jarvis.bat --remove-folder "E:\ruta\proyecto"     (quitarla)
```

Las carpetas quedan guardadas y la ventana las muestra al arrancar.

- **Normal:** Jarvis puede leer y buscar en todas tus carpetas, pero no cambia nada.
- **Modo edición** (se activa en el ⚙ de la tableta, y arriba a la izquierda aparece "✎ Modo edición"): puede crear y modificar archivos en las carpetas editables y crear **proyectos nuevos** en `%USERPROFILE%\JarvisProyectos`. Puedes cambiar esa carpeta con `--projects-dir "E:\ruta"`. Antes de cambiar algo te dice qué va a hacer y espera tu "sí". En este modo no ve las carpetas de solo lectura.
- **Terminal** (otro interruptor en el ⚙, solo funciona con el modo edición activo): además puede ejecutar comandos en tu PC, por ejemplo instalar paquetes, usar git o correr tu proyecto. Antes de cada comando te dice cuál es y espera tu "sí". Siempre están bloqueados: borrar carpetas (`rm -r`, `rd /s`, `del /s`, `Remove-Item`), `git push`, `git reset --hard`, `git clean`, `format`, `diskpart` y `shutdown`.
  - En Windows, Claude Code usa **Git for Windows** para la terminal. Si no lo tienes, instálalo desde https://git-scm.com.
  - Ojo: los comandos corren con tus permisos de usuario y no quedan limitados a una carpeta. Actívala solo cuando la necesites.
- Usa git en tus proyectos editables para poder deshacer cualquier cambio.

## Escribir y cámara en vivo

- **⌨ Teclado:** abre una barra arriba para escribirle cuando no te entiende bien por voz.
- **📷 Cámara en vivo:** prende la cámara de la tableta (trasera por defecto; también se elige en el ⚙) y muestra lo que ve en una ventanita arriba a la izquierda. Mientras esté prendida, cada vez que le hables o escribas, Jarvis toma el cuadro de ese momento y lo mira junto con tu pregunta. Si escribes sin texto, pregunta "¿Qué ves?". El botón 🔄 que aparece al lado cambia entre la cámara frontal y la trasera al instante. Otro toque al 📷 la apaga (también se apaga sola si sales de la app). No es video continuo: Claude mira una imagen por pregunta.
- Las fotos se guardan en `%USERPROFILE%\.jarvis\workspace\fotos`. Solo se guardan las últimas 30.

## Estado de la tableta

Con cada pregunta, la app manda un resumen del estado de la tableta: batería (y si está cargando), temperatura, modelo, almacenamiento, memoria RAM, wifi, ubicación, pantalla y brillo, volumen, luz ambiente, si está de pie o acostada y qué sensores tiene. Así puedes preguntarle "¿cuánta batería tienes?" o "¿cuánto espacio te queda?". Solo se usa para responder; no se guarda.

La ubicación pide permiso la primera vez que abres la app. Si dijiste que no, o Jarvis dice que la ubicación está apagada, actívala en la tableta en **Configuración → Ubicación** (y en **Apps → Jarvis → Permisos**). La Fire no tiene GPS: la ubica por el wifi, así que es aproximada.

## Voz de Jarvis

En ⚙ → **Voz** escoges entre las voces que tenga la tableta (al cambiarla suena una prueba), y ajustas tono y velocidad. **⬇ Descargar más voces** abre la pantalla del motor de voz de la tableta para bajar voces nuevas; al volver aparecen en la lista. Las que dicen "por descargar" se bajan solas la primera vez que se usan (si el motor lo permite), y las que dicen "usa internet" necesitan wifi.

## Cambiar de modelo

En el ⚙ de la tableta eliges **Haiku** (el más rápido), **Sonnet** (equilibrado) u **Opus** (el más inteligente, más lento y gasta más de tu límite de Pro). La conversación sigue aunque cambies de modelo.

## Bueno saber

- La personalidad de Jarvis está en `persona.md`. Puedes editarla y reiniciar el puente.
- Jarvis recuerda la conversación mientras sigas hablando. Después de 15 minutos sin hablar empieza una nueva.
- Usa los mismos límites de uso de tu plan Pro que la app de Claude.
- El código de la tableta evita que otro aparato de tu wifi use tu Claude. Se guarda en `%USERPROFILE%\.jarvis\config.json`.
