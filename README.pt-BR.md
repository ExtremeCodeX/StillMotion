<h1><img src="assets/themes/stillmotion_icon.png" width="44" height="44" alt="" align="middle"> StillMotion</h1>

[English](README.md) · **Português (Brasil)**

StillMotion é um app Android de papéis de parede animados. O APK público começa com a galeria vazia. Ele importa pacotes do armazenamento local, mostra prévias da tela de bloqueio e da tela inicial e abre o seletor do Android para aplicar o tema.

Os shaders desenham na resolução da superfície. Os temas em vídeo usam decodificação por hardware quando disponível. Um pacote pode animar ao acender a tela ou ao desbloquear, com movimento opcional após o desbloqueio. O app para de desenhar e libera os recursos de reprodução quando o wallpaper fica oculto ou a tela apaga. Funciona offline, sem root e sem outro app de wallpaper.

## Como usar

[Baixar StillMotion 0.7.0 (APK)](StillMotion-0.7.0.apk)

1. Abra o StillMotion e toque em **Importar pacote de wallpapers**. Selecione o ZIP compatível sem extrair.
2. Escolha um tema e alterne entre **Tela de bloqueio** e **Tela inicial** para comparar as prévias.
3. Toque em **Aplicar wallpaper** e confirme no seletor do Android.

A reprodução animada usa informações de destino disponíveis no Android 14 e versões posteriores. Versões anteriores e o modo de economia de energia usam uma imagem estática. [Como criar um pacote compatível](docs/wallpapers-comunidade.md).

## Build e testes

O build usa JDK 17, Android SDK 35, Build Tools 37 e NDK r27d. Configure `ANDROID_SDK_ROOT` e instale os alvos Rust `aarch64-linux-android` e `x86_64-linux-android`. Os testes locais também usam Python com Pillow.

```sh
scripts/test-host.sh
scripts/build-android.sh debug
```

O APK de teste assinado é gerado em `build/debug/StillMotion-debug.apk`.

Os testes locais cobrem as políticas de reprodução em Rust e Java, a integração JNI e pacotes inválidos. A importação e a prévia também passaram em um emulador Android offline.

### Teste em aparelho físico

Medimos o StillMotion **0.4.4** em um Galaxy A15 5G com comandos ADB de leitura. Os resultados valem para aquela versão e para os intervalos amostrados.

| Cenário | Resultado |
| --- | --- |
| Tela inicial após a animação | O contador de CPU do processo do StillMotion ficou parado por **20 segundos**. |
| Jogo com o wallpaper oculto | O processo acumulou **10 ms de CPU em cerca de 6 segundos**. |
| Memória do processo | Cerca de **65 MiB de PSS** em uma amostra durante o jogo e **86–87 MiB de PSS** nas amostras após os ciclos de bloqueio e desbloqueio. Os valores incluem o processo inteiro. |

A conexão ADB caiu após a amostra no jogo. FPS e atividade da GPU ficaram sem medição. O cabo USB estava fornecendo energia, então essa coleta não mede autonomia.

## Licença

O código do StillMotion usa a [GNU General Public License v3.0](LICENSE). Pacotes de wallpaper criados de forma independente seguem a licença escolhida por seus autores.
