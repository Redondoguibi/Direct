# Execução e recarga dos shaderpacks

O Direct passou a entregar ao backend DX12 a textura RGBA8 que o último passe de mundo acabou de desenhar. A importação ainda corrige a orientação das linhas com o passe existente. Ela agora lê diretamente essa textura, identificada pelo valor da fence do quadro e mantida viva até a conclusão da leitura. Isso elimina uma cópia de cor de tela inteira por estágio de mundo no modo só DX12. O modo híbrido continua entregando o resultado na textura registrada no OpenGL.

O grafo reutiliza os 20 descritores de leitura e os oito de escrita por passe enquanto forem válidos para o slot do quadro, o histórico e a fonte de profundidade. Trocar o pack, recriar os alvos ou redimensionar a janela invalida as tabelas correspondentes. A lista de comandos do grafo também passa a conter as duas transições da ponte de profundidade que antes exigiam listas separadas. Os alvos intermediários mudam de estado quando um passe os usa.

## Recarga do Lus

O cache em disco do Direct agora cobre os programas de geometria, extensão e mundo dos packs. Cada entrada considera a fonte preparada, o ponto de entrada, o perfil, as macros, as flags e a versão de compilador usada na chave. O carregador confere tamanho e integridade do bytecode e recompila uma entrada inválida.

Medição local com as fontes e opções do Lus gravadas pelo harness na cena “Sodium X Direct 1_9_1”. Em cada execução, um dispositivo novo recebeu a configuração do pack uma vez para aquecer os pipelines do driver. Cronometrou-se a segunda chamada a `configurePackGeometry` e `configureWorldPasses` em conjunto, alternando a presença dos arquivos de bytecode. O primeiro aquecimento foi descartado.

| Bytecode do pack ao iniciar a recarga | Tempos de CPU decorridos | Mediana |
| --- | --- | --- |
| Ausente | 447,02 / 456,33 / 432,11 ms | 447,02 ms |
| Presente | 4,14 / 3,66 / 3,61 ms | 3,66 ms |

O comparativo mede a recarga de um pack já conhecido. O primeiro uso de uma variante ainda precisa compilar seus programas. O FPS médio em jogo desta revisão permanece sem medição comparativa intercalada.

## Verificação

O JAR compilou com Java e DLL atualizados. Os 12 testes de grafo de mundo com GPU e o teste de cache passaram. A prova do caminho de renderização com o Lus chegou a `PASS` no backend só DX12, com a camada de depuração ativa. Esse modo da prova grava capturas e verifica o funcionamento. A comparação pixel a pixel com o OpenGL ocorre no modo sombra.

As fontes dos packs, as opções, a resolução e as flags de otimização do compilador foram preservadas. Os blocos de código embutidos nos dois arquivos nativos alterados também foram conferidos contra a revisão de base.
