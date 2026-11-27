# Teste manual no Galaxy A15 5G

Para a versão 0.2.0 com vídeos do APK Flyme, instale o [APK atualizado](../build/debug/StillMotion-debug.apk), copie o [ZIP local](../temas/locais/Flyme-videos.stillmotion.zip) sem extrair e abra **StillMotion → Importar vídeos Flyme**. Selecione um dos quatro temas e confirme **Aplicar vídeo Flyme** para a tela inicial. Após desbloquear, observe se toca uma vez, mantém a última imagem e não reinicia ao voltar de um jogo. Os posters finais para o baseline estático estão no ZIP com os mesmos nomes dos MP4 e extensão `.webp`. A [prévia local](../build/flyme-preview/previa.html) mostra os trechos preparados. Se houver falha, copie **Diagnóstico do desbloqueio**, incluindo as linhas `video-*`.

O passo a passo das versões 0.1.x e os cenários gerais estão abaixo. Para a comparação de bateria da versão 0.2.0, substitua Dunas pelo mesmo tema Flyme nos ensaios A e C.

Use o [StillMotion-debug.apk](../build/debug/StillMotion-debug.apk) para o teste. Ele tem assinatura local de desenvolvimento. Copie-o para o aparelho, abra-o em Meus Arquivos e autorize instalação por essa origem se o Android solicitar. Abra StillMotion, escolha **Dunas ao entardecer**, deixe **Sempre estático** desmarcado, toque em **Selecionar papel de parede** e confirme para a **Tela inicial** se o seletor oferecer esse destino. O preview mostra a imagem final. Se o seletor não oferecer o componente, use **Salvar imagem estática** e aplique a imagem exportada pela Galeria como fallback. Registre essa limitação do firmware.

Se a animação não aparecer, volte ao StillMotion logo após reproduzir o problema, toque em **Diagnóstico do desbloqueio → Copiar** e compartilhe apenas o texto que você quiser enviar. O diagnóstico mostra o Android, eventos de tela e visibilidade e o motivo registrado quando o app decide manter o poster. A instalação 0.1.1 atualiza a 0.1.0 quando assinada com a mesma chave. Confirme o wallpaper dinâmico de novo após atualizar para registrar a engine desde a seleção.

Em **Configurações → Sobre o telefone → Informações de software**, anote modelo completo, Android, One UI e número da compilação. Anote RAM do modelo, taxa de atualização, brilho, modo de energia, temperatura ambiente e as configurações gráficas do jogo. Use a planilha CSV vazia [modelo-coleta.csv](modelo-coleta.csv). Não é necessário conectar o aparelho ao Codex nem fornecer PIN, conta ou capturas com notificações.

## 1. Comportamento e estabilidade

| Faça | Resultado esperado |
|---|---|
| Na home, bloqueie com o botão lateral. Aguarde, acorde e desbloqueie com a digital. Repita com PIN. | Na home pode haver um recuo breve, seguido da imagem final. Registre se ficou estático e qual método usou. A animação só ocorre quando os eventos exigidos chegam em ordem com a home visível. |
| Desbloqueie voltando diretamente para um app opaco. Volte à home. Em outro teste, abra um jogo assim que o movimento começar. | O papel de parede fica parado ao voltar na mesma sessão. Se conseguir ocultar durante a animação, a atividade gráfica deve ser interrompida assim que a engine receber invisibilidade. |
| Após o fim, aguarde 60 s e depois 10 min na home. Apague a tela por outra janela. | Nenhuma repetição espontânea, nenhum flash preto persistente ou animação em AOD/tela apagada. |
| Abra recentes, um app parcialmente transparente e faça trocas rápidas. Teste outra orientação se o launcher permitir. Abra a prévia e volte sem confirmar. | O poster reaparece inteiro quando a Surface é recriada. As ações só acionam a animação após um desbloqueio elegível. |
| Ative a Economia de energia do telefone. Em outro ensaio, ative **Sempre estático** no app. Troque de tema depois. | O poster funciona com ambos os modos estáticos. A troca de tema atualiza o poster. |
| Alterne as ações de bloqueio, desbloqueio, volta de jogo e recriação por pelo menos 100 ciclos. Use o telefone normalmente entre blocos. | Anote qualquer travamento, ANR, superfície vazia persistente, falha de seleção ou degradação acumulada. |

Faça ao menos cinco repetições dos cenários curtos. Para cronometrar, filme externamente quando puder, filtrando informações pessoais. A gravação da própria tela adiciona trabalho do encoder e altera a comparação. Conte quadros da filmagem entre desaparecimento da tela de bloqueio, primeiro movimento e poster final. Use **Rastreamento do sistema** nas opções do desenvolvedor se disponível no firmware. Capture separadamente da rodada de bateria, usando categorias de gráficos, agendamento e energia que estiverem disponíveis. [Guia Android de system tracing](https://developer.android.com/topic/performance/tracing)

## 2. Desempenho em jogo

Prepare **A**, poster da Galeria do sistema usando a imagem exportada de Dunas. Prepare **C**, StillMotion com Dunas e Economia extrema. Se tiver o Flyme original instalado de forma autorizada e funcionando, use **B** em uma comparação adicional, anotando que outra arte ou cor pode mudar o consumo no AMOLED. **C estático** com Sempre estático é um controle adicional que ajuda a estimar o custo da própria ligação do serviço.

Use a mesma cena salvável/offline, gráfico, limite de FPS, brilho, taxa de atualização, conectividade e condição térmica inicial. Compare uma abertura imediatamente no desbloqueio e outra depois que o poster estabilizou. Faça sessões de 10–30 min, alternando a ordem A/C e C/A, com tempo para retornar à condição térmica inicial. Registre FPS e frame times quando houver telemetria local. Guarde p50/p95/p99 de frame time, mediana e jank, com dados brutos e variação. Se só houver contador de FPS, registre isso como proxy. Uma queda mediana de FPS maior que 1% ou aumento p95 de frame time maior que 2% é um alerta se superar a variação normal.

## 3. Bateria, repouso e temperatura

1. Comece as rodadas em faixas de carga e temperatura comparáveis e desconecte o carregador. Fixe brilho, taxa de atualização, conectividade, AOD e modo de economia. Faça uma série sem economia do sistema para que C possa animar.
2. Compare A/C com tela apagada, home parada, desbloqueios repetidos e jogo. Faça janelas longas por cenário, iguais entre variantes, alternando a ordem em mais de um dia. Para desbloqueios, use a mesma quantidade e ritmo, separando digital e PIN se derem resultados diferentes.
3. Registre horário, percentuais inicial/final, temperatura disponível e interrupções. Guarde as telas/resumos de uso de bateria sem dados pessoais. Um empate em porcentagem mede apenas a resolução do indicador durante essa janela. Uso atribuído ao app não captura todo o painel, driver e compositor. Não converta porcentagem para mAh ou energia por transição sem instrumento e margem de erro.
4. Se houver trace, confira atividade da camada após finalizar, CPU do processo, compositor e eventuais filas após ocultar. Sem rail ou método energético confiável, use duração, FPS, temperatura e atividade como proxies e anote **inconclusivo** para equivalência energética. [FrameTimeline](https://perfetto.dev/docs/data-sources/frametimeline)

## 4. Diagnóstico opcional

O roteiro acima funciona só no telefone. Se você já usa ADB localmente e quiser observações mais precisas por conta própria, `bench/capture.sh` lê uma captura pontual da versão debug, sem limpar contadores ou alterar wallpaper. O build `probe` tem o mesmo identificador e oferece rastreio de callbacks com poster estático. Instale uma variante por vez. Revise os arquivos brutos antes de compartilhar, especialmente saída global de wallpaper e dados do aparelho. Os limiares de triagem do documento principal continuam exploratórios.

Mesmo com AOSP emulado, o Galaxy precisa validar ordem de callbacks, tela apagada e AOD, composição do último frame, ausência de novos submits após ocultar, latência de primeiro frame, competição com jogo, memória, drenagem p95, energia e temperatura. Comparações Canvas/GLES/Vulkan/decodificação de hardware e custo real do cache também dependem de hardware.
