# Fase 0 do Direct DX12 total — resultado (22/11/2026)

Plano e portões: [arquitetura-dx12-total.md](arquitetura-dx12-total.md). Linha de base: [comparacao-sodium-iris.md](comparacao-sodium-iris.md). Máquina: Intel UHD 630, i3-10105, mundo "Sodium X Direct 1_9_1".

## Portão

| Item | Critério | Resultado |
| --- | --- | --- |
| Apresentação DXGI na janela do early display do NeoForge | Estável em janela, resize, tela cheia e VSync | **Passou**: 7/7 estágios, duas vezes, com os pixels do desktop iguais às cores apresentadas |
| Shaders vanilla | 56/56 compilam | **Passou**: 56/56 programas ligados, 112 estágios DXIL, em duas execuções |
| Teto do quadro sem OpenGL | Abaixo de ~15 ms por quadro | **Passou**: 6,0–6,2 ms de GPU para terreno + Lus + GUI simples |

A fase 1 está liberada.

## 1. Apresentação DXGI

`docs/bench/run-present-probe.ps1` abre um cliente de desenvolvimento só com o Direct e `-Ddirect.phase0.present=true`. Na tela de título, `PhaseZeroWindowMixin` assume o `Window.updateDisplay` (o mixin só entra com essa propriedade; ver `DirectMixinPlugin`). `PhaseZeroPresentation` e `phase0_present.cpp` então:

- criam um dispositivo D3D12 próprio e uma swap chain flip-model (`FLIP_DISCARD`, 2 buffers, tearing quando disponível) no HWND da janela GLFW, que já tem contexto OpenGL 4.6 do early display;
- apresentam duas cores por estágio e, depois de `DwmFlush`, leem os pixels reais do desktop. O teste só passa se a tela mostrar o que o DXGI apresentou.

Estágios, todos com `desktopPixels=true`:

| Estágio | Modo | Framebuffer | VSync |
| --- | --- | --- | --- |
| 0 | janela | 960×540 | não |
| 1 / 4 | resize | 720×405 | sim |
| 2 / 5 | tela cheia | 1920×1080 | não / sim |
| 3 / 6 | janela restaurada | 960×540 | não |

O contexto GL continua corrente durante a prova, e nenhum `glfwSwapBuffers` é chamado. O risco 1 do plano está descartado para essa máquina.

## 2. Tradução dos shaders vanilla

`docs/bench/shaders/translate-core.py` lê os 56 descritores de `shaders/core` do jar de recursos do Minecraft 1.21.1 e, para cada programa:

1. resolve os `#moj_import`;
2. converte os uniforms para um bloco std140 na ordem do descritor e fixa locations e bindings;
3. liga o par com `glslangValidator`;
4. gera HLSL SM 6.0 com `spirv-cross --hlsl --fixup-clipspace`;
5. compila com `dxc -O3`;
6. confere pela reflexão que o layout dos uniforms, os bindings dos samplers e a interface entre os estágios batem com o descritor.

Resultado: 56/56 programas e 112 estágios DXIL, em duas execuções. A saída fica em `build/phase0-shaders/run-*/report.json`, com os hashes do jar e dos compiladores.

Ainda não foi verificado: a equivalência de pixels contra o OpenGL. Isso é trabalho da fase 1, com um teste por shader.

## 3. Teto do quadro sem OpenGL

**Captura.** `run-case.ps1 -CapturePhase0` roda o jogo com `-Ddirect.phase0.capture=true`. `Phase0Capture` grava em `logs/phase0-scene.bin` o que o renderizador híbrido já aceitou nesse quadro, depois de 60 s com pack de mundo ativo e nenhuma seção no vanilla: as malhas das seções visíveis, o atlas com as atualizações de animação, a lightmap, a câmera, as opções e a geometria do pack, e as fontes e uniforms do passe de mundo.

Cena gravada: 310 seções, 511.644 índices, um passe de mundo (Lus com as opções salvas do usuário), 19 MB. Nessa execução o jogo híbrido fez 25,9–26,1 FPS.

**Replay.** `docs/bench/run-phase0-replay.ps1` carrega a cena num dispositivo D3D12 isolado (`Phase0FrameNative`, `phase0_frame.inl`), sem contexto GL, sem D3D11On12 e sem superfície compartilhada. Cada quadro é uma submissão D3D12 ordenada: terreno, passe do Lus lendo a cor e a profundidade nativas, e uma GUI simples (hotbar e mira, 20 quads). O tempo vem de timestamps de GPU.

| Janela (60 s) | Quadros | GPU média | GPU p50 | GPU p95 | Terreno | Passe do Lus | GUI |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 8.542 | 6,19 ms | 6,11 | 6,70 | 2,70 | 3,48 | 0,01 |
| 2 | 9.026 | 6,06 ms | 6,02 | 6,44 | 2,62 | 3,43 | 0,01 |
| 3 | 8.328 | 6,25 ms | 6,14 | 7,04 | 2,74 | 3,49 | 0,02 |

Os índices submetidos batem com os da captura em todo quadro, e a profundidade cobre 1.821.375 pixels de terreno.

**Comparação com o jogo híbrido, mesma cena:** o terreno custa 6,9 ms e o passe do Lus 7,35 ms (7,6 ms no `LusPassBench`, que também usa as texturas compartilhadas). Fora da ponte, os mesmos dois passes custam menos da metade.

A causa não foi isolada. Hipóteses:
- superfícies compartilhadas com o OpenGL e envoltas pelo D3D11On12 perdem a compressão de render target da GPU Intel;
- os quadros híbridos alternam GPU ocupada e parada, e o clock não sobe.

Fica para investigar, porque também pode valer para o modo híbrido.

**Estimativa do quadro completo, não medida.** Somam-se:
- o replay: 6,1 ms;
- o que ele ainda não desenha (céu, entidades, block entities, partículas, nuvens, clima, mão, GUI real e o `Present`). Hoje tudo isso, terreno incluído, cabe nos 4,0 ms do quadro vanilla em OpenGL; estimativa de 1–3 ms.

O quadro de GPU fica em ~7–9 ms. Os ~3–4 ms de CPU do Minecraft podem se sobrepor à GPU quando não há espera de sincronização. Nessa sessão, o Sodium + Iris + Mellow mediu 57,3–57,5 FPS (~17,4 ms) na mesma cena. É um teto promissor, não um FPS do Direct.

## Correções feitas ao fechar a fase

- **Build desatualizado.** O projeto fica numa pasta sincronizada pelo Google Drive. O monitoramento de arquivos do Gradle perdeu edições, e o cache de build devolveu classes antigas: o `DirectMixinPlugin` sem o guarda do mixin da prova, e os ganchos da captura. Com isso, a prova de apresentação rodava em qualquer cliente. `gradle.properties` agora tem `org.gradle.vfs.watch=false`.
- **Tamanho da janela.** O harness pede 1920×1080, mas o Windows limita a janela à área de trabalho: 1920×1061, em todas as execuções de ontem e de hoje, nas duas pilhas. O replay agora aceita o tamanho gravado.
- **`run-present-probe.ps1`.** No Windows PowerShell 5.1 o `ExitCode` de um `Start-Process -PassThru` volta nulo sem abrir o handle do processo. O script lia "falha" mesmo com `PASS` no log.
- **Medição inválida descartada.** Uma execução do Sodium feita hoje com o Minecraft do usuário aberto em paralelo deu 25–32 FPS. Ela foi descartada e refeita com a máquina livre: 57,3–57,5 FPS.

## Validação

`build`, `test` (61), `graphicsTest` (56) e `nativeSmokeTest` (1/1) passam. O código da fase 0 só roda com `-Ddirect.phase0.present=true` ou `-Ddirect.phase0.capture=true`. Sem essas propriedades, o modo híbrido não muda.
