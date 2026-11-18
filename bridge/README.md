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
- Nunca puede ejecutar comandos, instalar paquetes ni usar git. Te recomiendo usar git en tus proyectos editables para poder deshacer cualquier cambio.

## Cambiar de modelo

En el ⚙ de la tableta eliges **Haiku** (el más rápido), **Sonnet** (equilibrado) u **Opus** (el más inteligente, más lento y gasta más de tu límite de Pro). La conversación sigue aunque cambies de modelo.

## Bueno saber

- La personalidad de Jarvis está en `persona.md`. Puedes editarla y reiniciar el puente.
- Jarvis recuerda la conversación mientras sigas hablando. Después de 15 minutos sin hablar empieza una nueva.
- Usa los mismos límites de uso de tu plan Pro que la app de Claude.
- El código de la tableta evita que otro aparato de tu wifi use tu Claude. Se guarda en `%USERPROFILE%\.jarvis\config.json`.
