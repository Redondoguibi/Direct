# Direct 1.6.1 / Raydiance 1.5.3 — caminhos de renderização

Esta revisão implementa redução de trabalho no renderizador e nos passes dos shaderpacks. Não foi executado Minecraft, benchmark ou teste. Compilar e empacotar não comprova ganho de FPS nem estabilidade final.

## Direct

- Uploads de malhas obedecem também ao orçamento flexível `maxMeshUploadMicros` (2000 µs por frame por padrão; zero desativa). Uma primeira seção sempre pode avançar; seus custos não são interrompidos no meio. Os limites existentes em bytes/seções continuam válidos. Isso pode aumentar o tempo para preencher a distância de terreno durante carregamentos intensos.
- A espera bloqueante de até cinco segundos pela imagem compartilhada OpenGL foi substituída por consulta sem espera. Enquanto o consumidor GL não termina, esse frame permanece vanilla; o watchdog ainda rejeita cinco segundos sem progresso. A imagem em uso não é sobrescrita. A espera pela produção DX12 do frame atual continua necessária.
- Propriedade das seções usa mapas de chaves long primitivas. A seleção dos casters é um snapshot invalidado em upload, commit, remoção e limpeza, eliminando a reconstrução e o envio JNI do conjunto de sombras quando nada mudou.
- A ordenação opaca usa sort sem buffer temporário e desempate pela posição original. A ordem é reutilizada apenas com câmera, matriz, distância, modo e entradas idênticos; offsets também são conferidos. Movimento e alterações de geometria invalidam o reaproveitamento.
- Listas de terreno e extensão HLSL são enviadas juntas e na mesma ordem. Não foram removidas barreiras nem sincronizações entre APIs.
- Arrays de parâmetros de efeitos são reutilizados em vez de alocados por frame.

## Raydiance

- Uniforms escalares, vetoriais, matrizes e índices de samplers só são reenviados quando mudam. O cache pertence ao programa e é destruído/recriado com ele; bindings de texturas continuam explícitos porque pertencem ao contexto GL.
- Frames neutros deixam de capturar/restaurar todo o estado GL a cada frame depois de carregar o programa.
- Packs format-2 não são obrigados a executar um compositor básico neutro antes dos seus passes. Filtros/efeitos ativos e composites format-1 preservam sua execução.
- O último passe elegível escreve diretamente em uma vista somente de cor do alvo do mundo, poupando um intermediário e o blit final. Requer saída RGBA8, escala 1, entrada diferente do alvo e ausência de leitura de OriginalScene. Saídas HDR, resolução diferente e dependências da cena original preservam o caminho anterior. Profundidade não fica anexada ao FBO de saída enquanto é amostrada.
- O fallback de BloomTex usa a entrada do passe, evitando vincular a saída como textura de leitura na ausência de bloom.
- O shader básico evita ler profundidade e reconstruir posição por pixel quando sombras, SSAO e atmosfera estão desligados. Packs customizados mantêm seu próprio código.

Os efeitos e a resolução configurada são preservados. Isso reduz custos de envio, estado e passes redundantes; não torna automaticamente os cálculos de um shader arbitrário mais baratos.

## Referências e limites

O estudo usou as fontes públicas como referência de técnicas, sem copiar implementações:

- Sodium: filas de chunks, reutilização de armazenamento e orçamento de uploads: https://github.com/CaffeineMC/sodium/blob/dev/common/src/main/java/net/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager.java
- ImmediatelyFast: agrupamento de comandos e redução de operações redundantes de framebuffer: https://github.com/RaphiMC/ImmediatelyFast
- Lithium: redução de trabalho repetido e alocações nos caminhos frequentes: https://github.com/CaffeineMC/lithium

O Direct continua capturando malhas vanilla e fazendo interop entre OpenGL e DX12. Não foi implementado um substituto completo do compilador de chunks do Sodium nem uma portabilidade geral das otimizações de simulação do Lithium. Sombras nativas, água e a composição atual ainda têm custos de sincronização e cópia. A magnitude do ganho depende de qual desses custos domina a cena.

Artefatos: `build/libs/direct-1.6.1.jar` no projeto principal `C:\Criando Mods\mod\Direct` e `Direct Group\Raydiance\build\libs\raydiance-1.5.3.jar`. Sem instalação automática na instância. Validação funcional, visual e de desempenho permanece pendente por solicitação de adiar testes.

## Evidência existente da instância

Na inspeção desta entrega, a instância CurseForge ainda continha `direct-1.5.3.jar`, com `enableDx12Preview=true` e `replaceVanillaTerrain=false`. As últimas linhas do CSV estavam identificadas como `overlay-debug`, com zero camadas vanilla suprimidas. Esse modo mantém trabalho de diagnóstico e não representa a substituição otimizada. A configuração foi preservada. Para usar a substituição após instalar, selecionar Direct DX12 e salvar, em vez de sobreposição de diagnóstico.

A primeira amostra histórica de `replacement` no CSV, em 2026-11-03T01:07:32Z, registra 8,499 ms/frame de espera DX12 e 10,766 ms/frame na composição. São registros antigos de outra revisão/cena, sem comparação controlada; servem como evidência de custo da ponte, não como resultado desta versão. A espera pela imagem atual ainda existe e limita o ganho possível apenas com melhorias de CPU/agrupamento.
