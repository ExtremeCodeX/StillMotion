# 001 · Um único quad como primeira hipótese

O primeiro efeito próprio usa a mesma imagem opaca para simular um recuo curto de câmera. Canvas HW pode reutilizar a textura durante o intervalo e apresentar o poster pelo mesmo produtor no fim. A escolha para a prova funcional vem do requisito visual e da simplicidade de ownership. O ranking de energia entre alternativas depende de hardware.

| Alternativa | Vantagem e custo para comparação |
|---|---|
| Canvas de hardware | Um bitmap, center crop e transformação. RenderThread/driver podem reter caches. A sincronização e os bloqueios de Canvas precisam de trace no A15. |
| Canvas de software | Resolve o poster estático quando o lock de hardware falha. Atualizar cada frame via CPU seria um candidato de maior tráfego de memória, ainda sem medição no aparelho. |
| GLES/EGL | Um quad e shader mínimo permitem controle de formato, tamanho e filas. Inicialização/recriação de contexto, memória e retenção do último buffer devem entrar na conta. |
| Vulkan | Controle explícito de buffers e sincronização. Swapchain, cold start, driver, reapresentação e erro de superfície trazem trabalho extra para um quad. Exige comparação em igualdade visual para justificar. |
| AVC/HEVC por hardware | Forte candidato para efeito fixo cheio de camadas. Mede-se seleção do decoder de hardware, formato, saída em Surface, PTS, EOS, teardown e poster após liberar. Para zoom de um bitmap adiciona decoder e filas. HEVC 10-bit/90 fps do original é uma variante própria de referência, não um baseline econômico. |
| Sequência ou atlas | Pode ser adequado quando houver transparência ou pouquíssimos quadros. Evite decodificação de dezenas de bitmaps e compare qualidade em movimento. |

Gates físicos em ordem: seleção e último frame estáveis, nenhum trabalho recorrente em repouso, cancelamento em app opaco, depois energia por animação a qualidade equivalente. Fixar resolução efetiva, enquadramento, trajetória, cor, número e timing dos frames. Medir primeiro frame, pico de memória e cancelamento. Em seguida testar perfis econômicos com FPS/resolução modificados e registrá-los como outra comparação.

O núcleo Rust decide estados e regras e valida entradas. A Java chama a API nativa de desenho que o framework já expõe. A economia comprovada por enquanto é de tamanho de binário com `no_std` e LTO. Benefício de startup, CPU, RAM e bateria continua pendente.
