# Captura das 23 cenas do Flyme

A versão 0.3.1 lê o pacote dos quatro vídeos e aceita os temas procedurais em pacotes por família ou em um pacote com os 23 temas. O teste de host cobre a estrutura dos pacotes e rejeita pacotes incompletos, nomes inseguros e combinações parciais de famílias. As amostras sintéticas em `analysis/` servem para testar o formato.

O [catálogo](../assets/flyme-scenes.tsv) enumera os 23 serviços do APK. Os cinco Bubble foram capturados com o APK original intacto, em um AVD Android 9 isolado e sem rede. O AVD usou um núcleo e resolução de 360×780. A imagem exigiu 1,5 GiB de RAM configurada e o processo chegou a aproximadamente 1,5 GiB de RSS no computador, com apenas um AVD em execução.

O Android 9 rejeitou a versão GLSL ES 3.20 exigida por Cosmic. Um segundo teste, com Android 15, um núcleo e 1 GiB de RAM configurada, manteve um monitor com corte em 1,6 GiB de RSS. A imagem reportou ausência do pacote ANGLE e Cosmic falhou na inicialização do EGL. O teste de Passion, de Cosmic Phoenix, com o driver padrão, produziu uma gravação preta. As duas famílias continuam pendentes.

Para capturar, a Activity própria em `bench/capture-host` deixa a superfície do wallpaper visível em tela cheia. A prévia/seletor padrão aplica o serviço original. O utilitário registra o desbloqueio no emulador e grava MP4 bruto. Antes de empacotar cada tema, verifique o movimento e os quadros inicial e final. Recorte os quadros de lock screen que tenham relógio, botões ou outros elementos externos à animação. A cadência do emulador sem virtualização pode ser inferior à do aparelho. Uma captura com quadros perdidos ou efeitos ausentes precisa ser descartada.

```bash
bench/build-capture-host.sh
scripts/select-flyme-scene.py emulator-5562 bubble_v1
scripts/record-flyme-scene.py emulator-5562 bubble_v1 analysis/capturas/bubble_v1.mp4
scripts/prepare-flyme-scenes.py analysis/capturas Flyme-Bubble.stillmotion.zip --family bubble --cuts analysis/cortes.json
```

Os cortes informam `[início, fim]` em segundos, com um terceiro valor opcional para o número de pixels a cortar do topo. Um ZIP de família exige o conjunto completo de vídeos e imagens finais daquela família. O gerador converte para AVC sem áudio e usa um poster do último quadro. O StillMotion toca a sequência capturada uma vez no desbloqueio.
