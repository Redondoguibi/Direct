# Direct 1.5.3 — pesquisa e aplicação de técnicas de otimização

Pesquisa em 4 de novembro de 2026, nas fontes dos próprios projetos. Não há um
“melhor mod” universal: renderização, simulação, memória e carregamento têm custos
diferentes. As versões atuais consultadas não são uma recomendação de instalação
no Minecraft 1.21.1 nem uma declaração de compatibilidade com Direct.

| Referência | Evidência consultada | Aplicação no Direct |
| --- | --- | --- |
| [Sodium](https://github.com/CaffeineMC/sodium/releases/tag/mc26.2-0.9.2) | A versão consultada descreve redução de alocações e redimensionamentos de buffers de terreno | Reutilizar armazenamento temporário da captura; os buffers finais dos meshes continuam privados |
| [Lithium](https://github.com/CaffeineMC/lithium/blob/develop/lithium-neoforge-mixin-config.md) | Documenta redução de alocações, reuso de arrays e caches em áreas específicas | Evitar criar arrays e mapas a cada camada, com cache limitado ao trabalho atual |
| [FerriteCore](https://github.com/malte0811/FerriteCore/blob/main/summary.md) | Documenta redução de representações duplicadas e retenção desnecessária de objetos | Uma fila ordenada de chaves primitivas substitui mapa mais deque; temporários deixam de reter estados após a captura |
| [ModernFix](https://github.com/embeddedt/ModernFix/tree/1.21) | Atua em carregamento, memória e correções | Referência de escopo: corrigir desperdícios concretos e validar compatibilidade; suas alterações de carregamento não foram transplantadas |

São princípios gerais aplicados em implementação própria. Nenhum código, shader
ou classe desses projetos foi incorporado. Em especial, não se pretende portar
o renderizador OpenGL do Sodium como se fosse um backend D3D12.

## Implementação

1. Cada thread de captura reutiliza um workspace. O array de materiais cresce sob
   demanda e retém no máximo 65536 bytes; pedidos maiores recebem array temporário.
2. A classificação por identidade de BlockState é compartilhada entre as camadas
   de uma compilação, com até 512 estados armazenados. A limpeza ao finalizar a
   captura evita cache entre chunks/trabalhos e resultados antigos após mudanças
   de tags/recursos. Não há cache global nem lock novo na classificação.
3. Capturas cuja geração/versão já foi invalidada são rejeitadas antes de percorrer
   e empacotar meshes. A validação final continua, cobrindo invalidação concorrente.
4. A fila coalescida usa um único Long2ObjectLinkedOpenHashMap da dependência fastutil
   já existente. Preserva FIFO, substituição por seção e contabilidade de bytes.
5. Atualizações obsoletas são descartadas antes de verificar o orçamento de upload.
   Antes, uma atualização grande obsoleta na cabeça podia bloquear trabalho válido
   quando parte do orçamento já tivesse sido usada. Um item válido grande continua
   aguardando o próximo quadro, que permite um item excedente para evitar inanição.

DX12 desligado preserva os atalhos da 1.5.2. Esta etapa otimiza estruturas do Direct;
não altera a simulação, os modelos vanilla ou o carregador de outros mods. C++ e
DLL continuam no Direct. Raydiance/Lus não foram alterados. API pública: 1.4.0.

## Medição e limites

Benchmark CPU de 500 trabalhos, quatro camadas e 4096 quads por camada, com 16
identidades repetidas. Após aquecimento, o caminho anterior alocou 8.848.000 bytes
por rodada; o novo não alocou objetos nesse recorte. O workspace previamente
alocado continua ocupando memória. Não significa zero alocação na captura completa:
os buffers finais de vértices/índices, records e outras estruturas continuam existindo.

Tempos antigos: 60,543 / 82,438 / 57,609 ms. Novos: 79,015 / 64,211 / 52,444 ms.
Há variação e não foi demonstrado ganho consistente de CPU. A classificação do
benchmark é trivial, não representa o custo real de consultar blocos e tags.
Dados: archive/capture-workspace-1.5.3.txt. Reprodução:

```powershell
.\gradlew.bat --init-script docs/terrain-benchmark.gradle captureAllocationBenchmark
```

Passaram 43 testes unitários, 25 gráficos, validateLus (1) e smoke WARP (1/1), além
de build Java/C++ e Javadoc. Sem falhas ou testes ignorados. Os casos novos cobrem
limites do workspace, identidade dos estados, limpeza entre trabalhos, FIFO,
coalescência, orçamento, remoção e a regressão do item obsoleto grande.

Os testes gráficos continuam cobrindo os recursos existentes nas duas rotas de
interop. Não substituem Minecraft no modpack: ainda faltam confirmação visual,
sessão longa, desempenho/GC reais e compatibilidade conjunta com outros mods de
otimização. Minecraft não foi iniciado pelo agente.
