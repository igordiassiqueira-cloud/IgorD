# MDA Guias — App Android (WebView)

App Android que abre o sistema https://mda-guias-pro.base44.app/login em tela cheia.

## O que o app faz
- Mantém o login (cookies e armazenamento local persistentes)
- Botão "voltar" do Android navega no histórico do site
- Upload de arquivos (inclusive múltiplos)
- Downloads normais, `blob:` e `data:` (PDFs gerados no navegador) salvos em Downloads
- Links `mailto:`, `tel:`, WhatsApp etc. abrem no app correspondente
- Tela de "Sem conexão" com botão para tentar novamente

## Gerar o APK pelo GitHub (sem instalar nada)
1. Crie um repositório no GitHub (pode ser privado).
2. Envie o conteúdo desta pasta para a branch `main`.
3. Vá em **Actions → Build APK**. O build roda sozinho a cada push
   (ou clique em **Run workflow**).
4. Ao terminar, baixe o artefato **MDA-Guias-APK** (vem em .zip com o `app-debug.apk`).

## Gerar pelo Android Studio
Abra a pasta no Android Studio, aguarde o sync e use **Build → Build APK(s)**.

## Instalar no celular
Copie o APK para o aparelho e abra. O Android vai pedir para permitir
"instalar apps de fontes desconhecidas" para o gerenciador de arquivos/navegador.

## Trocar o endereço
Edite `START_URL` em `app/src/main/java/br/com/atmis/mdaguias/MainActivity.kt`.

## Observações
- O APK é assinado com chave de debug: serve para distribuição interna,
  não para a Play Store.
- Requer Android 8.0 ou superior.
- Login com Google não funciona dentro de WebView (bloqueio do próprio Google).
  Use e-mail/senha ou peça a adaptação para Trusted Web Activity.
