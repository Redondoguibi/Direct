# Terreno em distâncias de renderização grandes

Com mais chunks carregados, o Direct percorre mais seções, prepara mais comandos de terreno e pode esgotar o espaço reservado aos índices translúcidos. As alterações atuam nessas três etapas do renderizador usado pelo modo híbrido e pelo backend DX12 total.

- A seleção Java continua consultando a visibilidade e a posse das malhas a cada quadro. Ela envia as chaves por JNI quando muda o conteúdo, a ordem ou o modo de cobertura. O primeiro quadro de cada inicialização envia a seleção novamente. Os vetores temporários crescem em blocos durante o carregamento de chunks.
- No terreno opaco e recortado, a contagem de comandos e índices usa a mesma travessia dos grupos de faces. Uma camada aceita por inteiro usa diretamente o intervalo contíguo da malha.
- Para o terreno translúcido, o Direct grava os índices no formato da seção. Até 65.536 vértices, os seis índices de um quad ocupam 12 bytes no buffer de upload. Acima disso, ocupam 24 bytes. A soma usada para decidir se o passe cabe no buffer usa esses mesmos tamanhos. A ordenação continua de trás para frente, com a ordem anterior nos empates.
- A lista de seções translúcidas ordenadas pode ser reutilizada enquanto a seleção ordenada e a posição da câmera forem válidas. Quando precisa reordenar, o Direct calcula a distância uma vez por seção.

A distância escolhida no jogo, a resolução e os presets dos packs continuam a determinar o conteúdo do quadro. As mudanças reduzem trabalho de CPU e tráfego de índices. O ganho de FPS em distâncias grandes ainda não foi medido.

## Compilação

JAR gerado em `build/libs/direct-1.9.1.jar`. Nesta alteração foram compilados o Java e a DLL do Direct. A bateria de testes e o benchmark em jogo foram dispensados pelo usuário. As medições anteriores de [fase 3](fase3-dx12-total.md) foram feitas em outra revisão e não quantificam esta alteração.
