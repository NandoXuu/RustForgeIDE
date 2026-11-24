# RustForge IDE 0.3.0 — atualização de desenvolvimento

## Incluído nesta atualização

- Logo Android + Rust crab adicionada como ícone do aplicativo e identidade visual na tela de projetos.
- Sugestões locais de código Rust enquanto digita (palavras-chave, tipos comuns e identificadores encontrados no arquivo).
- Navegador de símbolos do arquivo: `fn`, `struct`, `enum`, `trait`, `mod`, `type`, `const` e `static`, com salto para a linha.
- Diagnósticos do compilador exibidos na IDE usando `cargo check --message-format=json`.
- Indicador textual de progresso/etapa de build e mensagens de compilação.
- Ação para adicionar dependências pelo `cargo add crate` ou `crate@versão`.
- Proteção de caminhos no explorador e no `cd` para impedir sair da pasta do projeto.
- Criação/abertura de arquivos com atualização da árvore.

## Ainda não concluído / limitações

- As sugestões atuais são locais e contextuais; **não são ainda o autocomplete semântico do rust-analyzer**.
- O binário/servidor `rust-analyzer` e o cliente LSP (JSON-RPC) ainda precisam ser distribuídos e conectados ao editor para completar, publicar diagnósticos em tempo real e navegar por símbolos entre arquivos.
- O terminal ainda executa comandos em processos separados; não é uma sessão PTY persistente com shell interativo completo.
- O acompanhamento de build é baseado nas mensagens emitidas pelo Cargo, não em uma barra percentual real.
- O pacote recebido não continha `app/src/main/assets/bootstrap-aarch64.zip`, embora `Bootstrap.kt` espere esse arquivo. É necessário incluir o bootstrap ARM64 usado pelo projeto original antes de testar a instalação em um dispositivo.

## Como compilar

Abra a pasta `RustForgeIDE` no Android Studio ou use Gradle 8.9 + JDK 17:

```bash
gradle assembleDebug
```

O APK de debug será gerado em `app/build/outputs/apk/debug/`.
