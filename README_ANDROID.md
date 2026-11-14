# Aplicativo Android

O módulo `android/app` é um app Kotlin com WebView. Ao iniciar, ele abre o domínio oficial `https://youtube-downloader-dmf.pages.dev/` e inicia automaticamente o proxy local em `127.0.0.1` numa porta dinâmica. O app precisa de internet para carregar o site e suas dependências. Atualizações publicadas no Cloudflare Pages são carregadas pelo APK quando a página é reaberta; a interface não é uma cópia independente embutida neste APK.

A Activity injeta as pontes `AndroidProxy` e `AndroidDownloader` apenas no site oficial, restringe navegação principal a esse host e bloqueia o restante. O frontend usa a ponte para configurar automaticamente o proxy local e salvar o arquivo em blocos no armazenamento Android. Em navegador comum, sem pontes nativas, permanecem o proxy configurado pelo usuário e o salvamento via Blob.

O proxy aceita `http://127.0.0.1:PORTA/?url=URL_DESTINO`, GET/HEAD/OPTIONS e POST pequeno para metadados, CORS para as origens autorizadas, Range/206, streaming, redirects revalidados e cancelamento no encerramento do serviço. Ele escuta somente em loopback e bloqueia destinos privados, locais e não globais. Cookies e Authorization do chamador não são encaminhados.

## Dependências e limites

Pyodide, yt-dlp/yt-dlp-ejs e ffmpeg.wasm seguem os loaders do site. Este trabalho não empacota todos esses runtimes dentro do APK: Pyodide é carregado pela URL já usada pelo site, yt-dlp e yt-dlp-ejs são instalados pelo fluxo existente e FFmpeg.wasm usa seus assets originais. Portanto, o app não funciona offline e ainda depende das origens externas configuradas no frontend. Serviços opcionais de identificação musical também continuam externos.

O proxy encaminha o corpo sem acumulá-lo em memória, mas o downloader existente ainda mantém arquivos completos em memória/FS virtual para mesclagem e transferência. O bridge escreve em blocos no `MediaStore`, mas não remove esse limite de memória anterior. O armazenamento via `MediaStore` exige Android 10 (API 29) ou superior.

## Build e testes

Requisitos: JDK 17 ou superior, Android SDK Platform 36, Build Tools 36.0.0, Node.js 22.12 ou superior e npm.

Para validar o frontend e compilar o APK debug:

```bash
npm ci
npm test
npm run build
cd android
./gradlew testDebugUnitTest assembleDebug
```

O APK fica em `android/app/build/outputs/apk/debug/app-debug.apk`. Para instalá-lo com ADB em um aparelho conectado:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

O APK debug é autoassinado e destinado a testes. Uma versão release exige revisão de licenças, identidade definitiva de pacote/branding e assinatura por uma chave gerida pelo responsável.

Os testes JVM cobrem validação de destinos, bloqueio SSRF básico, inicialização do proxy e preflight CORS. Ainda é necessário testar em aparelho Android real: carregamento do domínio hospedado, Web Workers, WebAssembly/Pyodide, CORS/Mixed Content contra o loopback, download real do YouTube e salvamento no diretório Downloads. Sucesso de compilação/testes JVM não substitui esses testes de dispositivo.
