# Direct 1.9.1 — menos sincronização na transparência e terreno misto mais barato

## Evidência no jogo antes da mudança

Sessão de 21/11/2026 (19:29–19:33, instância Direct), Direct 1.9.0 + Raydiance 1.9.0 + Solaris, Intel UHD 630, 1920×1080, distância 12, arena de terreno de 128 MiB. Depois de explorar, a arena encheu:

| Janela | FPS | Quadros mistos | Seções vanilla por quadro | Rejeições da arena (acumuladas) |
| --- | ---: | ---: | ---: | ---: |
| 19:30 | 14,8 | 50 de 889 | 0,4 | 0 |
| 19:31 | 17,7 | 947 de 1064 | 188 | 1273 |
| 19:32 | 19,2 | 1148 de 1150 | 289 | 2691 |

Quase todo quadro passou a misturar DX12 e OpenGL. Três desperdícios apareceram nesse caminho:

- o vanilla ainda fazia o bind do VAO e o upload de `CHUNK_OFFSET` de cada seção que o DX12 já tinha desenhado, só para descartar o desenho depois;
- a mesma seção era reconstruída e rejeitada sem parar: o Direct pedia o rebuild de novo, mesmo quando o mesh não cabia em nenhum espaço livre da arena;
- os uploads rejeitados não contavam no orçamento por quadro.

Fora da arena cheia, com o Solaris e transparência DX12, cada quadro fazia dois locks WGL nas mesmas imagens: um para apresentar a transparência e outro, logo depois, para a captura do mundo. Na UHD 630 cada lock é uma transferência síncrona de cerca de 2 ms ([desempenho-1.9.0.md](desempenho-1.9.0.md)).

## Mudanças

1. **Seções nativas filtradas antes do OpenGL.** Numa camada mista, o percurso vanilla de `renderSectionLayer` trata as seções desenhadas pelo DX12 como vazias (`CompiledSection.isEmpty`). Assim o vanilla não envia `CHUNK_OFFSET` nem faz o bind delas. As seções que continuam no vanilla são desenhadas como antes.
2. **Lock da transparência mantido até a captura do mundo.** Quando um pack de mundo rodou no quadro anterior, a apresentação da transparência DX12 trava cor e profundidade em leitura/escrita e só as libera na captura do mundo. É a mesma regra da 1.9.0 para o composite: o D3D12 não usa essas imagens no intervalo, e toda submissão D3D12, resize, falha e desligamento libera um lock pendente antes. São um lock e um unlock a menos por quadro (`heldCompositeLocks` passa de cerca de 1 para cerca de 2 por quadro).
3. **Arena cheia sem repetição de rebuilds.** Uploads rejeitados contam no limite de uploads e de tempo do quadro. O tamanho de cada mesh rejeitado é guardado, e o Direct só pede o rebuild de novo quando o maior espaço livre da arena (consultado no máximo a cada 500 ms) comporta esse tamanho. O nativo faz uma única alocação de vértices mais índices (16 bits quando cabem), o mesmo tamanho que o Java estima. Quando há diferença de alinhamento, a estimativa é otimista, ou seja, no pior caso o comportamento volta ao da 1.9.0.

A imagem não muda em nenhum dos três casos.

## Resultado no jogo

Ambiente de desenvolvimento (`devlaunch`) com cópias da mesma cena salva, câmera parada, janela oculta de 1920×1080, sem VSync, Raydiance 1.9.0 + Solaris e as mesmas opções. Cada execução registra três janelas de 60 s; a primeira é aquecimento e fica de fora.

### Cena fixa (distância 12, arena de 128 MiB, sem arena cheia)

| Execução (ordem) | FPS | p50 (ms) | p95 (ms) | Captura do mundo (ms) | Apresentação da transparência (ms) |
| --- | --- | --- | --- | --- | --- |
| 1.9.0 (21:12) | 17,32 / 16,96 | 54,8 / 56,6 | 74,5 / 72,9 | 5,46 / 5,59 | 4,20 / 4,17 |
| 1.9.1 (21:20) | 18,43 / 19,39 | 53,6 / 51,0 | 60,4 / 56,6 | 3,13 / 3,07 | 2,16 / 2,14 |
| 1.9.0 (21:31) | 17,56 / 16,83 | 55,0 / 56,8 | 66,0 / 74,2 | 5,19 / 5,43 | 3,86 / 4,13 |

### Arena cheia (distância 24, arena de 64 MiB)

A cena foi aquecida uma vez com a 1.9.1, para gerar os chunks. Cada execução começou de uma cópia dessa cena aquecida. Em todos os quadros houve terreno misto, com 62–68 seções no vanilla.

| Execução (ordem) | FPS | p50 (ms) | p95 (ms) | Composição (ms) | GPU do terreno (ms) | Camadas base puladas por quadro | Rejeições na janela |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1.9.0 (1ª) | 20,72 / 20,79 | 47,9 / 47,8 | 52,2 / 51,5 | 11,6 / 11,5 | 8,3 / 8,3 | 0 de 3 | 827 / 805 |
| 1.9.1 (2ª) | 23,88 / 23,89 | 41,8 / 41,8 | 45,3 / 45,1 | 10,3 / 10,3 | 7,3 / 7,2 | 1 de 3 | 2 / 7 |

Com a 1.9.0, as rejeições cresciam cerca de 800 por minuto (rebuilds que nunca cabiam), e nenhuma camada base ficava inteiramente no DX12. Com a 1.9.1, as rejeições param, uma das três camadas fica inteira no DX12 em todos os quadros, e o quadro cai cerca de 6 ms (+15% de FPS).

Só existe um par antigo/novo para esse cenário. O segundo par (nova, antiga) foi interrompido no meio porque o jogo foi fechado durante a medição, e não entra na tabela. Dentro de cada execução a variação entre janelas foi de 0,1 FPS.

## Alternativa rejeitada

Uma variante que capturava cor e profundidade numa só passagem, em vez de dois blits, preservou pixels e profundidade nos testes gráficos. Na cena fixa, porém, a captura ficou em 5,66–5,70 ms, contra 5,46–5,59 ms da 1.9.0, sem ganho. Ela foi removida.

## Validação

- `build`, `test` (61), `graphicsTest` (56) e `nativeSmokeTest` (1/1) passam, sem falhas nem testes ignorados. O JAR medido é o mesmo instalado na instância Direct.
- `NativeMaterialsInteropTest` cobre a captura do mundo assumindo o lock de leitura/escrita mantido pela transparência, com pixels e profundidade verificados.
- `NativeArenaFullTest` cobre a arena cheia: rejeição sem falha, sinal consumido uma vez, `largestTerrainAllocation` menor que o mesh enquanto falta espaço, espaço liberado só reutilizado depois que a GPU termina os quadros que o liam.
- `TerrainMeshStoreTest` cobre o pedido de adoção sem repetir seções rejeitadas que não cabem.

## Limites

- As medições foram feitas no ambiente de desenvolvimento com a janela oculta, não na instância do CurseForge em tela cheia com VSync.
- No cenário de arena cheia há um único par antigo/novo.
- O ganho na arena cheia depende de quanto terreno não cabe. Aumentar `maxTerrainArenaMiB` continua sendo a forma de evitar o terreno misto.
- A transparência mantida só se aplica quando um pack de mundo roda e a transparência DX12 está ativa.
- O custo de GPU do Solaris (cerca de 18–27 ms por quadro em `gpuPassesMs`) continua sendo a maior parte do quadro, e o pack não foi alterado.

## Como validar no jogo

Mesma cena e câmera, 60 s estáveis por versão, comparando `Direct frame times`, `Direct ownership` e `Direct world post` (ou `logs/direct-performance.csv`):

1. Com arena cheia (aviso `Direct terrain GPU arena is full` no log): `arenaRejectionsTotal` deve parar de crescer depois do primeiro minuto, e `skippedBaseLayers` não deve ficar em zero.
2. Com Solaris e transparência DX12: `heldCompositeLocks` perto do dobro de `frames`.
3. Visual: água e vidro na frente e atrás do terreno, entidades, troca de dimensão, redimensionar a janela e alternar tela cheia.
