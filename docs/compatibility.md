# Compatibilidade

| Ambiente | Comportamento esperado |
|---|---|
| Android 8–13 (API 26–33) | Poster e seleção pública se houver picker. Em API 26–33 o build próprio mantém estático, pois a identificação pública do destino de cada engine não está disponível. |
| Android 14+ (API 34+) | Animação apenas em home do display padrão quando flags, Keyguard, PowerManager e callbacks satisfazem a política. Lock independente, preview, AOD, display secundário e economia de bateria permanecem estáticos. |
| ABI | Debug com arm64-v8a e x86_64. Probe sem bibliotecas nativas. O build animado não prevê instalação em Android exclusivamente 32 bits. |
| Seletor | Tenta a prévia específica via Android, depois o seletor genérico. Se ambos estiverem ausentes, há exportação de poster para aplicar pela Galeria ou interface de wallpaper. |
| Galaxy A15 5G | O usuário fará a validação no próprio aparelho. Modelo completo, Android, One UI, opção de lock/home, retenção do buffer, latência, jogo e autonomia precisam ser anotados por firmware. |
| OEM guarda visibilidade | Transições e apps transparentes podem deixar o wallpaper visível. Na ausência de evento elegível a imagem permanece parada. O app não tenta identificar jogos. |

Na versão 0.1.0, o usuário observou apenas o poster no Galaxy A15 5G. Desde a versão 0.1.1, a home pode se tornar apta até 300 ms depois de USER_PRESENT. A engine mantém o poster quando o firmware omite SCREEN_OFF ou USER_PRESENT, o destino não pode ser confirmado, ou a home permanece oculta durante toda a janela. O diagnóstico da própria Activity permite verificar os sinais da One UI sem ADB.

O relatório da versão 0.1.1 no Galaxy A15 5G com Android 17 (API 37) mostrou Canvas de hardware ativo, flags de home e lock (`3`), recepção de USER_PRESENT enquanto visível e bloqueio `NATIVE_UNAVAILABLE`. A inspeção encontrou referências a funções de Bionic no ELF, sem `DT_NEEDED` para `libc.so`. O erro exato do linker não foi registrado pelo build anterior. Desde 0.1.2, o build verifica a dependência e usa Java como fallback com a mesma política de reprodução se a JNI estiver indisponível. O diagnóstico informa a implementação usada e o motivo de falha do loader. [Android Bionic](https://android.googlesource.com/platform/bionic/+/refs/heads/main/android-changes-for-ndk-developers.md)

A amostra Google fornecida tem minSdk 36, portanto não serve como seletor instalável em Android 15 e anteriores. O produto usa ações públicas sem referência ao pacote Google. Nenhum APK de terceiros entra no build próprio.
