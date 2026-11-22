# Direct — arquitetura DX12 total (caminho C): preparação

## Por que

Medição de 21–22/11/2026 ([comparacao-sodium-iris.md](comparacao-sodium-iris.md)), no mundo do usuário, Intel UHD 630, 1080p, sem VSync:

| Configuração | ms por quadro |
| --- | ---: |
| Sodium + Iris + Mellow | 15–17 |
| Direct sem pack | 17,5 |
| Direct + Raydiance + Lus | 34–36 |

O quadro do Direct é limitado pela GPU. A imagem passa entre OpenGL e DX12 quatro vezes por quadro. Cada passagem custa de 2 a 3 ms nessa GPU:

| Passagem | Custo |
| --- | --- |
| Composite do terreno | lock 2,2 ms + unlock ~3 ms |
| Captura do mundo | ~4 ms |
| Importação no D3D12 (`ReleaseWrappedResources` + `Flush`) | 1,7 ms |
| Apresentação | ~2,3 ms |

Otimizações dentro da arquitetura híbrida não mudaram o FPS. O caminho C remove a ponte: o Direct desenha o quadro inteiro, inclusive a GUI, e apresenta por DXGI.

## Objetivo e critério de sucesso

- Direct + Raydiance + Lus (opções do usuário) acima do Sodium + Iris + Mellow no FPS médio, na mesma cena e com as mesmas opções, medido com `docs/bench/run-case.ps1`.
- Imagem igual à do Minecraft vanilla, exceto onde o pack muda de propósito, verificada por comparação de capturas.
- O modo híbrido atual continua existindo e é o fallback. O modo novo é opt-in até ser validado.

## O que precisa ser substituído

Inventário das fontes do NeoForge 21.1.265 / Minecraft 1.21.1:

- **Camada `com.mojang.blaze3d`:** 75 arquivos. `GlStateManager` tem 123 métodos públicos e `RenderSystem`, 130. Todo desenho do vanilla passa por eles, por `VertexBuffer` e `BufferUploader` (`MeshData`), por `ShaderInstance` e `EffectInstance`, por `RenderTarget`, `TextureUtil` e `NativeImage.upload`, e por `Window` (swap).
- **Fora dessa camada:** só 12 arquivos usam OpenGL direto, a maioria constantes de estado: `Gui`, `UnihexProvider`, `LoadingOverlay`, `WinScreen`, `ParticleEngine`, `EffectInstance`, `GameRenderer`, `LevelRenderer`, `RenderStateShard`, `ShaderInstance`, `AbstractTexture` e `NeoForgeLoadingOverlay`.
- **Shaders vanilla:** 56 shaders principais (`shaders/core`, 55 pares `.vsh`/`.fsh`), 4 includes e 24 programas de pós-processamento (`shaders/program`). Resource packs e mods podem trocá-los.
- **Janela:** criada pelo early display do NeoForge com contexto OpenGL 4.6 (`ImmediateWindowHandler.setupMinecraftWindow`, `earlyWindowControl = true`). O Minecraft reaproveita essa janela e chama `GL.createCapabilities()`.

O terreno DX12, o atlas, a lightmap, o grafo de mundo (API 1.7) e os programas de pack do Direct já existem e são reaproveitados.

## Desenho

Um backend DX12 na fronteira do `blaze3d`, a mesma técnica do VulkanMod com Vulkan: mixins nos pontos de entrada substituem as chamadas OpenGL por equivalentes D3D12, mantendo a API que o vanilla e os mods usam.

| Camada | Abordagem |
| --- | --- |
| Estado (`GlStateManager`) | Espelho de estado em Java (blend, profundidade, cull, máscara de cor, scissor, polygon offset, bindings). O estado vira a chave de um cache de PSOs; nenhuma chamada GL. |
| Shaders | GLSL do vanilla → SPIR-V (glslang) → HLSL (SPIRV-Cross) → `D3DCompile`/DXC, com cache em disco como o atual. Uniforms viram cbuffers com o layout do `ShaderInstance`. Shaders de resource packs são traduzidos na hora do carregamento. Ferramentas disponíveis na máquina: `glslangValidator` e `spirv-cross` (Vulkan SDK 1.4.363) e `dxc` (Windows SDK 10.0.26100). No mod final, as bibliotecas vão embutidas na DLL. |
| Buffers | `VertexBuffer.upload(MeshData)` e `BufferUploader` → buffers D3D12 num anel de upload. Os formatos de vértice do vanilla viram input layouts. |
| Texturas | `TextureUtil.prepareImage`, `NativeImage.upload` e `_texSubImage2D` → texturas D3D12, com mipmaps e filtros como em GL. IDs inteiros continuam como handles para o código Java. |
| Render targets | `RenderTarget` → RTV/DSV D3D12. Pós-processamento (`PostChain`, contorno de entidades, Fabulous) com os mesmos passes. |
| Apresentação | Swap chain DXGI (flip model) no HWND da janela GLFW, no lugar do `glfwSwapBuffers`. VSync, resize e tela cheia passam pelo DXGI. |
| Terreno e pack | O terreno DX12 atual desenha direto no render target principal, sem composite. O grafo de mundo lê a cena sem captura, sem importação e sem apresentação de volta. |

## Riscos principais

1. **Janela com contexto GL.** É preciso apresentar por DXGI num HWND que já tem formato de pixel e contexto OpenGL, criados pelo early display. Alternativa: desligar o early display ou recriar a janela. Validar primeiro.
2. **Mods que chamam OpenGL direto** fora do `RenderSystem` (por exemplo Flywheel/Create, Distant Horizons, ImmediatelyFast, e qualquer `glXxx` via LWJGL). No modo novo eles quebram. Política: detectar e cair para o modo híbrido, com aviso, em vez de desenhar errado.
3. **Exatidão da tradução de shaders:** precisão, `texelFetch`, origem de coordenadas (GL embaixo, D3D em cima), profundidade de −1..1 para 0..1, alinhamento de uniforms. Cada shader vanilla precisa de um teste de pixels contra o OpenGL.
4. **Tamanho:** é a maior mudança do projeto, estimada em meses. Por isso as fases abaixo têm portões de decisão por medição.
5. **Docs atuais:** os contratos do modo híbrido (D3D11On12, locks, composite) continuam valendo para o modo híbrido. O modo novo ganha docs próprias.

## Fases e portões

| Fase | Entrega | Portão para seguir |
| --- | --- | --- |
| 0. Spike de viabilidade | Swap chain DXGI apresentando no HWND do NeoForge (com o contexto GL presente). Os 56 shaders principais traduzidos e compilados automaticamente. Um benchmark sintético com o quadro estimado: terreno DX12 + passe do Lus + uma GUI simples, sem nenhuma passagem GL. | Apresentação estável (resize, tela cheia, VSync). 56/56 shaders compilam. Teto medido abaixo de ~15 ms por quadro na cena do usuário. |
| 1. GUI e menus | Backend de estado, buffers, texturas e shaders suficiente para tela de título, menus, HUD e texto. | Pixels iguais ao OpenGL nos testes de tela; menus navegáveis. |
| 2. Mundo | Entidades, block entities, partículas, céu, nuvens, clima, mão, contorno e destruição de blocos, sobre o terreno DX12 existente. | Capturas comparadas ao vanilla em cenas fixas; harness sem regressão. |
| 3. Pós e packs | `PostChain`, Fabulous, glint, e o grafo de mundo do Direct lendo a cena direto. Lus e Solaris sem mudanças. | Lus/Solaris com a mesma imagem do modo híbrido; FPS medido contra Sodium + Iris + Mellow. |
| 4. Compatibilidade e entrega | Detecção de mods que usam GL direto, fallback para o modo híbrido, opção no painel, docs e validação em jogo. | Sessão longa no modpack real sem falhas. |

## Próximo passo

A fase 0 passou nos três portões (22/11/2026): apresentação DXGI estável na janela do NeoForge, 56/56 shaders traduzidos e compilados, e 6,0–6,2 ms de GPU para terreno + Lus + GUI simples sem OpenGL. Detalhes em [fase0-dx12-total.md](fase0-dx12-total.md).

A fase 1 passou (22/11/2026). O título e cinco menus foram renderizados pelo backend DX12 com pixels iguais aos do OpenGL (diferença máxima de 1/255 nos menus; no título, só nas bordas do texto girado). Com os desenhos do OpenGL cancelados e a apresentação pelo DXGI, a tela mostrou a imagem do DX12 em 18 de 18 verificações. Detalhes em [fase1-dx12-total.md](fase1-dx12-total.md).

A fase 2 está em andamento (22/11/2026); detalhes em [fase2-dx12-total.md](fase2-dx12-total.md).

- **Pronto e aprovado no portão:** o mundo vanilla inteiro passa pelo backend DX12, com pixels equivalentes aos do OpenGL em 11 cenas fixas: terreno, céu, nuvens, entidades, mão, contorno, quebra de blocos, partículas, HUD, inventário e pausa.
- **Falta:** pôr o terreno DX12 do Direct sob esses desenhos. Isso pede o mesmo dispositivo D3D12 para o terreno e o backend, uma fence entre as filas e um composite em DX12 no lugar do composite OpenGL.
