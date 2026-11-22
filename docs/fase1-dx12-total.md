# Fase 1 do Direct DX12 total — GUI e menus (22/11/2026)

Plano: [arquitetura-dx12-total.md](arquitetura-dx12-total.md). Fase anterior: [fase0-dx12-total.md](fase0-dx12-total.md).

## Portão

| Critério | Resultado |
| --- | --- |
| Pixels iguais ao OpenGL nos testes de tela | **Passou.** Título e 5 menus (opções, vídeo, idioma, sons, controles), 3 quadros cada. Nos menus, diferença máxima de 1/255 em 0,4–0,7% dos pixels. No título, 0,05–0,06% dos pixels diferem mais que 2/255, todos nas bordas do texto amarelo girado (detalhes abaixo). |
| Menus com o DX12 na tela | **Passou.** Com os desenhos do OpenGL cancelados e a apresentação pelo DXGI, 64 de 64 pixels do desktop iguais à imagem do DX12, em 18 de 18 verificações. |

Validação: `build`, `test` (61), `graphicsTest` (56) e `nativeSmokeTest` (1/1) passam. O modo híbrido não muda: o código da fase 1 só entra com as propriedades abaixo.

## O que foi feito

**Backend DX12** (`src/main/cpp/dx12_backend.cpp`). Usa um dispositivo D3D12 próprio, separado do renderizador híbrido. Ele reproduz o que o Minecraft pede ao `blaze3d`, na mesma ordem e numa única lista de comandos:

- **Texturas pelo id do OpenGL.** Suporta RGBA8, R8, RG8, `D32` e `D32S8`, com mipmaps, filtros, wrap, LOD bias, `UNPACK_ROW_LENGTH`/`SKIP` e expansão de RGB para RGBA.
- **Framebuffers** viram render targets e depth stencils, com limpeza respeitando o scissor.
- **Programas** são compilados com `D3DCompile` (SM 5.1) a partir do HLSL gerado.
- **PSOs** ficam em cache, indexados por programa, layout de vértices, blend, profundidade, cull, máscara de cor, topologia, formatos e polygon offset.
- **Dados de cada desenho** (vértices, índices e cbuffer) passam por um anel de upload.

**Convenção de imagem.** Os vertex shaders invertem o Y. Com isso, toda imagem D3D12 guarda as linhas de baixo para cima, como o OpenGL, e os retângulos de viewport e scissor do GL valem sem conversão. Pela mesma razão, a face da frente fica horária para o D3D, que é o padrão dele. A apresentação desfaz a inversão.

**Shaders** (`docs/bench/shaders/translate-runtime.py`). Gera 65 programas: 56 de `shaders/core` e 9 de `shaders/program` (pós-processamento, como o `box_blur` do desfoque dos menus). O caminho é GLSL → SPIR-V (`glslangValidator`) → HLSL SM 5.0 (`spirv-cross`, `--fixup-clipspace --flip-vert-y`), validado com o `fxc`. A saída fica em `src/main/resources/assets/direct/dx12/`, com um `program.json` por programa contendo as entradas de vértice, os offsets std140 do cbuffer e a ordem dos samplers. O `spirv-cross` descarta os espaços de registrador no SM 5.0, então tudo fica no espaço 0 (`b0`, `t0..t11`, `s0..s11`).

**Ganchos Java** (`com.redondoguibi.direct.backend`, mixins `*ShadowMixin`):
- `GlStateManager`: estado, texturas, pixel store, framebuffers e limpezas;
- `VertexBuffer`: cópia da malha a cada `upload` (incluindo os índices sequenciais de `QUADS`/`LINES` e a conversão de `TRIANGLE_FAN`) e o desenho a cada `draw`;
- `ShaderInstance`/`EffectInstance`: programa aplicado, uniforms e samplers resolvidos pelo nome;
- `Window`/`RenderSystem`: fim do quadro e troca do `glfwSwapBuffers` pela apresentação DXGI.

Propriedades:

| Propriedade | Efeito |
| --- | --- |
| `-Ddirect.phase1.shadow=true` | Espelha tudo no DX12; o OpenGL continua desenhando e apresentando |
| `-Ddirect.phase1.probe=true` | Roteiro de telas com comparação de pixels e PNGs em `phase1/` |
| `-Ddirect.phase1.present=true` | O DXGI apresenta a imagem do DX12 no lugar do back buffer do OpenGL |
| `-Ddirect.phase1.skipGl=true` | Com `present`: cancela os desenhos do OpenGL, e só o DX12 renderiza |
| `-Ddirect.phase1.debug=true` | Camada de depuração do D3D12; as mensagens vão junto dos erros de PSO |
| `-Ddirect.phase1.trace=true` | Com `probe`: grava cada limpeza e desenho do quadro comparado em `phase1/*-trace.txt` |

Scripts:
- `docs/bench/run-shadow-probe.ps1` (`-Present`, `-SkipGl`, `-Trace`, `-DebugLayer`);
- `docs/bench/run-dx12-menus.ps1`, que abre os menus só em DX12 para navegação manual.

## Resultados

**Modo sombra** (OpenGL desenha e apresenta; o DX12 repete tudo), 1280×720:

| Tela | Desenhos DX12 acumulados | Diferença máxima | Pixels diferentes | Mais de 2/255 |
| --- | ---: | ---: | ---: | ---: |
| Título | 4.299–4.410 | 175–255 | 0,22–0,30% | 0,05–0,06% |
| Opções (com desfoque) | 7.811 | 1 | 0,57% | 0 |
| Vídeo | 12.261 | 1 | 0,42% | 0 |
| Idioma | 15.155 | 1 | 0,66% | 0 |
| Sons | 19.689 | 1 | 0,43% | 0 |
| Controles | 22.429 | 1 | 0,62% | 0 |

As diferenças de 1/255 são arredondamento do desfoque. As do título estão nas bordas do texto amarelo girado e pulsante (splash), desenhado com `rendertype_text`, teste de alfa e amostragem pontual. Ali os pontos de borda caem de lados diferentes nas regras de rasterização de cada API. O resto da tela do título difere em no máximo 1/255.

**Modo apresentação só com DX12** (`present` + `skipGl`, janela visível): as 18 verificações de desktop deram 64/64. As imagens `*-dx12only.png` (título com logo, botões, texto e panorama, e vídeo com o fundo desfocado) estão completas.

## Erros encontrados e corrigidos no caminho

1. **Espaços de registrador.** PSOs falhavam com `E_INVALIDARG`. A camada de depuração mostrou que os shaders usavam o espaço 0, e não o 1 (o `spirv-cross` em SM 5.0 descarta os espaços). A assinatura raiz passou para o espaço 0.
2. **Orientação das faces.** Com o Y invertido, o anti-horário do GL chega ao D3D como horário. `FrontCounterClockwise = TRUE` descartava toda a GUI e o quadrilátero dos passes de desfoque, todos com cull ligado; o certo é `FALSE`.
3. **Roteiro da prova.** A tela de mundos abria "Criar mundo" sozinha numa pasta vazia, e isso renderizava quadros antes de o `setScreen` retornar. Os contadores agora são zerados antes, e as telas são outras.
4. **Build.** A pasta do projeto, sincronizada pelo Google Drive, trava arquivos novos em `build/`. O `processResources` falha na primeira tentativa depois de mudar recursos, e os resultados de teste em `build/test-results` ficam presos. A validação rodou com um init script que grava os resultados de teste em `%TEMP%\direct-test-results` (mesma solução da doc da ativação 1.7.4).

## Limites

- Só o título e os menus foram testados. O HUD e tudo dentro de um mundo são a fase 2.
- Com `skipGl`, só os desenhos são cancelados. Texturas, estado e framebuffers do OpenGL continuam sendo criados e atualizados, e o contexto GL continua existindo. Não há ganho de desempenho medido nesta fase: o modo sombra é síncrono por design.
- Os shaders são os vanilla do 1.21.1 pré-traduzidos. Shaders de resource packs e de mods ainda não são traduzidos em tempo de execução, e esses desenhos são pulados. Vale o mesmo para chamadas OpenGL que não passam pelo `GlStateManager`.
- Ainda não implementados: `glLogicOp` (usado no realce de seleção de texto), espessura de linha, formatos de textura além dos listados e estados de stencil.
