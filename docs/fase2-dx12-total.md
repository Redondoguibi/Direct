# Fase 2 do Direct DX12 total — mundo (22/11/2026)

Plano: [arquitetura-dx12-total.md](arquitetura-dx12-total.md). Fase anterior: [fase1-dx12-total.md](fase1-dx12-total.md).

## Resumo

O backend DX12 da fase 1 agora desenha um mundo inteiro do vanilla, com pixels equivalentes aos do OpenGL nas cenas de teste: terreno, céu, sol e lua, estrelas, nuvens, entidades, mão, contorno de bloco, rachaduras de quebra, partículas, HUD, F3, inventário e menu de pausa.

O terreno usado é o do vanilla, desenhado pelo próprio backend. Pôr o terreno DX12 do Direct sob esses desenhos ainda falta e é o próximo passo (ver "O que falta").

## Portão

| Critério | Resultado |
| --- | --- |
| Capturas comparadas ao vanilla em cenas fixas | **Passou.** 11 cenas, 3 quadros cada, todas dentro do critério abaixo, em 4 execuções seguidas (3 normais e 1 com a camada de depuração do D3D12). |
| Harness sem regressão | **Passou por construção.** Todo o código da fase 2 só entra com `-Ddirect.phase1.shadow=true` (os mixins `*ShadowMixin` não são aplicados sem essa propriedade). O modo híbrido e o vanilla não executam nada novo. |

Validação: `build`, `test` (61), `graphicsTest` (56) e `nativeSmokeTest` (1/1) passam. A prova dos menus da fase 1 continua passando.

## Cenas

Ambiente:
- Cliente de desenvolvimento só com o Direct (terreno DX12 do modo híbrido desligado, que é o padrão).
- Os mods que o save precisa: `thefict` e `geckolib`.
- Cópia do mundo de benchmark do usuário (badlands, distância 12), 1280×720.
- Script: `docs/bench/run-world-probe.ps1`.

| Cena | O que cobre | Pixels não explicados | Maior grupo |
| --- | --- | ---: | ---: |
| horizonte | terreno sólido, cutout e translúcido, entidades, mão, HUD | 33 | 1 |
| céu | céu, sol, nuvens desligadas | 14 | 1 |
| nuvens | nuvens Fancy (ligadas só nessa cena) | 337 | 3 |
| chão | contorno do bloco, mão | 18 | 1 |
| quebra | rachaduras de destruição (`rendertype_crumbling`) | 52 | 3 |
| terceira pessoa | modelo do jogador | 32 | 2 |
| noite | estrelas, lua, lightmap noturna | 9 | 1 |
| chuva | céu de chuva (não chove no bioma badlands) | 40 | 1 |
| F3 | texto da tela de depuração | 26 | 1 |
| inventário | itens na GUI, jogador na GUI | 7 | 1 |
| pausa | desfoque do mundo | 0 | 0 |

Cada quadro faz de 114 a 742 desenhos, conforme a cena. Todos chegam ao D3D12; o único ignorado é a cópia final para o framebuffer 0, porque a apresentação usa o render target principal.

## Critério de comparação no mundo

**Por que não basta contar diferenças.** De 0,05% a 0,3% dos pixels diferem mais de 2/255. Quase todos são pixels isolados num destes lugares:
- arestas de triângulos;
- fronteiras entre texels com filtro `NEAREST`;
- frestas entre os quadriláteros das nuvens, que nas duas APIs aparecem em pixels diferentes.

Nesses lugares, o ponto de amostra cai exatamente na fronteira. O `fxc` e o compilador GLSL do driver calculam posições e coordenadas com diferenças no último bit, e cada API arredonda o empate para um lado. Isso não muda a imagem que o jogador vê.

**O que a prova mede.** Um pixel conta como "não explicado" quando:
- difere mais de 8/255;
- e nenhum pixel na vizinhança 3×3 da outra imagem tem a mesma cor.

**Critério.** A cena passa se os não explicados formam grupos de no máximo 4 pixels e somam menos de 0,1% da imagem. Um objeto que falta ou fica fora do lugar sempre deixa um grupo maior. O bug descrito abaixo, por exemplo, deixava grupos de 52 a 210 pixels. Os menus continuam com o critério da fase 1.

## O que foi feito

**Malhas estáticas na GPU.** Os `VertexBuffer` com `Usage.STATIC` (seções de terreno, céu, estrelas, nuvens) guardam vértices e índices em páginas de 32 MiB no heap DEFAULT do D3D12. Funciona assim:
- os dados são copiados por `CopyBufferRegion` a partir do anel de upload;
- o espaço é alocado por first-fit, e um trecho liberado só volta a ser usado depois da próxima espera da fence;
- `VertexBuffer.close` libera a malha.

As malhas dinâmicas (`BufferUploader`) continuam copiadas a cada desenho. A cena de teste ocupa 21 MiB.

**Cópia de profundidade.** `glBlitFramebuffer` de anexos inteiros do mesmo tamanho virou `CopyTextureRegion`. É usado por `RenderTarget.copyDepthFrom`, que copia a profundidade para o alvo do contorno de entidades a cada quadro. Cópias parciais ou com escala são registradas como não suportadas; nenhuma apareceu nas cenas.

**Tabelas de sampler em cache.** O heap de samplers visível aos shaders tem no máximo 2048 entradas, e cada desenho usa 12. Antes, o heap enchia a cada ~170 desenhos e forçava uma execução no meio do quadro. Agora cada combinação de 12 samplers vira uma tabela criada uma vez e reutilizada. A cena inteira usa poucas combinações.

**Ferramentas de diagnóstico:**
- `-Ddirect.phase2.verifyMeshes=true` (`-VerifyMeshes` no script): a cada 30 quadros, compara os vértices de cada malha estática, lidos de volta pela GPU, com o buffer do OpenGL, e confere o padrão e a contagem dos índices.
- Com `-Ddirect.phase1.debug=true`, as mensagens da camada de depuração do D3D12 vão para o log a cada quadro, não só nos erros de PSO.
- O log de cada cena mostra quantos desenhos o jogo pediu, quantos o backend aceitou e quantos chegaram ao D3D12.

## Bug encontrado: constantes sobrescritas no anel

**Sintoma.** Em algumas execuções, grupos de arbustos secos sumiam na imagem DX12, sempre os mesmos dentro de uma execução e diferentes entre execuções.

**O que foi descartado:**
- a camada de depuração não apontava erro;
- todos os desenhos chegavam ao D3D12;
- os vértices e índices das malhas eram iguais aos do OpenGL, inclusive lidos de volta pela GPU;
- desligar o reuso de memória e mover as malhas para o heap DEFAULT não resolveram.

**Causa.** Um desenho escrevia suas constantes (`ChunkOffset` das seções) no anel de upload e só depois reservava os samplers. Quando o heap de samplers enchia nesse ponto, o backend executava o trabalho gravado e reiniciava o anel. O desenho ainda não gravado ficava apontando para constantes que os desenhos seguintes sobrescreviam antes da execução. A seção era então desenhada com o deslocamento de outra seção, fora da vista. Por depender de onde caía a execução forçada, o erro aparecia ou não conforme a execução.

**Correção.** Cada desenho reserva antes todo o espaço de anel e de descritores de que precisa; a partir daí nenhuma alocação executa o trabalho pendente. Somadas às tabelas de sampler em cache, quatro execuções seguidas ficaram sem nenhum grupo acima de 3 pixels.

## Modo só DX12 no mundo

Com `-Present -SkipGl` (OpenGL sem desenhar, imagem apresentada por DXGI, janela visível), as imagens DX12 de todas as cenas ficaram completas.

A verificação do desktop deu 64/64 em 30 de 34 checagens. As 4 falhas (0/64) coincidiram com entrada externa na janela visível: um campo de chat abriu sozinho na cena do chão. Uma segunda execução teve 0/64 em tudo: a janela ficou atrás de outra, e a câmera girou com o mouse.

A apresentação por DXGI em si já foi provada na fase 1. Uma repetição da checagem do desktop no mundo precisa do PC livre.

## O que falta para fechar a fase 2

O plano pede "entidades, block entities, partículas, céu, nuvens, clima, mão, contorno e destruição de blocos, **sobre o terreno DX12 existente**".

**O que já existe.** Tudo isso passa pelo backend, mas sobre o terreno vanilla desenhado pelo backend.

**Por que o terreno do Direct ainda não entra.** O renderizador híbrido (`dx12_gl_interop.cpp`) tem dispositivo e fila próprios e entrega o terreno ao OpenGL por interop. Seu composite usa chamadas OpenGL diretas, que o backend não vê.

**O que é preciso:**
1. O renderizador de terreno e o backend passam a usar o mesmo dispositivo. O backend espera, na própria fila, a fence do terreno daquele quadro antes de ler o resultado.
2. Um composite em DX12 escreve a cor e a profundidade do terreno no render target principal do backend, no lugar do composite OpenGL.
3. No modo só DX12, nenhuma trava WGL nem captura de cena roda.
4. A prova do mundo compara o resultado com o modo híbrido atual, com o terreno do Direct ligado.

## Limites

- **Clima:** a cena de chuva usa um bioma sem chuva, então gotas e respingos não foram comparados. Neve e chuva visíveis precisam de outra cena.
- **Fora desta fase:** o contorno de entidades brilhantes (`PostChain`), o Fabulous e o glint são da fase 3.
- **Entradas `UV1`/`UV2` declaradas como float:** alguns shaders vanilla as declaram assim e recebem inteiros de 16 bits, mas nunca as leem. O OpenGL deixa esse caso indefinido; o D3D12 reinterpreta os bits. Sem efeito na imagem.
- **Desempenho:** o modo sombra continua síncrono e não foi medido. As malhas estáticas na GPU e as tabelas de sampler reduzem o trabalho por desenho, mas o ganho de FPS só pode ser medido no modo só DX12 com o terreno do Direct.
