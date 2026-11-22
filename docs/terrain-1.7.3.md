# Direct 1.7.3 — trabalho de CPU do terreno

- A seleção nativa é reaproveitada antes de consultar meshes e transformar posições quando câmera, matriz, distância e seleção não mudaram. Uploads, substituições, remoções e limpeza invalidam o cache antes de liberar meshes.
- Foram removidas duas cópias completas da lista de seções visíveis. A entrada JNI reutiliza um buffer de chaves e detecta mudanças de conteúdo e ordem.
- A seleção Java consulta a propriedade de todas as seções sob uma única aquisição do lock, reutilizando arrays de índices, camadas e resultados. A ordem vanilla e o fallback de seções pendentes são preservados.
- A consulta de propriedade elimina uma busca redundante no mapa de capturas.

Mantidos resolução, distância, efeitos, ordenação, orçamento de transparência e sincronização entre DX12 e OpenGL. API pública inalterada.

Artefato: `build/libs/direct-1.7.3.jar`, incluindo a DLL Windows x86-64. Compilação com `gradlew.bat --no-configuration-cache jar -x test -x javadoc`. Testes e benchmarks foram interrompidos a pedido do usuário; esta entrega não afirma ganho medido de FPS. O JAR não foi instalado automaticamente na instância.
