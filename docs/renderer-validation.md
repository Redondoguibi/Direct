# Validação Direct 1.4.0 / API 1.3.0

Java e DLL são distribuídos juntos. Build, JUnit, testes gráficos de GPU e smoke WARP:

```powershell
.\gradlew.bat build test javadoc graphicsTest nativeSmokeTest
.\gradlew.bat --init-script "..\Direct Group\Lus\_interno\validation\validate.gradle" validateLus
```

Os testes criam janelas gráficas ocultas; não iniciam Minecraft. Exercitam compositor,
D3D12/11On12/WGL, opaco/recortes, materiais V2, luz/fog, atlas animado e BC7, depth HLSL,
Lus real, oclusão de entidade, blending/ordenação, água/refração e reflexão com acerto
na cena, sombras sem alterar profundidade, vento, ajustes zerados, resize e orçamento.
Round trip de cena verifica cada pixel/linha e profundidade, além de estado GL preservado.
O teste de limite verifica que não altera a imagem nem impede o frame opaco seguinte.

O teste de importação usa textura RGBA8 e fence exportadas pelo DX12. No driver
Intel UHD 630 31.0.101.2140, ambas as importações GL são rejeitadas com 0x500; a ponte
validada permanece. Não demonstra a causa interna do driver ou compatibilidade universal.

Esta execução não abriu Minecraft. Depende de validação no modpack real: carregamento de
mixins, F8, câmera em movimento, blocos de outros mods, entidades, água alagada/corrente,
noite/chuva/dimensões, reload, resize, saída/entrada de mundos e sessão longa. Fabulous
mantém transparência vanilla. Sombras são próximas, sem cascatas/entidades; reflexos em tela
têm fallback atmosférico. Não há promessa de equivalência visual ao Mellow.

profileRenderer é opcional, registra FPS e frametimes por janela limitada, e agora separa
os custos da transparência em CSV próprio. O ganho de FPS desta versão exige comparação
no jogo; testes de pixels e benchmarks sintéticos não o certificam. Ver native-shader-contract.md
e dx12-terrain-performance.md para arquitetura, limitações e medições anteriores.
