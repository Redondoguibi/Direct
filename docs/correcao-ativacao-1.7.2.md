# Direct 1.7.2 — falha após a transparência

O log da instância, com Direct 1.7.1 e Raydiance 1.6.1, repete `IllegalStateException: World pass cannot overtake uploads` em `NativeDx12.renderWorldInterop`, chamado pelo shaderpack no fim da cena. O backend é desligado e reinicializado por esse erro.

A etapa de transparência usava `reserveUploadRange` para guardar índices ordenados. Esse helper chama `ensureUploadOpen`, que abre a lista de cópias no alocador do slot atual. Os índices, porém, são lidos diretamente do upload heap pelo draw. A etapa translúcida não envia uma cópia, não fecha essa lista e avança o slot. Assim, `uploadOpen` permanecia true e o pós-processamento world rejeitava o frame. Esse estado também podia fazer a verificação de slot considerar o próximo slot pronto indevidamente.

A reserva foi dividida: `reserveMappedRange` apenas reserva memória do slot já verificado; `reserveUploadRange` continua abrindo a lista para os caminhos que realmente gravam cópias. A transparência usa a primeira variante e continua protegida pela fence do slot.

Foi adicionada uma consulta de prontidão para o estágio world antes de capturar a cena GL. Se houver uploads reais em gravação ou um slot ocupado, o pós-processamento é adiado sem reiniciar o terreno. O estágio nativo também retorna busy para uploads pendentes, em vez de lançar a exceção fatal. Não são submetidos draws por cima de cópias em gravação.

Entrega: `build/libs/direct-1.7.2.jar`. Raydiance 1.6.1 e o pack atual podem ser mantidos. A DLL é recompilada e incluída no JAR.

Diagnóstico baseado no log real e no fluxo de chamadas. Compilação Java/C++ e inspeção estática; sem testes ou execução do jogo, conforme a restrição da sessão. A eliminação do erro na instância ainda precisa ser confirmada após substituir o JAR e reiniciar o processo do jogo.
