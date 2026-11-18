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
   En la tableta escribe:  IP 192.168.1.20:8737   código 123456
   ```
4. En la tableta, toca el engranaje ⚙ (arriba a la derecha) o mantén presionada la cara, escribe esa IP (con el `:8737`) y ese código y toca **Conectar**.
5. Toca la cara, habla, y espera la respuesta.

Deja la ventana abierta mientras uses a Jarvis. En la ventana verás lo que entendió y lo que respondió.

## Opciones

Puedes pasar opciones al `.bat` desde una terminal, por ejemplo `iniciar_jarvis.bat --model haiku`:

- `--port 8738`: usa otro puerto si la ventana dice que el 8737 está ocupado. En la tableta escribe la IP con ese puerto, por ejemplo `192.168.1.20:8738`.
- `--model haiku`: respuestas más rápidas y gasta menos de tu límite de Pro. Por defecto usa `sonnet`.
- `--whisper base`: reconocimiento de voz más rápido pero menos preciso. `medium` es más preciso pero más lento. Por defecto `small`.
- `--device cuda`: usa tu tarjeta NVIDIA para Whisper, si tienes una.

## Bueno saber

- La personalidad de Jarvis está en `persona.md`. Puedes editarla y reiniciar el puente.
- Jarvis recuerda la conversación mientras sigas hablando. Después de 15 minutos sin hablar empieza una nueva.
- Usa los mismos límites de uso de tu plan Pro que la app de Claude.
- El código de la tableta evita que otro aparato de tu wifi use tu Claude. Se guarda en `%USERPROFILE%\.jarvis\config.json`.
