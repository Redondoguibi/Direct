# Direct 1.7.6 — espera DX12 atrás do OpenGL retido

## Evidência no jogo

Sessão de 2026-11-20 (21:19–21:21, instância Direct, só Direct 1.7.5 e Raydiance 1.6.2 com o pack pausado), Intel UHD 630, 1920×1080, distância 5, VSync ligado, `replacement`, 0 seções em fallback e 0 frames descartados:

| Janela | FPS | Frame p50 (ms) | Composição/frame (ms) | Espera DX12/frame (ms) | GPU DX12/frame (ms) |
| --- | ---: | ---: | ---: | ---: | ---: |
| 21:20 | 24,41 | 40,22 | 17,96 | 13,15 | 3,21 |
| 21:21 | 25,24 | 39,07 | 17,63 | 12,96 | 3,12 |

O render thread espera cerca de 13 ms por um frame DX12 que ocupa a GPU por cerca de 3 ms. O timestamp inicial fica na primeira lista do terreno. Os ~10 ms restantes acontecem antes de o terreno começar na GPU. O consumo GL anterior já foi confirmado pelo GLsync antes da submissão, e a fila DX12 não depende de nenhum outro trabalho GL. Esse tempo é o trabalho OpenGL já entregue à GPU à frente do frame DX12.

## Causa reproduzida

`GlFlushBenchmark` (janela GL oculta, sem Minecraft) reproduz o fim de um frame do jogo: draws GL curtos intercalados com trabalho de CPU (entidades, GUI), o flush do swap e, no frame seguinte, a submissão DX12 com espera na CPU. Fases alternadas A/B/B/A, 20 de aquecimento e 90 amostras. Os valores abaixo são p50.

| GL no fim do frame | Flush por etapa | Espera (ms) | GPU DX12 (ms) | Frame (ms) |
| --- | --- | ---: | ---: | ---: |
| ~12 ms | não | 26,29 / 26,28 | 16,50 / 16,52 | 45,26 / 45,24 |
| ~12 ms | sim | 16,22 / 16,23 | 16,10 / 16,06 | 35,60 / 35,68 |
| ~6 ms | não | 20,76 / 21,02 | 16,36 / 16,51 | 32,21 / 32,44 |
| ~6 ms | sim | 16,57 / 16,55 | 16,45 / 16,36 | 28,20 / 28,17 |

Sem flush intermediário, o driver retém os comandos gravados até o swap. A GPU fica ociosa enquanto a CPU grava o fim do frame e depois recebe tudo de uma vez. O frame DX12 seguinte entra atrás desse bloco, e a espera passa a ser fila GL mais DX12. Com flush ao fim de cada etapa, a GPU executa o GL em paralelo com a CPU, e a espera volta a ser só o tempo de GPU do DX12.

## Implementação

Com o backend DX12 ativo, o Direct chama `glFlush()` ao fim das etapas `AFTER_SKY`, `AFTER_ENTITIES`, `AFTER_BLOCK_ENTITIES`, `AFTER_TRANSLUCENT_BLOCKS`, `AFTER_PARTICLES`, `AFTER_WEATHER` e `AFTER_LEVEL` do `RenderLevelStageEvent`. O listener usa prioridade `LOWEST`, então desenhos de outros mods na mesma etapa também vão à GPU. São até 7 flushes por frame. Não há espera, cópia ou mudança de ordem dos comandos: a imagem é a mesma.

- Opção: **Avançado > Flush OpenGL por etapa** (`flushOpenGlStages`, padrão ligado). Desligada no preset de compatibilidade.
- Sem efeito em vanilla nem com o backend desligado ou suspenso.
- Fences, GLsync, locks WGL e a espera do frame atual continuam todos ativos.

## Alternativa rejeitada

Uma fila D3D12 com `D3D12_COMMAND_QUEUE_PRIORITY_HIGH` foi testada no mesmo cenário sintético e removida. O driver concedeu a prioridade, mas a espera não mudou além da variação entre execuções. Com ~10 ms de GL à frente, ficou em 11,4–12,3 ms na fila normal e 10,8–11,5 ms na alta; com ~25 ms, em 21,8–22,7 e 21,6–22,2 ms. O agendador não passa o frame DX12 à frente do trabalho GL já entregue.

Uma variante com janela visível e VSync também foi descartada como medida. Mesmo sem DX12, a janela pequena aberta pelo Gradle ficou em 33–46 ms por frame, então ela não representa a janela do jogo.

## Limites

A carga é sintética. O ganho no jogo depende de quanto OpenGL o driver retém e de quanto a CPU grava depois da composição. A sessão acima sugere uma fila de ~10 ms, que é o teto do que esta mudança pode remover da espera. Isso não é estimativa de FPS. A composição ainda precisa do frame DX12 atual, e sem a importação GPU da fence (rejeitada com 0x500 neste driver) a CPU continua esperando o tempo de GPU do terreno.

Para validar no jogo, compare na mesma cena, mesma câmera e mesmas opções, 60 s estáveis por modo, com **Flush OpenGL por etapa** ligado e desligado: `dxWaitMs`, `compositeMs`, `fps` e `p95Ms` em `logs/direct-performance.csv`. O agente não abriu o Minecraft.

Reprodução, sem Minecraft aberto:

```powershell
.\gradlew.bat --init-script docs/terrain-benchmark.gradle glFlushBenchmark '-PbenchmarkLibrary=C:\Criando Mods\mod\Direct\build\generated\nativeResources\META-INF\native\windows-x86_64\direct_renderer.dll' -PbenchmarkGlMs=12
```
