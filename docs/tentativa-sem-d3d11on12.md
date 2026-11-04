# Tentativa de substituir D3D11On12 — 2026-11-04

Resultado: foi implementado e compilado um protótipo que executa a transferência sem chamar D3D11On12, mas a alternativa funcional ficou mais lenta no teste isolado. Não foi instalada no modpack. O código de produção foi restaurado ao estado anterior à experiência, preservando as mudanças anteriores da 1.5.3.

## Rotas investigadas

1. Dispositivo D3D11 nativo no mesmo adaptador (LUID) do DX12; recursos compartilhados por handle NT. Foram experimentadas criação em DX12 e abertura em D3D11, criação em D3D11 e abertura em DX12, e uma variante com keyed mutex. A criação/abertura entre Direct3D funcionou, mas o registro da textura compartilhada no WGL falhou neste ambiente. A causa interna do driver não foi comprovada.
2. Texturas comuns D3D11 para o WGL, com texturas compartilhadas intermediárias entre D3D11 e DX12. Essa rota funcionou. Usa cópias na GPU, estados COMMON nas fronteiras e uma fence compartilhada com Wait/Signal em ambas as filas. Não usa readback de pixels pela CPU. As esperas e locks existentes de GL continuam necessários.

O protótipo conserva a implementação antiga para comparação por variável de ambiente; portanto, sua DLL ainda contém a importação de D3D11On12. Isso não é uma remoção completa da dependência no produto. Na rota experimental selecionada, o dispositivo e as transferências usam D3D11 nativo.

## Validação e custo

Intel UHD Graphics 630, 1920×1080, 16 quads sobrepostos, 30 quadros de aquecimento e 120 amostras por execução. Minecraft fechado. Tempos em milissegundos, medidos por NativeDepthCostBenchmark:

| Implementação | GPU p50, execução 1 / 2 | Submissão até fence p50, execução 1 / 2 |
| --- | --- | --- |
| D3D11On12 atual, cor compartilhada | 9,247 / 7,378 | 9,773 / 7,765 |
| D3D11 nativo, intermediários | 13,894 / 12,334 | 15,031 / 12,852 |

A carga não mede a composição completa do Minecraft nem reproduz a espera de 19 ms do log. Há variação entre execuções. Ela demonstra que retirar a tradução por essa rota não trouxe ganho no cenário medido; não demonstra que toda alternativa sem D3D11On12 seria mais lenta.

Passaram três testes gráficos do protótipo: materiais/controle zero, transparência ordenada com oclusão por entidades, e round trip de cor/profundidade com resize e preservação de estado GL. O teste de round trip sozinho não valida os efeitos sobre o terreno; a combinação com os outros dois verifica alterações reais nos pixels. Não houve teste no Minecraft.

## Artefatos

- `archive/native-d3d11-bridge-prototype.patch`: alterações experimentais sobre o código existente no início desta tentativa.
- `archive/native-d3d11-copy-prototype.dll`: DLL do protótipo funcional, fora do modpack.
- `archive/native-bridge-functional-tests.xml`: resultados dos três testes gráficos.
- `archive/native-d3d11-bridge-results.json`: medições e condições.

Para reproduzir em uma cópia de desenvolvimento, aplique o patch e defina `DIRECT_NATIVE_D3D11=1` no processo de teste. Use o caminho copiado (`benchmarkColorAlias=false`); o protótipo não implementa o caminho de alias de cor. Ele não está pronto para distribuição e ainda exigiria cobertura completa de falhas, teardown e sessão prolongada.

Referências oficiais consultadas: [compartilhamento de recursos D3D12](https://microsoft.github.io/DirectX-Specs/d3d/ResourceHeaps.html), [OpenSharedHandle](https://learn.microsoft.com/en-us/windows/win32/api/d3d12/nf-d3d12-id3d12device-opensharedhandle) e [contrato WGL/DX](https://github.com/KhronosGroup/OpenGL-Registry/blob/main/extensions/NV/WGL_NV_DX_interop.txt).

A remoção completa continua pendente. Antes de outra substituição, é necessário identificar quanto da espera observada no jogo vem de trabalho anterior nas filas, da ponte e da composição GL. Esta experiência não resolveu a regressão de FPS relatada.

Após restaurar os dois arquivos de produção (comparação byte a byte com o backup desta tentativa), a compilação completa passou, assim como 43 testes unitários, 24 testes gráficos e o smoke nativo 1/1, sem falhas ou testes ignorados. Nenhum arquivo do modpack foi alterado nesta tentativa.
