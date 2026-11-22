# Direct 1.7.4 — otimizações gerais do jogo e empacotamento nativo

O Direct passa a incluir otimizações de lógica, memória e inicialização inspiradas no Lithium, no ModernFix e no FerriteCore (branches 1.21.1). Elas são independentes do renderizador de terreno e continuam ativas mesmo quando outro renderizador de terreno desliga os hooks do Direct. Foram reimplementadas a partir do comportamento do código 1.21.1/NeoForge 21.1.265, sem copiar código desses projetos.

## Configuração

`config/direct-optimizations.properties`, criado no primeiro início com todas as opções ligadas. Exige reiniciar o Minecraft: mixins aplicam no carregamento das classes, antes do `ModConfigSpec`. `enabled=false` desliga tudo. Cada opção é pulada automaticamente quando o mod em que foi baseada (ou um port dele) está instalado, para que o mesmo código nunca seja alterado duas vezes:

| Chave | Origem | Pulada se instalado |
| --- | --- | --- |
| `lithium.biome_temperature` | Lithium `world.temperature_cache` | lithium, canary, radium, modernfix |
| `lithium.entity_class_map` | Lithium `collections.entity_by_type` / `entity_filtering` | lithium, canary, radium |
| `lithium.entity_tracker` | Lithium `alloc.entity_tracker` | lithium, canary, radium |
| `modernfix.thread_priorities` | ModernFix `perf.thread_priorities` | modernfix, smoothboot, threadtweak |
| `modernfix.blockstate_cache_arrays` | ModernFix `perf.cache_blockstate_cache_arrays` | modernfix |
| `modernfix.empty_neighbour_tables` | ModernFix `perf.mojang_registry_size` | modernfix, ferritecore |
| `ferritecore.multipart_deduplication` | FerriteCore `cacheMultipartPredicates` + `multipartDeduplication` | ferritecore, modernfix |

O `latest.log` registra a decisão de cada opção e cada mixin aplicado (`Direct optimizations`). O relatório de diagnóstico (F7 > Diagnóstico) mostra, por opção, se está ligada e quantos mixins foram aplicados até o momento. Alguns só aplicam quando a classe-alvo carrega; o servidor integrado, por exemplo, só ao abrir um mundo.

A configuração de mixins é `required: false`. Se um mod alterar um alvo de forma incompatível, essa otimização falha e é registrada no log, sem impedir o jogo de iniciar.

## O que cada uma faz

- **Temperatura de bioma.** O vanilla memoriza `getHeightAdjustedTemperature` num mapa LRU de 1.024 entradas por bioma e por thread (`ThreadLocal`). A função é pura (clima do bioma, ruído de seed fixa, posição), então chamá-la diretamente dá o mesmo valor, sem consulta ao ThreadLocal, hash e manutenção do LRU. Os mapas também deixam de ser criados em cada thread de trabalho.
- **Mapa de entidades por classe** (`ClassInstanceMultiMap`, uma por seção de entidades). O `HashMap` de listas por classe vira um mapa de identidade, que é equivalente porque a igualdade de `Class` já é identidade. Na segunda consulta da mesma classe, a lista é devolvida sem repetir o `isAssignableFrom` e sem alocar o lambda do `computeIfAbsent`. A primeira consulta e classes inválidas seguem pelo método vanilla, com a mesma lista e a mesma exceção.
- **Rastreador de entidades.** O conjunto de conexões que veem cada entidade troca o `IdentityHashMap` (via `Sets.newIdentityHashSet`) por um `ReferenceOpenHashSet`: mesma semântica de identidade e ordem igualmente não especificada, iterado a cada broadcast.
- **Prioridade de threads.** Os workers do executor de fundo (malhas de chunks, geração, carregamento de recursos) e a thread do servidor integrado rodam com prioridade 4, uma abaixo do normal. Sob disputa de CPU, a thread de renderização é atendida primeiro; com núcleos livres, nada muda. Número de threads, nomes e ticks não mudam.
- **Arrays do cache de estados.** O construtor do cache de cada estado de bloco copiava `SupportType.values()` uma vez por direção e `Direction.Axis.values()` uma vez. Agora usa um array compartilhado, só lido pelos laços (efeito na carga do pack).
- **Tabelas de vizinhos vazias.** Estados sem propriedades guardavam um `HashBasedTable` vazio próprio. Agora compartilham `ImmutableTable.of()`: a consulta devolve o mesmo `null` e `setValue` lança a mesma exceção.
- **Deduplicação multipart.** Na 1.21.1, cada estado de um bloco multipart (cercas, muros, painéis, redstone) é assado como um `MultiPartBakedModel` próprio, com predicados recriados para cada seletor. Agora:
  1. Cada seletor guarda o predicado para a definição de estados do bloco.
  2. Durante o bake, modelos com a mesma lista de seletores (mesmos predicados, mesmos modelos de parte do cache de bake) são a mesma instância. A tabela existe só durante `bakeModels`, sem reter modelos entre recargas.
  3. Como um modelo compartilhado atende vários estados e é consultado em paralelo pelas threads de malha, os bits de seletor por estado vão para um cache copy-on-write lido sem lock. Eles são calculados como no vanilla (mesma ordem, mesmos índices), e o mapa vanilla não é mais usado.

  O log registra `Multipart models: N built, M shared`. A redução de memória depende do pack e não foi medida.

## Avaliadas e não incluídas

- Lithium `entity.fast_hand_swing`: a economia são três consultas a um `HashMap` vazio por entidade e tick, e o alvo provavelmente é modificado pelo BetterCombat e pelo player-animation-lib, presentes no pack. Um `@Redirect` em conflito impede o jogo de iniciar.
- Lithium `alloc.enum_values` (pistões e redstone): sem `@Redirect`, que poderia conflitar com os mixins de pistão do Quark, a alocação continua e o ganho some.
- Lithium `alloc.nbt`: muda a ordem de iteração das tags NBT e, portanto, a ordem dos bytes salvos.
- ModernFix `memoize_creative_tab_build`, `dynamic_resources` e `reduce_blockstate_cache_rebuilds`; FerriteCore `replaceNeighborLookup`, `replacePropertyMap`, `bakedQuadDeduplication` e `useSmallThreadingDetector`: invasivas (estruturas centrais de estado, `@Overwrite` ou carregamento sob demanda) e com riscos documentados pelos próprios autores. Exigiriam validação no pack que esta entrega não fez.

## C++

As otimizações acima são consultas a mapas e cálculos de nanossegundos, chamados um a um. Cruzar o JNI a cada chamada custaria mais que a própria operação, então elas ficam em Java.

O C++ foi aplicado onde há lote. A conversão das malhas capturadas para o vértice V2 de 20 bytes (`TerrainBufferPacker.packVertices20`) roda nas threads de chunks e processa milhares de vértices por camada, numa única chamada (`terrain_packer.cpp`). O resultado é idêntico byte a byte ao Java: arredondamento igual ao do `Math.round(float)`, `min`/`max` com NaN e −0,0 como no Java, `/fp:precise` e `fp_contract(off)` para não haver FMA. O Java continua como referência e alternativa. Controle: **Avançado > Empacotamento nativo** (`nativeVertexPacking`, desligado no perfil Compatibilidade).

Primeira versão: o `std::floor` em double vira chamada de biblioteca sob SSE2, e o ganho ficou em cerca de 10% (mais lento em camadas pequenas). Versão final: floor por truncamento com correção.

`TerrainPackerBenchmark`, uma thread, após aquecimento do JIT, ordem Java/nativo/nativo/Java, ns por vértice (p50):

| Vértices por camada | Java | Nativo | Nativo | Java |
| --- | --- | --- | --- | --- |
| 1.024 | 41,870 | 27,324 | 27,935 | 41,333 |
| 8.192 | 55,671 | 33,505 | 33,448 | 56,718 |
| 32.768 | 55,777 | 33,242 | 33,280 | 56,536 |

```
.\gradlew.bat --no-configuration-cache --init-script docs/terrain-benchmark.gradle terrainPackerBenchmark -PbenchmarkLibrary=<DLL absoluta> -PbenchmarkVertices=8192
```

Os dados são aleatórios, com materiais variados, e por isso penalizam mais a previsão de desvios que malhas reais. Mede só o conversor, não a compilação de chunks inteira nem o FPS.

## Validação

- `OptimizationMixinTargetsTest` lê com ASM o bytecode real do Minecraft 1.21.1/NeoForge do classpath de testes. Ele confirma que cada método, campo (com a finalidade esperada por `@Final`/`@Mutable`) e instrução (`ForkJoinPool.<init>`, `SupportType.values`, `Direction.Axis.values`, `NEW MultiPartBakedModel`) visados pelos mixins existe com o descritor exato.
- `OptimizationSettingsTest`: padrões, desligamento por opção e geral, mods de origem instalados, arquivo de padrões e consistência entre o JSON de mixins e a tabela de opções.
- `MultipartSharingTest`: deduplicação só durante o bake, ordem como parte da identidade, liberação da tabela, cache com chave nula e falha não memorizada. Inclui estresse com 8 threads e 2.000 chaves: cada chave é calculada uma única vez e nenhum leitor vê valor inconsistente.
- `NativeTerrainPackerTest`: 400 malhas aleatórias com NaN, infinitos, −0,0, empates de arredondamento, offsets de origem e destino diferentes de zero e materiais de água/planta idênticas byte a byte ao Java, mais a rejeição de intervalos inválidos.
- Suíte completa: 88 testes (`test` + `graphicsTest`) e smoke WARP 1/1, sem falhas.

Mixins não são aplicados em testes unitários. A aplicação real e a convivência com os 144 mods do pack (Create, Quark, Iron's Spellbooks, Cataclysm e outros) dependem de o usuário abrir o Minecraft: conferir no `latest.log` as linhas `Direct optimizations` e a contagem multipart, e comparar memória (F3 / Spark) e FPS na mesma cena com e sem `enabled=false`. Esta execução não abriu o jogo e não declara ganho de FPS ou de memória no pack.
