# Direct 1.5.1 — otimização do terreno

Implementado sem reduzir resolução, distância, precisão dos vértices ou efeitos:
- Terreno opaco/recortado ordenado de perto para longe, permitindo rejeitar fragmentos ocultos mais cedo. Ordenação estável em empates; transparência mantém sua ordenação independente.
- Índices GPU de 16 bits para seções de até 65536 vértices; seções maiores conservam 32 bits. Reduz pela metade os bytes de índices na arena e no staging, sem alterar a entrada JNI ou geometria.
- ExecuteIndirect grava as duas listas sem acordar/esperar um worker. O paralelismo permanece disponível para desenhos individuais.
- Seleção Java de seções compatíveis executada uma vez após uploads, eliminando a varredura duplicada. O início do quadro também invalida a autorização anterior de suprimir draws vanilla.
- Coleta nativa reutiliza o vetor visível, elimina o vetor intermediário e evita ordenar quando a lista já está ordenada.

## Evidência

Benchmark real D3D12, Intel UHD 630, 1920×1080, 1024 seções, 256 quads por seção, 25 quadros de aquecimento e 100 amostras. Mesma geometria em três ordens de entrada; execução antigo/novo/novo/antigo. Dados em archive/terrain-1024-comparison.txt.

| Ordem de entrada | GPU p50 antiga (ms) | GPU p50 nova (ms) |
| --- | --- | --- |
| Próxima primeiro | 5,89–6,10 | 5,91–6,02 |
| Misturada | 8,48–8,81 | 5,79–5,92 |
| Distante primeiro | 26,74–28,55 | 5,70–5,76 |

A arena reservada caiu de 32 para 24 MiB nessa carga (25%). CPU e upload variaram; não houve evidência consistente de ganho nesses tempos. O ganho de GPU depende da ordem anterior e da sobreposição: uma cena já bem ordenada não mostrou ganho relevante. Estes números não são FPS do Minecraft nem representam sua cena real.

O último log do cliente ainda mostrava esperas DX12 elevadas; esta versão não certifica que todas foram eliminadas. Não foram removidas fences ou esperas de segurança. Uma nova tentativa isolada de compartilhar depth diretamente continuou expondo armazenamento de largura zero no driver e foi rejeitada; ficou fora do produto.

## Verificação

Passaram 36 testes unitários, 23 gráficos, validateLus (1) e smoke WARP (1/1), sem falhas ou testes ignorados. Build Java/C++ e Javadoc concluídos. Testes nas rotas compartilhada/copiada, Lus e smoke WARP. Os testes incluem o limite 65535/65536 dos índices, ExecuteIndirect e desenho individual, cor/profundidade, empates coplanares, transparência, materiais, resize e fallback. Relatório de instalação registra contagens e hashes.

Minecraft não foi iniciado pelo agente. Faltam validação visual, carregamento de chunks e comparação controlada de FPS/frametime no modpack. Raydiance e Lus não precisam de alteração para esta otimização; API pública permanece 1.4.0.
