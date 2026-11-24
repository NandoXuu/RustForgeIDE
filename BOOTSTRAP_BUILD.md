# RustForge IDE — bootstrap Linux AArch64

O workflow `.github/workflows/android.yml` constrói o ambiente para `com.ndx.rustforge.ide` a partir do repositório oficial `termux/termux-packages`.

## Execução

1. Envie o projeto RustForge para seu repositório GitHub.
2. Abra **Actions → RustForge Android AArch64**.
3. Execute **Run workflow**.
4. Aguarde o job terminar e baixe o artefato `rustforge-android-aarch64`.

O artefato de uma execução bem-sucedida contém:

- `app-debug.apk`
- `bootstrap-aarch64.zip`
- `bootstrap-build.log`

O workflow registra o commit exato de `termux-packages` usado naquela execução. A referência inicial é `master`, configurável por `TERMUX_PACKAGES_REF` no workflow.

## Como o prefixo é produzido

O workflow configura o applicationId, o namespace, o nome e os identificadores internos da integração antes de carregar `scripts/properties.sh`. O nome interno upstream `TERMUX__INTERNAL_NAME` é mantido porque o próprio repositório oficial o define como constante, mas caminhos e namespaces privados do app apontam para RustForge. O prefixo e o HOME resultantes são:

- `/data/data/com.ndx.rustforge.ide/files/usr`
- `/data/data/com.ndx.rustforge.ide/files/home`

O bootstrap é produzido por `scripts/build-bootstraps.sh` dentro do builder Docker oficial. Os pacotes adicionais incluem Rust, Git, CMake, Ninja, Clang, Make, pkg-config e certificados. A dependência `rust-std-aarch64-linux-android` é solicitada pelo próprio pacote Rust do repositório.

O pós-processador `scripts/prepare-rustforge-bootstrap.py`:
- desativa as fontes APT do Termux no bootstrap standalone;
- normaliza links simbólicos para caminhos relativos dentro do prefixo;
- rejeita caminhos privados de `com.termux`, links externos/quebrados e caminhos inseguros;
- exige ferramentas e biblioteca padrão Rust para `aarch64-linux-android`;
- grava `RUSTFORGE-MODES.txt`, para o instalador Android restaurar permissões que `ZipInputStream` não expõe.

## Instalação no Android

`Bootstrap.kt` extrai primeiro em `files/usr.staging`, valida os caminhos e restaura links/permissões, e só então ativa `files/usr`. `SetupViewModel` não executa `apt update`, `apt upgrade`, `pkg` nem instala pacotes pela rede. O marcador `.rustforge_ready` é criado somente depois de Bash, `rustc`, Cargo e `libstd` do target Android responderem aos testes executados no próprio dispositivo.

## Limite importante de validação

O workflow valida o conteúdo do ZIP, permissões, links, arquitetura dos ELF AArch64, pacote Android, assinatura de debug e inclusão exata do bootstrap dentro do APK. A validação final de execução de `bash`, `rustc`, Cargo e `libstd` acontece no primeiro uso, dentro do aplicativo Android. Um APK construído com sucesso ainda precisa ser instalado e testado em um dispositivo ARM64 real; isso não é simulado pelo runner Ubuntu.


## Android 10+ e execução de binários

O módulo mantém `targetSdk = 28` porque o ambiente executa binários extraídos em `files/usr`. O Android 10 introduziu a restrição W^X para apps que miram API 29 ou superior: esses apps não podem executar código arbitrário gravado no diretório privado de dados. O workflow verifica que o `targetSdk` permaneça em 28. Não aumente esse valor sem migrar a distribuição dos executáveis para o modelo de bibliotecas nativas empacotadas no APK e adaptar o instalador.
