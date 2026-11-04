# Medição de desempenho DX12 Terrain — em andamento

Direct 1.2.2 adiciona FPS médio, frametime médio, p50/p95/p99 recentes e máximo,
com amostra limitada a 16384 intervalos. FPS/média/máximo usam a janela inteira;
percentis usam as últimas amostras. O número de amostras é explícito no relatório.

O profiler separa espera D3D12, espera GLsync, submissão, composição e upload de
chunks; registra bytes/seções enviados, fila de chunks, arena GPU reservada,
ring de upload reservado, desenhos nativos e camadas vanilla puladas/desenhadas.
GPU time abrange terreno, passes de extensão e cópia; não representa toda a GPU
ocupada pelo Minecraft. Esperas são subconjuntos de submissão/composição: não somar.
Capturas em threads de compilação ainda não têm medição individual por tarefa.

Emite uma linha por minuto em logs/direct-performance.csv, além do latest.log.
Não há escrita em disco por frame. Alterar o modo vanilla/replacement/overlay
inicia outra janela. Testes devem usar mesma câmera, resolução, distância,
VSync, limite de FPS e estado do Lus. Aguardar estabilização do carregamento.
A janela durante pausa, menus, alternância de janela ou carregamento não é
comparável com jogo estável e deve ser identificada como tal, sem apagar picos.

Regressão corrigida: timestamps antigos não são válidos para slots produzidos
com profiling desligado. O teste nativo alterna profiling e verifica isso.

Benchmark CPU reproduzível:
  .\gradlew.bat --init-script docs/terrain-benchmark.gradle terrainLookupBenchmark
4096 chaves, 2000 repetições, 9 rodadas após aquecimento:
HashSet<Long> mediana 3.844 ns/consulta; LongOpenHashSet 3.754 ns/consulta.
Diferença pequena, sem evidência de efeito material em FPS; troca NÃO aplicada.
Não interpretar microbenchmark como desempenho no Minecraft.

Esta versão prepara o diagnóstico; ainda não foi medido ganho de FPS no modpack.
O próximo teste será feito pelo usuário abrindo o Minecraft, sem lançamento automático.
Configuração anterior e JAR anterior preservados em archive/ para restaurar ao final.

## Direct 1.2.4 — profundidade e esperas descartadas

O quadro que perdeu validade por câmera/visibilidade/chunks é descartado antes
da espera DX12 que seria desnecessária. A conclusão de consumo é sinalizada
sem leitura GL, preservando a ordem da fila e o fallback vanilla. A validade dos
meshes é conferida novamente depois da espera para detectar conclusões assíncronas.

O terreno escreve agora um único alvo de cor. O depth buffer real usa recurso
R32_TYPELESS com DSV D32_FLOAT; a ponte 11On12 copia a profundidade para a imagem
R32_FLOAT do interop. Não há mais alvo R32 de exportação escrito pelo pixel shader,
nem sua limpeza. Estados DEPTH_WRITE são restaurados na liberação do wrapper.
Economia determinística: width*height*4 bytes de alocação (8294400 bytes em 1080p),
um alvo de saída e sua escrita por fragmento. Formato público e shaders HLSL intactos.

Teste real GPU/WGL verificou profundidade/cor, recortes alfa, culling, resize,
composição GL, HLSL Lus e descarte sem espera seguido de outra submissão correta.
Build, testes Java/gráficos e smoke nativo passaram. Não foi executado Minecraft.

Benchmark isolado NativeDepthCostBenchmark, Intel UHD 630, 1920x1080,
16 quads sobrepostos, 30 aquecimentos + 120 amostras:
- Ordem antigo/novo: p50 18.8670 / 7.5849 ms; p95 19.6143 / 10.2230 ms.
- Ordem novo/antigo: p50 9.9220 / 16.8998 ms; p95 10.3004 / 18.9637 ms.
A carga artificial enfatiza sobreposição e largura de banda. Há variação entre
execuções; não extrapolar esses números para o FPS no jogo. A alteração reduz o
trabalho que antecede a composição; não implementa pipeline de frames atrasados.
Referência do usuário continua 60 FPS sem DX12 e 50 com Direct 1.2.3.

Reprodução: docs/terrain-benchmark.gradle, tarefa nativeDepthCostBenchmark,
-PbenchmarkLibrary=<caminho absoluto da DLL que será medida>. O artefato anterior
usado foi extraído do JAR 1.2.3 realmente instalado, não recompilado com código novo.

Contrato de wrapping e cópia consultado:
https://learn.microsoft.com/en-us/windows/win32/api/d3d11on12/nf-d3d11on12-id3d11on12device-createwrappedresource
https://microsoft.github.io/DirectX-Specs/d3d/archive/D3D11_3_FunctionalSpec.htm

## Direct 1.4.0 — custos da migração de geometria

Preserva as reduções de trabalho medidas na 1.2.4. Esta ampliação não estabelece
um novo ganho de FPS. V2 acrescenta 4 bytes por vértice (+25% de vértices), normais
e materiais; transparência usa centros/índices CPU e o ring existente para ordenação.
Sombras são um passe opt-in; vento é trabalho de vértice, sem pós-passagem.
Cor/profundidade de refração são snapshots separados, alocados sob demanda.

A travessia adicional GL para DX12 e de volta só é solicitada por água/reflexos ou
pela opção dx12Translucency. Camada vazia e orçamento excedido são detectados antes
da captura. Orçamento insuficiente preserva a transparência vanilla sem reiniciar
opaco DX12. Effects/integração desligados liberam pedidos; recursos são liberados
quando os valores zeram e ao sair do mundo, inclusive sem outro hook renderLevel.

Além de direct-performance.csv, profileRenderer escreve por minuto
logs/direct-translucency-performance.csv. captureMs/sortSubmitMs/waitMs/presentMs
são por frame do mundo; GPU/draws são por frame translúcido nativo. GPU translúcida
abrange snapshots, geometria e exportação, excluindo a importação 11On12 anterior
ao marcador. A captura inclui sua conclusão GL. Espera CPU não deve ser somada ao
tempo GPU como se representassem trabalhos independentes. O tempo GPU de terreno
agora inclui o mapa de sombras quando ativo, e seus draw calls também são contados.

Teste de importação direta em Intel UHD 630: recurso D3D12 compartilhado real,
size=0, enum correto, rejeitado com GL_INVALID_ENUM. Fence também rejeitada.
A alternativa não passou na validação funcional; não foi adotada nem foi inventada
uma medição comparativa. A causa interna do driver permanece sem determinação.

Comparação pendente no modpack: mesmo cenário/câmera/resolução/distância e configurações,
60s estáveis por modo; registrar água/reflexos/sombras individualmente e p95/p99,
sem incluir menus/carregamento como se fossem uma sessão estável. Referência do usuário:
60 FPS desligado / 50 ligado. O cliente deve ser aberto pelo usuário.

### Verificação adicional sem efeitos (2026-11-03)

Uma primeira rodada sinalizou possível regressão: 1.3.0 p50 9.7857/8.9846 ms,
1.4.0 inicial 10.4090/10.0071 ms, na ordem antigo/novo/novo/antigo.
Foi adicionado um pipeline especializado para opaco/recortes sem sombras/vento.
Ele é criado uma vez, selecionado por frame e não recompila ao mover controles.
Mantém o layout V2, mas não busca os atributos novos nem executa essas rotinas.
Água/reflexos continuam no pipeline translúcido próprio, sem encarecer o shader opaco.

Comparação do compilador FXC /O3, shader original extraído da DLL 1.3.0 realmente
instalada: VS rápido e PS rápido têm bytecode idêntico ao anterior, por SHA-256.
VS: 25 slots de instrução contra 66 da variante de efeitos; PS: 30 contra 68.
Isso verifica remoção de trabalho estático, não uma proporção de tempo de GPU:
branches e o driver determinam o trabalho efetivamente executado. Resultados em
build/shader-comparison/results.json; fontes/bytecode/assembly no mesmo diretório.

Repetição na ordem rápido/antigo/antigo/rápido, mesmo teste 1080p/16 quads:
- 1.4 rápido: p50 9.9837, p95 10.3221 ms.
- 1.3: p50 8.5677, p95 10.0160 ms.
- 1.3: p50 8.9165, p95 9.9560 ms.
- 1.4 rápido: p50 7.7772, p95 10.1601 ms.
A variação é grande: a suspeita inicial não ficou confirmada como uma regressão
estável; estes dados também não provam ganho. Não converter em estimativa de FPS
no Minecraft. Logs originais em archive/benchmark-1.4.0*.txt. Reproduzir passando
benchmarkJar e benchmarkLibrary correspondentes à mesma versão para manter a ABI.
