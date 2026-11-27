# Desempenho e gates

Em repouso, o projeto não agenda playback, vsync do app, sensor, job, timer periódico ou wake lock. As únicas submissões estacionárias possíveis são redraw solicitado ou atualização pontual de conteúdo. Após reconhecer ocultação ou tela apagada, remove callbacks de animação e fecha o renderer ou inicia a liberação do decoder. Android pode manter buffers, RenderThread, compositor e processo. Essas propriedades de implementação não são medições de energia, RAM ou GPU.

## Revisão 0.4.4

| Caminho | Decisão |
|---|---|
| Órbita | Mantém a resolução da superfície, o zoom de 1,05 s, a fase das ondas, a cadência de 24 fps no bloqueio e 30 fps no zoom. O shader roda apenas no retângulo que cobre as bolhas. O fundo é limpo em uma operação de cor. A variante de uma ou duas bolhas e a opção de névoa são especializadas ao abrir o renderer. A dimensão é consultada na abertura e no redimensionamento. |
| Tela e visibilidade | Estado de bloqueio, display e energia é atualizado nos callbacks do Android, sem consultar o keyguard por quadro. A tela apagada interrompe o loop. O desbloqueio tem uma janela curta para estados OEM atrasados. Se ocultado durante o zoom, o tema reaparece no quadro final. O loop da tela bloqueada funciona apenas enquanto ela está visível e ativa. [Callbacks do Android](https://developer.android.com/reference/android/service/wallpaper/WallpaperService.Engine) |
| Flyme importado | Mantém AVC com decodificação por hardware direto para a Surface. O cancelamento deixa de reenfileirar o encerramento quando chamado repetidamente. Uma saída de codec sem quadro é devolvida sem agendar uma tarefa. Continua a liberar o decoder e o extractor antes de mostrar o poster. |
| Temas simples e política | Mantém Canvas para os temas antigos com bitmap e Rust para a política de ciclo de vida. Os caminhos não ganhariam paralelismo ou qualidade ao serem reescritos nesta versão. Vulkan exigiria outro backend sem evidência de vantagem física para essa cena. |

O recorte foi validado com as dimensões 720×1560, 1080×2340 e 1440×2560, para ambas as geometrias e opções de névoa. O teste usa a mesma máscara calculada pelo código Java do app. Em 1080×2340 o retângulo do shader cobre 2,98–3,51% no início do despertar e 78,28–83,62% no bloqueio com a bolha já ampliada. No zoom que preenche a tela o shader continua em resolução integral. A limpeza de fundo, a composição do Android e os custos do driver são externos a essa contagem de área.

O teste de tempo é um comparativo de rasterização por software em um único thread no PC. Ele exclui compilação, leitura de imagem, latência de desbloqueio e composição Android. Foram cinco pares alternados por cenário, com a média de dois quadros por amostra após aquecimento, sincronizados por `glFinish`. Em 1080×2340, duas bolhas sem névoa passaram de 43,4 para 7,8 ms de mediana no primeiro quadro e de 89,7 para 74,7 ms no bloqueio já ampliado. Esses números não estimam FPS, consumo ou economia no Galaxy. O CSV contém também quadros que ocupam toda a tela, nos quais o recorte tem pouco ou nenhum efeito. [Medições do host](../bench/results/orbita-0.4.4-host.csv)

O comparativo de imagem admite diferença de uma unidade de cor em 8 bits devido ao arredondamento e ao fundo limpo. O dithering do shader continua ativo nas bolhas. Para reproduzir a medição, use JDK, compilador C, EGL e GLES do host, a partir da raiz.

```bash
work=$(mktemp -d)
unzip -p StillMotion-0.4.3.apk res/raw/orbita.frag > "$work/previous.frag"
javac --release 8 -d "$work" app/src/main/java/dev/stillmotion/wallpaper/OrbitaBounds.java bench/OrbitaBoundSamples.java
cc -O2 -Wall -Wextra bench/orbita-host.c -o "$work/check" -lEGL -lGLESv2
java -cp "$work" dev.stillmotion.wallpaper.OrbitaBoundSamples |
  EGL_PLATFORM=surfaceless LIBGL_ALWAYS_SOFTWARE=1 LP_NUM_THREADS=1 "$work/check" "$work/previous.frag" app/src/main/res/raw/orbita.frag
```

## Estado de validação

| Critério | Cobertura atual |
|---|---|
| Estado/sessão, fallback, geração e JNI | Testes de unidade e bridge JVM de host. O host testa sequências e chamadas concorrentes, sem reproduzir o driver de Surface. |
| APK, permissões e dependências | Manifesto empacotado, integridade ZIP, assinatura e alinhamento. Sem SDK externo, rede ou dependências Cargo. |
| Poster e backend | WebP original validado no host, dimensões e conteúdo verificados. Retenção e recuperação de Surface em hardware dependem de testes no A15. |
| Cancelamento, 100 ciclos e inatividade | Sequências de política automatizadas no host. O funcionamento do driver e a meta de teardown p95 ≤ 250 ms exigem medição no aparelho. |
| PSS em repouso, GPU, jogo, energia e temperatura | Sem métricas físicas. A meta exploratória de PSS ≤ 20 MiB, os alertas de jogo e equivalência energética continuam em aberto. |
| Comparação de codecs, GLES e Vulkan | Comparação técnica e plano para assets equivalentes. Sem ranking de CPU, energia ou GPU. |
| Licença e paridade visual com APK original | Originais auditados estaticamente, sem direitos de redistribuição estabelecidos. Implementação e imagens próprias, sem reprodução visual dos temas de referência. |

Meça A (estático do sistema com poster equivalente), B (original quando autorizado e disponível) e C (StillMotion) com sequência, brilho, conectividade, carga e temperatura controlados. Separe custo do painel e composição da atividade própria. Faça no mínimo cinco repetições curtas e janelas longas para bateria. Preserve mediana, p95, dispersão e dados brutos. Os resultados no host descrevem implementação e tamanho. Um emulador só pode confirmar funcionalidade da imagem AOSP usada.

[Roteiro de teste no Galaxy](../bench/teste-galaxy-a15.md) · [Resultados registrados](../bench/results/) · [Decisão de backend](decisions/001-backend.md)

## Tamanho medido no build

No build 0.1.0, o núcleo ARM64 passou de 260.392 para 4.072 bytes ao compilar com `no_std` e LTO. Em x86-64, passou de 289.544 para 4.096 bytes. `opt-level=s` ficou 64 bytes abaixo de `3` no ARM64 e 16 bytes acima no x86-64. Foi mantido para os dois alvos com prioridade ao ARM64. `z` produziu arquivos maiores em ambos. Essas medidas incluem o ELF no disco. Startup, CPU e memória residente não foram extrapolados desses bytes. [Dados de tamanho](../bench/results/rust-size.csv) · [Tamanho e hashes dos APKs atuais](../bench/results/build.json)

## Emulador

A imagem oficial AOSP Android 15 x86_64 rev. 2 foi iniciada em Android Emulator 37.3.10.0 com SwiftShader. O host não expunha KVM, então foi necessário TCG. O gerenciador de pacotes apareceu antes de sua inicialização interna, e a instalação da prova retornou uma exceção do serviço do sistema. O watchdog reiniciou o `system_server` em dois ciclos, antes de `sys.boot_completed`. O teste de seleção e apresentação Android ficou inconclusivo. O arquivo de teste pode ser usado em um AVD já iniciado e descartável. [Resultado da tentativa](../bench/results/emulator.json) · [Teste de seleção AOSP](../bench/emulator-smoke.py)

## Marcos e critérios do plano

| Marco | Entrega e estado |
|---|---|
| M1 | APK estático probe assinado, manifesto e fluxo público implementados. Entrega real de callbacks continua pendente. |
| M2 | Núcleo Rust/JNI implementado. Dez testes Rust, 600 ciclos JNI/JVM e os testes de deadline e autorização finita de desbloqueio passaram no host. |
| M3 | Canvas e dois temas próprios em APK debug. Renderização, retenção e cancelamento Android aguardam execução em ambiente funcional. |
| M4 | Roteiro de validação Galaxy entregue. Modelo, Android, One UI e comportamento real serão registrados pelo usuário. |
| M5 | Dois APKs auditados. Comparação visual e energética com o original e reuso de material proprietário dependem de autorização e de execução controlada. |
| M6 | Shaders, 3D e vídeo documentados como candidatos. O backend implementado cobre transformação de imagem. |
| M7 | Otimizações de compilação comparadas em tamanho real. Teto, lógica de repouso e ciclo de vida testados no host. Regressões físicas continuam como critérios abertos. |
| M8 | APK de debug e prova estática assinados para instalação, fontes e documentação entregues. Release de produção depende de validação do aparelho. |
