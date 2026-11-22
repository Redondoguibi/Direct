# Fase 2 do Direct DX12 total — mundo (22/11/2026)

Plano: [arquitetura-dx12-total.md](arquitetura-dx12-total.md). Fase anterior: [fase1-dx12-total.md](fase1-dx12-total.md).

## Resumo

O backend DX12 desenha o mundo inteiro, e o terreno pode ser o próprio terreno DX12 do Direct, composto dentro do backend sem passar pelo OpenGL. Tudo isso aparece com pixels equivalentes nas cenas de teste:
- céu, sol e lua, estrelas, nuvens;
- entidades, mão, contorno de bloco, rachaduras de quebra, partículas;
- HUD, F3, inventário e menu de pausa.

No modo só DX12, com os desenhos do OpenGL cancelados, nada da cena passa pelo OpenGL: nem desenho, nem trava WGL.

## Portão

| Critério | Resultado |
| --- | --- |
| Capturas comparadas em cenas fixas, sobre o terreno DX12 existente | **Passou.** 11 cenas, 3 quadros cada. Terreno vanilla: imagem DX12 contra a do vanilla em OpenGL. Terreno do Direct: imagem DX12 contra a do modo híbrido atual. Tudo dentro do critério abaixo, também com a camada de depuração do D3D12. |
| Harness sem regressão | **Passou.** Sem o backend, o modo híbrido só ganhou um teste de nulo na criação do dispositivo. Medição intercalada no harness (1080p, mundo do usuário, janelas de 60 s): build nova 31,2 / 29,4 FPS, build anterior 34,1 / 29,3 FPS. O par final é igual. O primeiro par variou 2,3 FPS dentro da própria execução da build anterior, sinal de carga externa (o PC estava em uso). |

Validação: `build`, `test` (61), `graphicsTest` (56) e `nativeSmokeTest` (1/1) passam. A prova dos menus da fase 1 continua passando.

## Cenas

Ambiente:
- Cliente de desenvolvimento só com o Direct e os mods que o save precisa (`thefict`, `geckolib`).
- Cópia do mundo de benchmark do usuário (badlands, distância 12), 1280×720.
- Script: `docs/bench/run-world-probe.ps1`. Com `-DirectTerrain`, o terreno DX12 do Direct substitui o vanilla.

| Cena | O que cobre | Terreno vanilla: não explicados / maior grupo | Terreno do Direct: não explicados / maior grupo |
| --- | --- | ---: | ---: |
| horizonte | terreno, entidades, mão, HUD | 32 / 1 | 16 / 1 |
| céu | céu, sol | 14 / 1 | 14 / 1 |
| nuvens | nuvens Fancy (ligadas só nessa cena) | 341 / 2 | 371 / 3 |
| chão | contorno do bloco, mão | 18 / 1 | 16 / 1 |
| quebra | rachaduras de destruição | 52 / 3 | 38 / 1 |
| terceira pessoa | modelo do jogador, sombra da entidade | 33 / 2 | 5 / 2 |
| noite | estrelas, lua, lightmap noturna | 9 / 1 | 9 / 1 |
| chuva | céu de chuva (não chove no bioma badlands) | 40 / 1 | 16 / 1 |
| F3 | texto da tela de depuração | 26 / 1 | 15 / 1 |
| inventário | itens e jogador na GUI | 7 / 1 | 7 / 1 |
| pausa | desfoque do mundo | 0 / 0 | 0 / 0 |

Em nenhuma cena houve bloco 2×2 de pixels não explicados.

**Desenhos por quadro.** Com o terreno vanilla, cada quadro faz de 114 a 744 desenhos. Com o terreno do Direct, de 19 a 199, porque o terreno inteiro vira um só composite. Todos chegam ao D3D12, menos a cópia final para o framebuffer 0, porque a apresentação usa o render target principal.

## Critério de comparação no mundo

**Por que não basta contar diferenças.** De 0,05% a 0,3% dos pixels diferem mais de 2/255. Quase todos são pixels soltos num destes lugares:
- arestas de triângulos;
- fronteiras entre texels com filtro `NEAREST`;
- frestas entre os quadriláteros das nuvens, que nas duas APIs aparecem em pixels diferentes.

Nesses lugares o ponto de amostra cai exatamente na fronteira. O `fxc` e o compilador GLSL do driver calculam posições e coordenadas com diferenças no último bit, e cada API arredonda o empate para um lado. Isso não muda a imagem que o jogador vê.

**O que a prova mede.** Um pixel conta como "não explicado" quando:
- difere mais de 8/255;
- e nenhum pixel na vizinhança 3×3 da outra imagem tem a mesma cor.

**Critério.** A cena passa se os pixels não explicados:
- são pontos ou linhas de um pixel, sem nenhum bloco 2×2;
- não formam grupo com mais de 16 pixels;
- somam menos de 0,1% da imagem.

Um objeto que falta ou fica fora do lugar deixa uma área. Nos quadros do bug descrito abaixo havia de 126 a 149 blocos 2×2 e grupos de 127 a 210 pixels. Nos quadros corretos, nenhum bloco. As frestas das nuvens chegam a formar linhas diagonais de 5 pixels. Os menus continuam com o critério da fase 1.

## O que foi feito

### Mundo vanilla no backend

**Malhas estáticas na GPU.** Os `VertexBuffer` com `Usage.STATIC` (seções de terreno, céu, estrelas, nuvens) guardam vértices e índices em páginas de 32 MiB no heap DEFAULT do D3D12. Funciona assim:
- os dados são copiados por `CopyBufferRegion` a partir do anel de upload;
- o espaço é alocado por first-fit, e um trecho liberado só volta a ser usado depois da próxima espera da fence;
- `VertexBuffer.close` libera a malha.

As malhas dinâmicas (`BufferUploader`) continuam copiadas a cada desenho. A cena de teste ocupa 21 MiB.

**Cópia de profundidade.** `glBlitFramebuffer` de anexos inteiros do mesmo tamanho virou `CopyTextureRegion`. É usado por `RenderTarget.copyDepthFrom`, que copia a profundidade para o alvo do contorno de entidades a cada quadro. Cópias parciais ou com escala ficam registradas como não suportadas; nenhuma apareceu nas cenas.

**Tabelas de sampler em cache.** O heap de samplers visível aos shaders tem no máximo 2048 entradas, e cada desenho usa 12. Antes ele enchia a cada ~170 desenhos. Agora cada combinação de 12 samplers vira uma tabela criada uma vez e reutilizada.

### Terreno DX12 do Direct no backend

**Mesmo dispositivo.** Com o backend ativo, o renderizador de terreno do modo híbrido (`dx12_gl_interop.cpp`) é criado no mesmo dispositivo D3D12 que o backend, por `directBackendDevice()` em `direct_shared.h`. Ele mantém a própria fila de comandos. Sem o backend, ele cria o próprio dispositivo, como antes.

**Composite em DX12.** No ponto em que `Dx12GlInterop.resolvePendingFrame` faz o composite do terreno em OpenGL, o backend grava o mesmo composite em DX12:
- um triângulo de tela cheia lê a cor e a profundidade do terreno;
- a cor é misturada com alfa sobre o framebuffer;
- num quadro de substituição, a profundidade é escrita, e os pixels sem terreno (profundidade 1 e alfa 0) são descartados;
- a imagem do terreno tem as linhas de cima para baixo e o framebuffer do backend as tem de baixo para cima, então a linha y lê a linha `altura − 1 − y`.

**Sincronização entre filas.** Antes de executar o composite, a fila do backend espera na GPU (`ID3D12CommandQueue::Wait`) a fence do quadro de terreno.

**Modo sombra.** Aqui o OpenGL também faz o composite da mesma imagem pela trava WGL. Nesse caminho o D3D11On12 move a imagem por estados próprios na fila do terreno (a camada de depuração o mostrou em `COPY_SOURCE` e `COPY_DEST`). Por isso, nesse modo:
- o backend submete, na fila do terreno, uma cópia da cor e da profundidade logo após o quadro de terreno e antes da trava;
- e lê as cópias, não a imagem compartilhada.

Com isso, a camada de depuração não aponta nenhum erro.

**Modo só DX12** (`present` + `skipGl`):
- o backend lê a imagem do terreno direto, sem cópia;
- o composite OpenGL e a trava WGL não rodam;
- o renderizador é liberado para o quadro seguinte pela mesma fence que o OpenGL sinalizaria.

### Ferramentas

- `-Ddirect.phase2.verifyMeshes=true` (`-VerifyMeshes`): a cada 30 quadros, compara os vértices de cada malha estática, lidos de volta pela GPU, com o buffer do OpenGL, e confere o padrão e a contagem dos índices.
- Com `-Ddirect.phase1.debug=true`, as mensagens da camada de depuração vão para o log a cada quadro. A lista de comandos, as texturas e a imagem do terreno têm nome nessas mensagens.
- O log de cada cena mostra:
  - quantos desenhos o jogo pediu, quantos o backend aceitou e quantos chegaram ao D3D12;
  - quantos composites de terreno o quadro teve;
  - a taxa de quadros aproximada enquanto a cena se assentava.
- `-Hidden` mantém a janela oculta mesmo com `-Present`. A checagem do desktop é pulada quando a janela não está visível.

## Bug encontrado: constantes sobrescritas no anel

**Sintoma.** Em algumas execuções, grupos de arbustos secos sumiam da imagem DX12. Eram sempre os mesmos dentro de uma execução e diferentes entre execuções.

**O que foi descartado:**
- a camada de depuração não apontava erro;
- todos os desenhos chegavam ao D3D12;
- os vértices e índices das malhas eram iguais aos do OpenGL, inclusive lidos de volta pela GPU;
- desligar o reuso de memória e mover as malhas para o heap DEFAULT não resolveram.

**Causa.** Um desenho escrevia suas constantes (o `ChunkOffset` da seção) no anel de upload e só depois reservava os samplers. Quando o heap de samplers enchia nesse ponto, o backend executava o trabalho gravado e reiniciava o anel. O desenho ainda não gravado ficava apontando para constantes que os desenhos seguintes sobrescreviam antes da execução. A seção era então desenhada com o deslocamento de outra seção, fora da vista.

**Correção.** Cada desenho reserva antes todo o espaço de anel e de descritores de que precisa, e a partir daí nenhuma alocação executa o trabalho pendente. Com as tabelas de sampler em cache, nenhuma execução posterior teve área faltando.

## Modo só DX12

**Terreno vanilla.** Com `-Present -SkipGl` e a janela visível, a tela mostrou a imagem DX12 (64/64 pixels do desktop) em 30 de 34 checagens. As 4 falhas coincidiram com entrada externa na janela: um campo de chat abriu na cena do chão. Uma segunda execução teve 0/64 em tudo, com a janela atrás de outra e a câmera girada pelo mouse.

**Terreno do Direct.** Com `-DirectTerrain -Present -SkipGl -Hidden`, as imagens DX12 das 11 cenas ficaram completas, também com a camada de depuração. A checagem do desktop não foi repetida com a janela visível porque o PC estava em uso. A apresentação por DXGI já foi provada na fase 1 (18/18) e nesta fase com o terreno vanilla.

**Taxa aproximada** enquanto cada cena se assentava (1280×720, janela oculta, backend síncrono; não é benchmark):

| Modo | Horizonte | Nuvens | Terceira pessoa |
| --- | ---: | ---: | ---: |
| Só DX12, terreno do Direct | 78 | 63 | 65 |
| Sombra, terreno vanilla (OpenGL + DX12) | 63 | 51 | 52 |
| Sombra, terreno do Direct (OpenGL híbrido + DX12) | 51 | 39 | 44 |

## Limites

- **Shader packs.** O grafo de mundo do Direct (Lus/Solaris), a transparência DX12 e as sombras do pack ainda importam a cena do OpenGL. Ler a cena direto do backend é a fase 3.
- **Clima:** a cena de chuva usa um bioma sem chuva, então gotas e respingos não foram comparados.
- **Fora desta fase:** o contorno de entidades brilhantes (`PostChain`), o Fabulous e o glint são da fase 3.
- **Contexto OpenGL:** mesmo no modo só DX12, texturas, estado e framebuffers continuam sendo criados no OpenGL. O renderizador de terreno continua criando os recursos D3D11On12 de interop; eles só não são usados por quadro.
- **Desempenho:** o backend executa e espera cada quadro de forma síncrona, e o desempenho ainda não foi medido no harness de 1080p. Isso fica para a fase 3, junto com os packs, contra o Sodium + Iris + Mellow.
- **Entradas `UV1`/`UV2` declaradas como float:** alguns shaders vanilla as declaram assim e recebem inteiros de 16 bits, mas nunca as leem. O OpenGL deixa esse caso indefinido; o D3D12 reinterpreta os bits. Sem efeito na imagem.
