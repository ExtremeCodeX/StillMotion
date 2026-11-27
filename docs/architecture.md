# Arquitetura

Um `WallpaperService` publica um único componente padrão Android. A Activity configura tema e modo, inicia a prévia pública e pode exportar o poster pelo Storage Access Framework. O serviço é protegido com `BIND_WALLPAPER` no componente. O APK não pede permissões e não declara rede, analytics, jobs, serviços foreground, sensores ou wake locks. O seletor do sistema confirma a mudança do papel de parede.

## Política

O núcleo Rust recebe o estado anterior, um evento e um booleano de elegibilidade. Devolve o estado seguinte e, quando necessário, um comando de início ou parada. A JNI transfere somente valores primitivos. Se a biblioteca ou um símbolo JNI não puder ser carregado, a implementação Java usa o mesmo formato de estado e descritor. Os testes de host comparam as transições e o parser e executam o ciclo completo também sem a biblioteca. Valores inválidos desativam animação e preservam o caminho estático. Em Android, o núcleo é `no_std` e os caminhos normais não alocam. A função de pânico terminal impede propagação por FFI em falha interna inesperada.

| Situação | Trabalho |
|---|---|
| Sem superfície ou oculto | Nenhum frame animado. Um redraw estático explícito do sistema pode produzir um poster se houver Surface. |
| Visível, estático | Poster único na entrada ou no redraw, sem agenda recorrente. |
| Início elegível | Reserva a sessão e valida o descritor do tema. Prepara um único bitmap e calcula deadline monotônico. |
| Reprodução | Um único callback pendente no Looper da engine. Faz center crop e zoom, salta trabalho atrasado e limita a cadência abaixo de 24 fps. |
| Finalização ou cancelamento | Envia zoom final 1,0 ao concluir, remove callback, solta a referência do bitmap. Se estava oculto, o poster é refeito na próxima visibilidade se necessário. |
| Destruído | Descarta referências e callbacks. Engines reais compartilham um receiver enquanto vinculadas; preview não é inscrito e tem estado isolado. |

Para iniciar, o receiver precisa observar SCREEN_OFF seguido de USER_PRESENT com o aparelho interativo. Desde a versão 0.1.1, SCREEN_ON serve para diagnóstico e atualização de poster. A engine pode consumir USER_PRESENT quando a visibilidade, a Surface ou o estado do keyguard ficam aptos até 300 ms depois de receber o evento. Usa os próprios callbacks e, se já estiver visível, pode fazer uma única rechecagem após 80 ms. Uma notificação de ocultação, perda de superfície, economia de bateria ou nova sessão cancela a autorização pendente. Um evento duplicado não prolonga o prazo. O retorno à home após conclusão ou cancelamento preserva o poster.

A orquestração usa API 34+ para restringir a execução à home no display principal. Exige display ligado, dispositivo interativo, keyguard liberado e Canvas de hardware. Quando a visibilidade chega perto de USER_PRESENT, o app não tem uma API pública que identifique qual app estava em primeiro plano. O limite de 300 ms restringe essa decisão à transição observada. A confirmação no Galaxy ainda depende dos callbacks do firmware.

A criação da superfície muda a geração. Callback de reprodução guarda a geração e a identidade do playback. Callbacks de Android e envio estão no mesmo Looper, eliminando disputa entre nosso submit e uma destruição já entregue. O serviço cancela imediatamente depois que recebe ocultação, perda de superfície ou tela apagada. Um lock/post gráfico já em execução e buffers entregues ao sistema ainda podem drenar. Uma animação tardia é encerrada por timestamps monotônicos, sem tentar compensar quadros atrasados. Uma alteração de tamanho pode agendar um redraw único, cancelado se o callback de redraw do sistema chega no mesmo ciclo, ou se a engine perde visibilidade.

## Renderização e assets

O backend inicial usa `SurfaceHolder.lockHardwareCanvas`, com modo estático por Canvas de software se o primeiro lock de hardware falhar. Um producer de software jamais é trocado por outro na mesma geração. Sem decoder de vídeo, contexto EGL/GLES próprio ou Vulkan no APK. HWUI e o compositor continuam responsáveis por seus buffers e caches.

Dois temas originais usam um WebP opaco e uma transformação de câmera, com 1,2 s ou 1,4 s, 24 fps como teto e a mesma imagem no poster final. O descritor de oito bytes contém versão, backend, duração, FPS e zoom, tem teto de 2 s e passa pelo parser Rust. Um manifesto JSON com hashes acompanha cada tema. O poster é limitado a 2,1 MP antes de decodificar. Sem atlas ou retenção de sequência. O bitmap de movimento é o mesmo do último frame durante o playback, e a engine abandona a referência após o fim. A comparação de consumo entre manter ou recriar buffers permanece dependente de traces físicos.

Os pontos de extensão atuais são o descritor e o backend selecionado. Novos formatos só entram quando um efeito autorizado e a comparação justificar. O fluxo de vídeo do Flyme e os shaders proprietários ficam fora do build. Um vídeo ou cena 3D original adicional pode usar a mesma política de vida finita depois de passar pelo seu gate específico.

A variante `probe` pinta só o poster e registra a mesma entrada de callbacks sem carregar JNI. A variante `debug` usa a máquina de estados e registra visibilidade, superfície, motivo de bloqueio do início e contagem por execução. O botão **Diagnóstico do desbloqueio** mostra os 48 eventos mais recentes da execução atual, mantidos em memória. O botão **Copiar** coloca o texto na área de transferência. Não há logs por quadro.
