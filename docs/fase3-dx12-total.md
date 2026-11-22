# Fase 3 do Direct DX12 total — shader packs e FPS (22/11/2026)

Plano: [arquitetura-dx12-total.md](arquitetura-dx12-total.md). Fase anterior: [fase2-dx12-total.md](fase2-dx12-total.md).

## Resumo

Os shader packs agora leem a cena direto do backend DX12, sem passar pelo OpenGL. O Lus e o Solaris rodam sem mudanças, nos dois estágios que dependiam do OpenGL: os passes de mundo e a transparência do pack.

No modo só DX12, o Direct + Raydiance + Lus mediu 69,0 FPS, contra 67,6 do Sodium + Iris + Mellow e 29,4 do modo híbrido atual. É a mesma cena do harness, com as mesmas opções. Em FPS médio é um empate técnico; no p95, o Direct é bem melhor (18 ms contra 31 ms).

## Portão

| Critério | Resultado |
| --- | --- |
| Lus e Solaris com a mesma imagem do modo híbrido | **Passou.** 16 cenas, 3 quadros cada: Lus com e sem a transparência DX12, e Solaris. O modo sombra compara, no mesmo quadro, a imagem OpenGL do modo híbrido com a imagem do backend. O Lus e o Solaris não foram alterados. |
| `PostChain`, Fabulous e glint | **Passou.** Contorno de entidade brilhante, encantamento em item na mão e a cadeia de transparência do Fabulous, nas mesmas comparações. |
| FPS medido contra o Sodium + Iris + Mellow | **Medido.** Tabela abaixo. |

Validação:
- `build`, `test` (61), `graphicsTest` (56) e `nativeSmokeTest` (1/1) passam;
- a prova dos menus da fase 1 continua passando;
- o modo só DX12 roda limpo na camada de depuração do D3D12 com Lus e com Solaris.

## FPS

Harness `docs/bench/run-case.ps1`: 1920×1080 (janela oculta), mundo e opções do usuário, distância 12, sem VSync. São três janelas de 60 s por execução; a primeira é descartada. As execuções foram intercaladas na ordem abaixo.

| Execução | FPS (janelas 2 e 3) | p95 |
| --- | --- | --- |
| Direct só DX12 + Raydiance + Lus, A | 68,9 / 69,2 | 18,2 / 18,5 ms |
| Sodium + Iris + Mellow, A | 69,5 / 70,1 | 31,7 / 31,4 ms |
| Direct híbrido + Raydiance + Lus | 29,5 / 29,4 | 39,6 / 39,8 ms |
| Direct só DX12 + Raydiance + Lus, B | 69,0 / 69,1 | 18,1 / 18,2 ms |
| Sodium + Iris + Mellow, B | 66,1 / 64,6 | 30,6 / 33,2 ms |

Médias:
- **Direct só DX12:** 69,0 FPS, p95 18,3 ms;
- **Sodium:** 67,6 FPS, p95 31,7 ms;
- **Direct híbrido:** 29,4 FPS.

O modo só DX12 é 2,3 vezes mais rápido que o híbrido. Os números do harness são mais altos que os da instância do usuário (44 FPS no Sodium, em tela cheia e com o PC em uso); a comparação vale dentro do harness.

**Onde vai o quadro no modo só DX12** (log `Direct DX12 backend timing`, execução B):

| CPU por quadro | |
| --- | ---: |
| Trabalho do jogo | 8,2–9,0 ms |
| Espera pelo quadro de terreno anterior | 5,6–6,0 ms |
| Apresentação | 0,2 ms |

| GPU do backend por quadro | |
| --- | ---: |
| Composite do terreno | 1,5 ms |
| Exportação da cena para o pack | 1,3 ms |
| Importação de volta | 0,6 ms |
| Apresentação | 0,8 ms |
| Limpezas | 0,2 ms |
| Uploads de textura | 0,4 ms |
| Desenhos | ~1,9 ms |
| **Total** | **6,7–6,9 ms** |

Os passes do Lus custam mais 3,4 ms de GPU.

## Como os packs leem a cena do backend

O renderizador de terreno usa o dispositivo do backend desde a fase 2. Nos dois estágios do pack (transparência, depois das entidades, e passes de mundo, no fim do nível), em vez da captura OpenGL:

1. **Exportação.** O backend escreve a própria cena nas imagens de entrada do pack, como `InteropSceneCapture.capture` faz: a cor na imagem de cor do terreno e a profundidade no bridge R32F. É um passe de tela cheia que inverte as linhas, porque o backend guarda as linhas de baixo para cima e o pack as espera de cima para baixo.
2. **Envio sem espera.** O backend envia o que gravou até ali sem esperar pela GPU, e a fila do terreno espera por ele na GPU.
3. **Estágio inalterado.** O estágio nativo do pack (`renderTranslucentInterop` ou `renderWorldInterop`) roda sem mudanças.
4. **Importação.** O resultado volta para a cena do backend, a cor e, na transparência, também a profundidade, depois de uma espera na GPU pela fence do estágio.
5. **Liberação.** O valor do estágio é dado como consumido, como o OpenGL faria; o próximo quadro de terreno depende disso.

No modo só DX12, o renderizador também deixa de preparar as imagens para o OpenGL: não exporta mais a profundidade para o bridge depois de cada quadro e não as entrega ao D3D11On12.

No modo sombra (comparação), o caminho OpenGL roda primeiro e o do backend roda de novo sobre a própria cena. Isso vale porque o Lus e o Solaris não têm histórico entre quadros.

## Quadros em paralelo (o que levou de 27 para 69 FPS)

A primeira medição do modo só DX12 deu 27 FPS, abaixo do híbrido. O CPU parava e esperava a GPU três vezes por quadro: no composite do terreno, no estágio do pack e no fim do quadro. Com isso, os ~9 ms de CPU e os ~15 ms de GPU se somavam.

No modo só DX12, agora:
- o composite do terreno e as importações esperam na GPU, não no CPU;
- o fim do quadro envia e apresenta sem esperar;
- há dois quadros em voo, cada um com sua metade do anel de upload e do anel de descritores;
- recursos e trechos de malha liberados voltam a ser usados quando a fence passa do valor que os cobre;
- os alocadores de comandos são reciclados pela fence;
- as medições de GPU usam uma faixa de consultas por quadro.

A única espera no CPU que sobra é no início do terreno do quadro seguinte: o renderizador substitui recursos de seções e precisa que o quadro anterior dele tenha terminado.

O modo só DX12 também deixou de executar no OpenGL as limpezas e as cópias de framebuffer (`glClear` e `glBlitFramebuffer`), que ninguém lê. As texturas continuam sendo enviadas ao OpenGL, porque o Direct lê o atlas de blocos de lá.

## Bugs encontrados

1. **Espera circular na primeira execução com o Lus.** O próximo quadro de terreno espera que o OpenGL tenha consumido o último valor enviado (`glDoneFence`). Os estágios do backend criavam valores que ninguém marcava como consumidos, e o terreno travava. Agora o backend marca. No modo sombra, o sinal pendente do OpenGL é concluído antes, para a fence nunca voltar.
2. **Lus aplicado duas vezes com a transparência DX12 (modo sombra).** A importação da transparência ficava gravada e só rodava no envio seguinte. Antes disso, o caminho OpenGL do passe de mundo escrevia a própria captura e o resultado do Lus nas mesmas imagens, e o backend importava a imagem já processada. No modo sombra, a importação agora é executada na hora.
3. **Alfa do contorno de entidade.** `GlStateManager._blendFunc` só compara e registra o par RGB. Quando esse par não muda, ele não chama `glBlendFunc`, e o OpenGL mantém os fatores de alfa anteriores. O espelho aplicava os fatores novos também ao alfa, e o passe final do contorno saía com alfa ao quadrado. Agora o espelho segue o mesmo cache do `GlStateManager.BLEND`.
4. **Estado do D3D11On12 no modo sombra.** Enquanto o OpenGL usa a imagem do terreno pela trava WGL, o D3D11On12 a deixa em `COPY_SOURCE` ou `COPY_DEST` na fila do terreno. No modo sombra, o backend lê cópias feitas nessa fila antes da trava (fase 2). Antes de escrever nas imagens, ele também as devolve ao D3D12 (`directTerrainHandOff`). A camada de depuração ficou sem erros.

## Cenas e critério

A prova do mundo ganhou cinco cenas:

| Cena | O que cobre |
| --- | --- |
| água | uma piscina de água e uma coluna de vidro tingido criadas na cópia do mundo; transparência vanilla ou do pack |
| brilhando | o jogador com o efeito Brilho em terceira pessoa; a `PostChain` do contorno de entidades |
| glint | uma maçã dourada encantada na mão |
| Fabulous | o modo gráfico Fabulous e a cadeia de transparência |
| Solaris | todas as cenas com o Solaris (`-PackName solaris`): três passes de mundo, transparência e sombras do pack |

O critério da fase 2 vale para todas, com uma exceção na cena brilhando. A `PostChain` do contorno desfoca a silhueta, então um empate de um pixel na borda vira uma pequena área de valores diferentes.

Por isso, nessa cena:
- o alvo do contorno é comparado à parte, com alfa: no máximo 0,01% dos pixels diferentes no alfa e 0,1% na cor;
- a imagem final é comparada fora dos pixels cobertos pelo contorno.

Sem a correção do cache de blend, essa cena tinha 544 pixels não explicados no contorno. Com ela, são 34 pixels de alfa diferentes, em duas manchas ao longo de uma aresta do modelo.

Para rodar as provas:
- `-DirectTerrain -Pack`, com `-Source` apontando para o caso `base-direct-1` do harness;
- `-Dx12Translucency` liga a transparência DX12 também com o Lus no preset Desempenho;
- `-PackName solaris` troca o pack.

## Limites

- **Modo experimental.** O modo só DX12 continua atrás das propriedades `-Ddirect.phase1.shadow`, `present` e `skipGl`. Opção no painel, detecção de mods que usam OpenGL direto e fallback são a fase 4.
- **Sem vitória clara.** Em FPS médio, o resultado é um empate com o Sodium + Iris + Mellow. Ainda pesam:
  - as transferências por causa da orientação das linhas (composite, exportação, importação e apresentação, cerca de 4,5 ms de GPU);
  - a espera no início do terreno (~6 ms de CPU).

  Os caminhos para cortar isso, sem tocar nos packs, estão em "Próximo passo".
- **Medido só no harness.** Ainda falta confirmar na instância real do usuário, em tela cheia e com VSync.
- **Fabulous.** Com o Fabulous, os estágios do pack não rodam, como no modo híbrido. O terreno DX12 continua.
- **Uploads de textura no OpenGL.** No modo só DX12, as texturas ainda são enviadas também ao OpenGL, porque o Direct lê o atlas de blocos de lá.

## Próximo passo

Fase 4 (compatibilidade e entrega), mais estes ganhos medidos e ainda livres:
1. **Animações do atlas.** São ~170 cópias pequenas por quadro, que podem virar uma por nível de mip (0,4 ms de GPU).
2. **Composite do terreno.** Desenhar o terreno direto no alvo principal do backend, com o Y invertido no próprio renderizador de terreno, elimina o composite (1,5 ms de GPU).
3. **Espera no início do terreno.** Pode ser reduzida com mais frames por slot no renderizador de terreno.
4. **Instância real.** Confirmar na instância do usuário, em tela cheia e com VSync.
