# Direct 1.5.2 — processamento de chunks

- Com captura DX12 inativa, a compilação vanilla passa diretamente pelo hook: não cria token de captura, não faz extração nem executa a finalização de captura. Uploads de VertexBuffer deixam de entrar no lock/mapas de propriedade do Direct. Reset de seção evita construir SectionPos para uma remoção inativa. Matrizes DX12 não são copiadas com o terreno desligado.
- Com DX12 ativo, a geração de índices sequenciais escreve um quad inteiro por iteração, eliminando divisão/resto por índice. Formato, ordem e offsets permanecem iguais; buffers customizados continuam no caminho original.
- Transparência DX12 reaproveita a ordenação dos quads quando a posição exata da câmera permanece igual. Movimento, mesmo pequeno, recalcula; trocar o mesh cria um cache novo. Rotacionar a câmera não altera distâncias dos quads e não exige nova ordenação interna.
- Distâncias são calculadas uma vez por quad quando necessárias. Índice original desempata distâncias iguais, preservando o resultado da ordenação estável anterior. Vetores de seções e distâncias são reutilizados. A ordem entre seções continua sendo atualizada e os índices continuam enviados pelo ring existente.

Não reduz qualidade, resolução, distância ou quantidade de chunks. Não altera o compilador vanilla, não oculta erros e não promete acelerar todo o Minecraft com DX12 desligado: nesse modo, remove custos introduzidos pelos hooks do Direct. Capturas em andamento ainda fecham seus tokens e mantêm as verificações de geração/propriedade. API permanece 1.4.0; Raydiance e Lus não foram alterados.

## Custo e medição

O cache mantém aproximadamente 4 bytes por quad translúcido mais metadados por seção. Um vetor de double por maior seção processada é reutilizado para distâncias; não há cópia adicional de textura ou alvo GPU.

Benchmark CPU de 1000 gerações de 4096 quads por rodada, após três rodadas de aquecimento: mediana de 46,676 ms no algoritmo anterior e 14,404 ms no novo. Uma rodada nova levou 53,533 ms, portanto não há promessa de tempo constante. Dados completos em archive/chunk-indices-1.5.2.txt; reprodução: .\gradlew.bat --init-script docs/terrain-benchmark.gradle chunkIndexBenchmark.

Essa medição cobre somente geração de índices, não compilação completa, upload, GPU ou FPS. O cache elimina deterministicamente a reordenação por quad em câmera parada, mas não foi certificado um percentual de ganho no cliente real.

## Validação

Build Java/C++, Javadoc, unitários, gráficos, Lus e smoke WARP executados; contagens e hashes no registro archive/installation-1.5.2.json. Testes gráficos verificam reuso com câmera parada, inversão da ordem após mudar a câmera de lado e invalidação ao substituir o mesh, nas duas rotas de interop. Permanecem testes de índices, limites 16/32 bits, materiais, água, sombras, resize e fallback.

Minecraft não foi iniciado pelo agente. Ainda dependem do jogo: carregamento/movimento de chunks no modpack, alternância DX12, transparência com outros mods, frametimes e FPS comparáveis.
