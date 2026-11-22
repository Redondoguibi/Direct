# Direct 1.6.0 — experiência de configuração e recuperação

Fonte principal: `C:\Criando Mods\mod\Direct`. A pasta `Direct Group\Direct` contém uma cópia antiga (1.2.4); não foi sincronizada nem usada para esta entrega.

## O que mudou

- Painel próprio em F7, Mods > Direct > Config e `/direct menu`, com visão geral, recursos e opções avançadas.
- Modos Vanilla, Direct DX12 e sobreposição de diagnóstico claramente separados. Substituição força distância de 100%; a distância parcial é exclusiva do diagnóstico.
- Perfil Equilibrado com os padrões existentes; Menos memória com arena de 128 MiB, anel de 16 MiB, envio de 8 MiB/frame e 16 seções/frame; Compatibilidade com sincronização CPU, cor copiada, sem gravação paralela/desenhos indiretos/compressão BC7. Os perfis não ativam o Direct automaticamente nem alteram os efeitos do Raydiance.
- Rascunho de configuração independente do estado ativo. Salvar aplica; Cancelar/Esc descarta. Navegação, redimensionamento e submenus mantêm o rascunho. Alterações de renderização recriam o backend no próximo tick; HUD/métricas não exigem reinicialização.
- Guia e diagnóstico com rolagem por mouse/teclado. O relatório pode ser copiado e inclui versão, API, adaptador, capacidades, último erro, falhas dos passes e configurações atuais. Não envia dados pela rede.
- HUD traduzido, oculto com F1 e F3; o estado DX12 ativo só é emitido depois de importar a imagem. Seções ainda vanilla são contadas no estado misto.
- Tratamento comum de falhas de terreno, composição e translucência. Intervalos de 5 e 10 segundos, suspensão após a terceira falha e reinício manual. Falha de limpeza suspende imediatamente. O contador só zera após 120 frames importados; o último erro é preservado para diagnóstico.
- Verificação de plataforma/DLL e bloqueio dos renderizadores alternativos conhecidos. Um plugin Mixin evita aplicar os hooks vanilla quando esses mods são encontrados pelo loader, antes de chegar ao renderizador.
- Atalhos entre as telas de Direct e Raydiance pela fábrica de configuração NeoForge, sem dependência obrigatória ou reflexão sobre classes internas. Cada mod mantém sua própria transação de configuração.

## Entrega e compatibilidade

JAR: `build/libs/direct-1.6.0.jar`. Minecraft 1.21.1, NeoForge 21.1.265, Java 21, Windows x86-64 e driver D3D12/WGL compatível. API pública permanece 1.4.0. Raydiance recebeu a versão 1.5.2 somente para os ajustes de navegação/atalho; Lus não mudou.

Não houve instalação automática na instância CurseForge. Para instalar, feche o jogo e substitua o JAR Direct anterior, mantendo apenas uma versão. O JAR novo contém a DLL Windows. Os arquivos de configuração existentes continuam válidos, inclusive a chave histórica `enableDx12Preview`.

## Compilação e validação adiada

Foram usados `compileJava` e `jar`; esses alvos não executam testes. Não foram executados testes unitários, gráficos, smoke nativo, benchmark ou Minecraft. Não há aprovação visual ou comprovação de FPS desta atualização.

Para uma próxima etapa, validar no jogo: salvar/cancelar e redimensionar a interface; alternar Vanilla/DX12/diagnóstico; editar perfis; acessar Raydiance e voltar; reiniciar após falha; sair/trocar de mundo; recarregar recursos; observar fallback misto; instalar cada renderizador alternativo separadamente; comparar custos e imagens na mesma cena.

Os limites gráficos existentes continuam: sombras próximas, reflexos em tela, transparência vanilla em alvos incompatíveis/Fabulous e ausência de compatibilidade com packs Iris/OptiFine. A melhoria desta entrega é de operação, configuração e recuperação; estabilidade final depende da validação adiada.
