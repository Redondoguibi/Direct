# Grafo de mundo — Direct 1.9.0 / API 1.7

`DirectRenderService.renderWorld(String[] sources, float[] uniforms)` continua sendo a única chamada. Diretivas em linhas próprias das fontes HLSL descrevem o grafo; um passe sem diretivas mantém exatamente o contrato da API 1.5 (cadeia de cor com ping-pong, t0–t2). O Raydiance 1.9 gera essas diretivas a partir do manifesto (ver o `shaderpacks-1.9.md` do Raydiance).

## Diretivas

| Diretiva | Efeito |
| --- | --- |
| `// direct:target=rgba16f` | Na primeira linha: a saída de cor da cadeia desse passe é RGBA16F (como antes). |
| `// direct:buffer <0-7> <formato> [scale=<0.125-1>] [history] [clear]` | Declara um buffer do pack. Formatos: `rgba8`, `rgba16f`, `rgba32f`, `r11g11b10f`, `rg16f`, `r16f`, `r32f`, `rgba16`, `rg8`, `r8`. `scale` é relativo à janela. `history` mantém o quadro anterior legível; `clear` zera o buffer no início de cada quadro. Sem `clear`, o conteúdo persiste entre quadros. Todo passe que declara um buffer precisa declará-lo igual. |
| `// direct:outputs color,<i>,...` | Até quatro render targets (`SV_Target0..3`). `color` é a saída da cadeia e, se presente, vem primeiro. Todas as saídas de um passe têm o mesmo tamanho. Sem a diretiva: só `color`. Um passe que não escreve `color` não avança a cadeia. |
| `// direct:compute` | Executa `main` como compute shader, com o `numthreads(x, y, 1)` do próprio HLSL, sobre o tamanho das saídas, que precisam ser buffers com escrita UAV tipada na GPU. |
| `// direct:terrain-depth` | Mantém uma cópia da profundidade só do terreno (antes de entidades, partículas e translucidez), em t3. Custa uma cópia de profundidade por quadro. |
| `// direct:fullscreen-vertex` | Vertex shader próprio `passVertex` (como na 1.8.2). |

O último passe precisa escrever `color` em RGBA8 e não pode ser compute. Até 16 passes. Erros chegam como `IllegalArgumentException` com o passe e o motivo; a cadeia anterior continua ativa.

## Bindings

| Registro | Conteúdo |
| --- | --- |
| t0 | Entrada da cadeia: cor original no primeiro passe que lê a cadeia, depois a última saída `color` |
| t1 | Profundidade da cena (1 = céu) |
| t2 | Cor original do estágio |
| t3 | Profundidade só do terreno (`terrain-depth`) |
| t4–t11 | Buffers 0–7 no estado atual. O buffer que o próprio passe escreve aparece vazio |
| t12–t19 | Buffers 0–7 do quadro anterior quando `history`; senão o conteúdo atual |
| u0–u7 | Buffers escritos por um passe compute |
| b0 | `float4 DirectFrame` (tempo, largura, altura, quadro) + 252 floats por passe |
| b1 | `float4 DirectTarget` (largura, altura, 1/largura, 1/altura da saída) e `float4 DirectBufferSize[8]` |
| s0 / s1 | Linear e ponto, ambos com clamp |

Para retroalimentação (TAA, acumulação), escreva o buffer `history` e leia o quadro anterior em t12+i.

## Céu e nuvens

`worldReplacesVanilla(boolean sky, boolean clouds)` faz o Direct pular o céu vanilla (cúpula, sol, lua e estrelas) e/ou as nuvens enquanto a etapa de mundo roda; eles voltam no quadro seguinte a uma falha ou à saída do pack. O pack desenha o céu nos pixels com profundidade 1.

## Entidades e partículas

Entidades, partículas, mão e GUI são desenhados pelo OpenGL do Minecraft; o Direct renderiza só o terreno em DX12. Programas próprios para eles exigiriam que o Direct capturasse e renderizasse esses desenhos nativamente, o que não existe. Hoje, `terrain-depth` permite separar na tela terreno (profundidade da cena igual à do terreno), entidades/partículas/translucidez na frente (cena mais próxima) e céu (profundidade 1).

## Exemplo

```hlsl
// direct:buffer 0 rgba16f scale=0.5 clear
// direct:buffer 1 rgba16f history
// direct:outputs color,1
Texture2D<float4> Previous : register(t0);
Texture2D<float4> Half : register(t4);
Texture2D<float4> AccumulatedBefore : register(t13);
SamplerState LinearClamp : register(s0);
cbuffer Frame : register(b0) { float4 DirectFrame; float4 Data[63]; };
struct Output { float4 color : SV_Target0; float4 accumulated : SV_Target1; };
Output main(float4 p : SV_Position) {
    float2 uv = p.xy / DirectFrame.yz;
    float4 now = Previous.Load(int3(int2(p.xy), 0)) + Half.SampleLevel(LinearClamp, uv, 0);
    Output o;
    o.accumulated = lerp(AccumulatedBefore.Load(int3(int2(p.xy), 0)), now, 0.1);
    o.color = o.accumulated;
    return o;
}
```

Validação: `NativeWorldGraphInteropTest` executa os passes em D3D12 real com as duas pontes de profundidade (contrato antigo, MRT, buffer em meia resolução, compute, histórico por três quadros, profundidade do terreno e erros de configuração). O desenho em jogo de um pack que use esses recursos ainda não foi testado.
