# Shaderpacks nativos — Direct 1.7.0 / API 1.5 / Raydiance 1.6.0

Com pack selecionado, o Raydiance carrega dados e envia parâmetros ao Direct. O compositor, SSAO, bloom, filtros e código do pack executam em HLSL/C++ após a cena completa, incluindo entidades e transparências, antes da mão/HUD. Os efeitos OpenGL do Raydiance, sombras OpenGL e overrides de névoa não executam nesse modo. Sem pack, os controles próprios continuam no caminho OpenGL anterior; o Raydiance libera os pedidos de efeitos de geometria DX12.

Os valores do pack e os ajustes individuais de seu perfil têm prioridade sobre os valores gerais, Effects, Use pack defaults e perfis dinâmicos OpenGL. Sliders numéricos, qualidade e FXAA com pack selecionado editam valores do pack, salvos em `pack-options`. Presets não alteram os ajustes OpenGL. Cancelar restaura o snapshot; remover o pack volta aos ajustes próprios do Raydiance.

O Lus foi portado em `Direct Group/Lus/lus/direct.hlsl`. Seus 32 controles próprios são enviados como uniforms, além de cor, bloom, SSAO, atmosfera, filtros, matrizes e contexto. Água, vento, sombras e materiais são pedidos de geometria nativa existentes. A névoa do terreno usa o multiplicador do pack no Direct. Sombras nativas não são duplicadas no compositor. O bloom nativo usa um filtro de extração de nove amostras; não reproduz exatamente o antigo filtro separável OpenGL. A aparência precisa de validação visual.

Packs com GLSL personalizado precisam fornecer `direct.hlsl` na pasta. Não há tradução geral de GLSL em runtime. Packs somente de parâmetros podem usar o compositor HLSL interno. Pack incompatível ou Direct indisponível gera diagnóstico e deixa de aplicar os efeitos do pack; não há fallback silencioso para shaders OpenGL. Direct deve estar em modo de substituição. Fabulous e alvos customizados incompatíveis não entram nessa etapa.

## API

`DirectRenderService.renderWorld(String[] sources, float[] uniforms)` deve ser chamado na thread de render após a cena e antes da mão/HUD. Aceita 1–8 passes, com 252 floats por passe. Retorna se a cena atual foi processada. `worldFailure()` informa rejeição. Passes de terreno anteriores continuam existindo e recebem o mesmo prefixo de uniforms; os buffers nativos agora têm 1024 bytes por passe.

Cada HLSL usa `Texture2D<float4> DirectInput : register(t0)`, profundidade original em `Texture2D<float> ... : register(t1)` e sampler `s0`. O CBV `b0` contém `float4 DirectFrameData` (tempo, largura, altura, frame), seguido de `float4 DirectUniforms[63]`. O contrato de world passa tempo zero no cabeçalho; tempo real de mundo é um parâmetro do Raydiance. Os alvos alternam entre duas texturas para evitar leitura/escrita simultânea. O original de profundidade não muda entre os passes.

O bloco do Raydiance usa 0–23 para os controles gerais (ordem em `NativeShaderPacks.KEYS`), 32–63 para opções próprias na ordem do manifesto, 64–127 para matrizes e 128–146 para contexto. As matrizes enviadas seguem o armazenamento column-major do JOML. O HLSL Lus converte UVs lógicos bottom-left para as texturas nativas top-left. Não são expostos ponteiros nativos.

## Limites e compilação

Essa implementação ainda transfere a cena OpenGL completa para DX12 e devolve o resultado. Os cálculos dos efeitos são nativos; a ponte de cor/profundidade exige sincronização e tem custo. Isso não constitui garantia de aumento de FPS.

Compilados Java, DLL e fontes HLSL via FXC. O FXC emitiu avisos de possível variável não inicializada em funções dos shaders portados; compilação não elimina essa incerteza. Não foram executados testes, Minecraft ou benchmarks, conforme a restrição da sessão. Os JARs e o pack não foram instalados automaticamente.
