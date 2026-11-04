# Direct 1.4.2 — compartilhamento da cor do DX12 Terrain

## Evidência e alcance

Esta mudança trata da primeira opção do Direct, `enableDx12Preview` (DX12 Terrain),
inclusive com shaders desligadas. Não depende de habilitar Raydiance ou Lus.
Na sessão 1.4.1, as janelas de diagnóstico continuaram próximas de 21–26 FPS,
com espera DX12 média de aproximadamente 14–24 ms. O tempo GPU informado foi
aproximadamente 8 ms nessas duas janelas; esses tempos não são aditivos nem uma
comparação controlada com vanilla. Custo de composição/transparência também existe.

As mesmas janelas registraram zero quadros descartados por invalidação de chunks e
poucas seções em fallback. Isso sustenta a correção de propriedade por seção da 1.4.1,
mas não encerra a investigação de desempenho nem prova ausência de travamentos no jogo.

## Implementação

- A textura nativa de cor agora pode ser registrada diretamente no WGL por seu
  `ID3D11Texture2D` produzido pelo D3D11On12. A ponte continua existindo; não se trata
  de importação direta de memória D3D12 pelo OpenGL.
- A aquisição pelo D3D11On12 ocorre antes do registro. O driver precisa expor o
  armazenamento real, com largura e altura corretas, antes de essa rota ser aceita.
  Registro WGL bem-sucedido sozinho não certifica uma textura utilizável.
- Não há cópia de exportação da cor nessa rota. Quando a etapa translúcida importa
  a cena GL, a cópia de importação da cor também é dispensada. Profundidade continua
  usando a ponte copiada R32_FLOAT. Resolução e qualidade dos efeitos são preservadas.
- A textura intermediária de cor deixa de ficar alocada: 8.294.400 bytes em 1920×1080,
  aproximadamente 7,91 MiB, sem contar alinhamentos internos do driver.
- A aquisição da cor é mantida enquanto GL usa a imagem, e liberada antes de DX12
  reutilizá-la. As fences, GLsync, locks WGL e waits necessários continuam ativos.
  Resize e desligamento drenam a liberação antes de destruir recursos.
- `preferDirectColorInterop=true` seleciona essa tentativa na inicialização/resize.
  Registro rejeitado ou dimensões incompatíveis preservam a ponte copiada, com
  motivo no log. Desativar essa preferência permite selecionar a ponte anterior.
  Falhas do backend continuam liberando terreno vanilla.
- A API pública permanece 1.3.0. Raydiance 1.4.0 e Lus não foram alterados nesta etapa.

## Alternativas investigadas

O compartilhamento da profundidade nativa foi rejeitado: no driver Intel testado,
o WGL registrou o objeto, mas expôs largura zero em vez do armazenamento de depth.
Não foi ativado para simplesmente eliminar a mensagem de erro.

Uma alternativa com Unwrap/Return e cópias na fila D3D12 passou funcionalmente, mas
não teve ganho consistente na comparação alternada. Ficou fora da implementação.
Remover o wait explícito também não demonstrou vantagem: o lock WGL esperou o
trabalho pendente. A espera explícita foi mantida.

As importações GL_EXT da memória/fence D3D12 continuam rejeitadas com 0x500 neste
driver, apesar das extensões anunciadas. A causa interna do driver não foi demonstrada.
Esta mudança não declara esse problema universalmente corrigido.

## Medição isolada

Intel UHD 630, 1080p, 16 quads sobrepostos, 30 quadros de aquecimento e 120 amostras.
Execuções alternadas A/B/B/A; wall mede submissão até a fence, sem composição Minecraft.

| Rota | GPU p50 (ms) | GPU p95 (ms) | Wall p50 (ms) | Wall p95 (ms) |
| --- | ---: | ---: | ---: | ---: |
| Copiada A1 | 10,2240 | 12,4010 | 11,1295 | 12,9837 |
| Compartilhada B1 | 9,5967 | 11,2787 | 10,6575 | 11,8187 |
| Compartilhada B2 | 9,5805 | 11,2128 | 10,7684 | 11,7685 |
| Copiada A2 | 11,3297 | 12,4608 | 11,9903 | 12,9993 |

Resultado modesto, com variação entre execuções. Não permite converter a diferença
em ganho de FPS no modpack, nem afirmar que o problema de 25 FPS está resolvido.
Dados brutos: `archive/color-alias-measurements.txt`. Para reproduzir, execute uma
rota de cada vez, sem Minecraft aberto:

```powershell
.\gradlew.bat --init-script docs/terrain-benchmark.gradle nativeDepthCostBenchmark '-PbenchmarkLibrary=C:\Criando Mods\mod\Direct\build\generated\nativeResources\META-INF\native\windows-x86_64\direct_renderer.dll' -PbenchmarkColorAlias=false
.\gradlew.bat --init-script docs/terrain-benchmark.gradle nativeDepthCostBenchmark '-PbenchmarkLibrary=C:\Criando Mods\mod\Direct\build\generated\nativeResources\META-INF\native\windows-x86_64\direct_renderer.dll' -PbenchmarkColorAlias=true
```

## Validação e pendências

Build Java/DLL e Javadoc concluídos. Passaram 35 testes unitários, 17 gráficos,
validateLus (1 execução adicional do teste do pack) e smoke WARP (1/1), sem falhas
ou testes ignorados. Os testes gráficos usam GPU e janelas ocultas, sem abrir Minecraft.

A rota compartilhada e a copiada foram exercitadas com terreno, recortes, materiais,
HLSL/depth, sombras, vento, ordenação/oclusão e round trip de cena com cor/depth por pixel.
O teste de rejeição força armazenamento de tamanho incompatível após resize e verifica
que a ponte copiada mantém a imagem correta. Lus também compila/renderiza no teste próprio.
Isso não equivale à validação conjunta de todos os efeitos do pack no mundo real.

Ainda dependem do jogo: comportamento da câmera, chunks e blocos do modpack, resize/reload,
troca de mundos, sessão longa, estabilidade de frametime e comparação controlada de FPS.
Não foi iniciado Minecraft pelo agente. Os maiores waits observados no cliente ainda
precisam ser reavaliados após esta redução real de cópias; não foram escondidos nem
substituídos por um contador de FPS sintético.
