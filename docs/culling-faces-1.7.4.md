# Direct 1.7.4 — culling de faces por direção

## Técnica

O Sodium agrupa os quads de cada seção pela direção da face (*block face culling*). A cada frame, ele deixa de desenhar os grupos que estão inteiramente de costas para a câmera. Sem isso, esses quads passam pelo input assembler e pelo vertex shader e só depois são descartados pelo rasterizador, que já usa `CULL_BACK` no Direct. A técnica foi reimplementada em C++ no backend nativo, sem copiar código do Sodium.

**Upload** (`partitionFaceGroups`). Cada quad das camadas solid, cutoutMipped e cutout é classificado pela normal geométrica exata dos seus dois triângulos. O cálculo usa produto vetorial inteiro sobre as posições quantizadas, sem tolerância. O atributo de normal não é usado, porque modelos podem declarar uma direção diferente da geometria. Quads fora de eixo, não planares ou com triângulos discordantes ficam no grupo "sem eixo", que é sempre desenhado. A partição é estável, então a ordem relativa dentro de cada grupo e os empates coplanares do vanilla são preservados. Cada grupo guarda o intervalo de planos que seus quads ocupam. A transparência não é reordenada, porque mantém a ordenação própria de trás para frente.

**Por frame** (`collectVisibleSections`). A orientação é decidida pelo sinal do determinante (x, y, w) do triângulo projetado (rasterização homogênea 2D, Olano & Greer 1997). Esse critério vale para qualquer matriz projetiva: perspectiva, ortográfica, espelhada e as matrizes identidade dos testes. Para uma família de planos paralelos, o determinante é afim na posição do plano. Por isso basta avaliar os dois extremos do grupo, com folga de 1/32 de bloco. O resultado é exato para geometria estática e entra no cache de seleção já existente: câmera parada não recalcula nada.

**Animação.** Vento e ondas movem vértices no vertex shader e podem mudar a orientação. Uma camada que contém folhas, plantas ou superfície de água animada nunca sofre culling enquanto o efeito correspondente está ativo.

**Draws.** Na primeira versão, cada seção passou de 1 para cerca de 2 draws. Ela foi descartada (medição abaixo). Na versão final, os grupos seguem a ordem +X, +Z, sem eixo, +Y, −Y, −Z, −X. Nas posições de câmera comuns, os grupos de costas ficam nas pontas da camada, e cortá-los não cria draw. Um grupo de costas entre dois visíveis só vira um draw separado acima de 256 quads; abaixo disso é enviado junto e descartado pelo rasterizador, como antes. Os passes de sombra (sem culling, luz do sol) e de transparência continuam desenhando as camadas inteiras.

**Controle.** **Avançado > Culling de faces** (`faceCulling`, ligado por padrão, desligado no perfil Compatibilidade). A imagem não muda.

## Medição

Benchmark sintético `FaceCullingBenchmark`, Intel UHD 630, 1920×1080, `ExecuteIndirect`, alias de cor. O relevo imita a saída do vanilla após a remoção de faces ocultas: topos e laterais expostas entre colunas de alturas diferentes, sem fundos. Câmera em perspectiva de 70° sobre o centro, com frustum culling nativo, 30 frames de aquecimento e 120 amostras. A ordem ligado, desligado, desligado, ligado no mesmo processo expõe a deriva entre blocos.

```
.\gradlew.bat --no-configuration-cache --init-script docs/terrain-benchmark.gradle faceCullingBenchmark -PbenchmarkLibrary=<DLL absoluta> -PbenchmarkGrid=32 -PbenchmarkCameraHeight=12
```

Primeira versão, sem ordem otimizada nem mescla de lacunas. Os índices caíram cerca de 20%, os draws dobraram e o GPU p50 não melhorou:

| Cena | Culling | Índices enviados | Draws | GPU p50 (ms) |
| --- | --- | --- | --- | --- |
| 16×16 seções, y=30 | lig./desl./desl./lig. | 0,788 / 1 / 1 / 0,788 | 178 / 89 / 89 / 178 | 2,6475 / 2,6093 / 2,5947 / 2,6272 |
| 32×32 seções, y=30 | lig./desl./desl./lig. | 0,807 / 1 / 1 / 0,807 | 640 / 320 / 320 / 640 | 3,2606 / 2,9431 / 2,9380 / 2,6923 |

Versão final. Os draws são iguais nos dois modos, e 17,5–18,6% dos índices opacos deixam de ser enviados:

| Cena | Índices enviados | Draws | GPU p50 lig./desl./desl./lig. (ms) | GPU p95 (ms) |
| --- | --- | --- | --- | --- |
| 16×16, y=30 (107 621 quads) | 0,814 | 89 | 2,4569 / 2,5036 / 2,4616 / 2,4239 | 2,7222 / 2,7526 / 2,7802 / 2,7703 |
| 32×32, y=30 (430 683 quads) | 0,825 | 320 | 3,1701 / 3,0043 / 2,9738 / 2,6787 | 3,6437 / 3,3927 / 3,4844 / 2,9004 |
| 32×32, y=12 | 0,824 | 313 | 3,0112 / 3,3512 / 3,0140 / 2,6202 | 3,4282 / 3,7378 / 3,5896 / 2,9559 |

As médias por modo favorecem o culling (2,44 vs 2,48; 2,92 vs 2,99; 2,82 vs 3,18 ms), mas a variação entre blocos é do mesmo tamanho. Nesta GPU, a cena é limitada por preenchimento de pixels, e o trabalho de vértices é uma parte pequena do total. O resultado mostra redução de trabalho sem regressão de draws. Não comprova ganho estável de tempo de GPU e não deve ser convertido em FPS do Minecraft. Cenas com mais faces laterais (montanhas, cavernas, construções) e GPUs limitadas por vértices tendem a se beneficiar mais. Isso continua sem medição.

Com `profileRenderer`, o `latest.log` registra por minuto a linha `Direct face culling: ... submittedOpaqueCutoutIndexFraction=...`, para medir a fração no mundo real.

## Outras referências consultadas

- Sodium (https://github.com/CaffeineMC/sodium): o culling de faces foi aplicado. O agrupamento por regiões com multi-draw não foi: o Direct já usa `ExecuteIndirect` por seção, e trocar o modelo de alocação da arena é uma mudança maior, que exigiria outra medição.
- Lithium (https://github.com/CaffeineMC/lithium): otimiza lógica do jogo e do servidor (ticks, colisões, IA), não o renderizador de terreno. O princípio de evitar trabalho e alocações repetidos já guia as entregas 1.5.3, 1.6.1 e 1.7.3.
- ModernFix (https://github.com/embeddedt/ModernFix): foca em inicialização e memória. A ideia análoga para o Direct é não refazer trabalho caro a cada ativação, implementada nesta mesma versão com o cache de shaders e da pipeline library ([ativação 1.7.4](ativacao-1.7.4.md)).

## Validação

Teste gráfico `faceCullingSkipsBackFacingGroupsWithoutChangingTheImage`, nos caminhos de cor copiada e alias: um cubo em perspectiva tem 3 de 6 faces enviadas (18/36 índices). Cor e profundidade são idênticas pixel a pixel às do caminho sem culling, com `ExecuteIndirect` e com desenho direto. Uma camada com vento ativo não sofre culling, e volta a sofrer quando o vento é desligado. Suíte completa: 71 testes (`test` + `graphicsTest`) e smoke WARP 1/1, sem falhas. Os testes existentes de culling por winding, empates coplanares, recortes e limites de índices de 16/32 bits continuam passando com a reordenação.

Esta execução não abriu o Minecraft. Faltam a confirmação visual no modpack (blocos de outros mods, modelos com faces fora de eixo, vento/ondas, câmera em terceira pessoa) e a comparação de FPS/p95/p99 na mesma cena, com o cliente aberto pelo usuário.
