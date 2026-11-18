# KindleAgent: Jarvis en una Fire 7

Convierte una tableta Amazon Fire 7 en un asistente de voz con cara animada que conversa usando Claude.

## Estado

- [x] **Paso 1: la cara.** App a pantalla completa con ojos que parpadean y una boca que se mueve al hablar. Tocar la cara la hace decir un saludo con la voz de la tableta.
- [ ] **Paso 2: la voz.** Escuchar, enviar a Claude y responder en voz alta.
- [ ] **Paso 3: modo Jarvis.** Abrir al encender, personalidad, memoria.
- [ ] **Paso 4: extras.** Recordatorios, clima, etc.

## Instalar en la Fire

1. En la tableta: **Configuración → Seguridad y privacidad → Apps de fuentes desconocidas** y permite a **Silk** (el navegador) instalar apps.
2. Abre en Silk la página de [la última versión](https://github.com/megadisos/KindleAgent/releases/tag/latest) y descarga `jarvis.apk`.
3. Abre el archivo descargado y toca **Instalar**.
4. Abre **Jarvis** y toca la cara.

Cada vez que se fusiona un cambio en `main`, GitHub compila un APK nuevo y lo publica en esa misma página. Para actualizar, descárgalo e instálalo encima; no hace falta desinstalar.

## Estructura

- `app/src/main/assets/face/`: la cara (HTML, CSS y JS sobre un canvas). Se puede abrir `index.html` en un navegador para verla sin la tableta.
- `app/src/main/java/.../MainActivity.java`: muestra la cara a pantalla completa y le da voz con el TextToSpeech de Android.
- `.github/workflows/build-apk.yml`: compila el APK en cada push y PR.
- `app/debug.keystore`: llave de depuración fija para que las actualizaciones se instalen encima. No es una llave de publicación.
