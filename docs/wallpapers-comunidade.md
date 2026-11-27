# Wallpapers da comunidade

O StillMotion instala pacotes criados pelo usuário. O conteúdo pode vir de uma filmagem, animação 2D ou 3D, renderização de shader, editor de vídeo ou sequência de imagens. A edição e a renderização do conteúdo são livres. O pacote descreve um evento de reprodução e traz o vídeo pronto.

## Importar e visualizar

1. Abra o StillMotion e toque em **Importar pacote de wallpapers** para selecionar o ZIP.
2. Escolha o tema na galeria e compare as prévias de bloqueio e início. A seleção na galeria é local à interface.
3. Toque em **Aplicar wallpaper** para abrir o seletor do Android. Inclua a tela de bloqueio para temas que animam ao acender. Inclua a tela inicial para temas que animam ao desbloquear.

A criação de pacotes compatíveis acontece no computador. O app copia os vídeos para a biblioteca privada e extrai as imagens de prévia antes de publicar o tema.

### Converter no computador

O script aceita entradas de vídeo e arquivos animados aceitos pelo FFmpeg, recorta o trecho desejado, ajusta o enquadramento e produz o ZIP. Requer Python 3 e FFmpeg com libx264.

```bash
python3 scripts/prepare-community-wallpaper.py minha-animacao.mp4 meu-tema.stillmotion.zip \
  --title 'Minha animação' --trigger unlock --start 0 --duration 2 --fps 30 --size 720
```

Use `--trigger wake` para lidar com o acendimento da tela. `--size` escolhe a largura de 360, 720 ou 1080 pixels. A altura acompanha a proporção vertical de 6:13. O recorte do vídeo de entrada é centralizado. Controle o ponto de interesse ou adicione margens no seu editor caso queira outro enquadramento.

Para uma sequência em duas etapas, adicione `--after fim.mp4 --after-duration 3` ao comando de desbloqueio. O segundo trecho começa após o primeiro terminar e repete enquanto o wallpaper estiver visível. Alinhe o primeiro quadro do segundo clipe com o último quadro do primeiro para evitar um salto visual. O Android ainda precisa trocar o decodificador entre os clipes.

## Formato v1

O ZIP contém exatamente dois arquivos na raiz. `theme.txt` é UTF-8 com três linhas, mais uma quebra final opcional. A primeira é `stillmotion-v1`, a segunda é `unlock` ou `wake`, e a terceira é o nome com até 60 caracteres. `animation.mp4` traz uma faixa de vídeo AVC/H.264 sem áudio, em 360×780, 720×1560 ou 1080×2340, de 0,25 a 14 s, até 30 fps e 32 MiB. Use pixels quadrados e aplique a rotação ao vídeo antes de exportar. Um pacote equivale a um tema e a um evento. A biblioteca aceita até 32 temas da comunidade.

O app verifica os nomes e tamanhos descomprimidos dos dois arquivos antes de instalar, grava temporários na pasta privada e publica o tema após criar os posters. O pacote carrega dados e vídeo. Scripts, shaders e binários podem ser usados como ferramentas de autoria no computador e ficam fora do ZIP.

## Formato v2

A primeira linha de `theme.txt` é `stillmotion-v2`, a segunda é `unlock` e a terceira continua sendo o nome. O ZIP inclui `animation.mp4` e `after.mp4`. Cada clipe obedece aos limites de formato da v1. A duração combinada é de até 14 segundos e o tamanho combinado é de até 32 MiB. O StillMotion abre um decodificador de cada vez e usa o último quadro do segundo clipe como imagem estática. Ao voltar à home, o clipe pós-desbloqueio reinicia do começo. Ele pode ser desativado aplicando um tema sem segundo clipe ou ativando **Sempre estático**.

## Reprodução e validação

A reprodução tem as mesmas condições de visibilidade, tela ligada e economia de energia dos temas incorporados. Usa um decodificador declarado como acelerado por hardware, ligado diretamente à superfície. O clipe principal toca uma vez por evento. O clipe pós-desbloqueio repete somente enquanto visível. O app libera o decoder ao ocultar e mantém uma imagem nos modos estáticos. Um dispositivo sem decoder adequado usa o poster. As animações dependem das flags de destino disponíveis no Android 14 ou superior e da ordem de eventos entregue pelo aparelho. Faça a comparação no Galaxy com clipes equivalentes. Duração, resolução e conteúdo do vídeo afetam o custo, então os resultados dos temas Órbita não se transferem automaticamente à biblioteca da comunidade.

Compartilhe no GitHub apenas temas e mídias que você pode distribuir. Os APKs e vídeos de referência da pasta local ficam excluídos pelo `.gitignore`.
