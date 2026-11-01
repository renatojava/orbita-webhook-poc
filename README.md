# Órbita CRM: webhook do WhatsApp sem duplicidade e sem perda

Recorte da missão de código do Desafio do Board. Ataca os problemas 1 (mensagens duplicadas)
e 2 (fila que trava e mensagens sem registro).

## Como rodar

Precisa só de Docker. Para rodar os testes, também Java 25.

```bash
./mvnw verify          # 38 testes, 34 deles com Postgres real (Testcontainers) e HTTP real
./scripts/demo.sh      # sobe tudo e executa os cenários da demo, incluindo kill -9
docker compose down -v # limpa o ambiente
```

Para subir sem a demo: `docker compose up --build`. A aplicação fica em `localhost:8080`.

## O fluxo

```
Meta → POST /webhooks/whatsapp → valida HMAC → grava em webhook_deliveries → 200
                                                      │
                 worker (SKIP LOCKED), em 1 transação ▼
                 messages (UNIQUE) + reply_windows (1 por contato) + DONE
                                                      │
                 janela fecha, em 1 transação         ▼
                 1 outbound_messages (UNIQUE) para N mensagens
                                                      │
                 dispatcher por cliente, com limite   ▼
                 WhatsAppGateway → SENT | retry | DEAD | UNCERTAIN
```

## Decisões

| Decisão | Por quê | O que custa |
|---|---|---|
| **Postgres como fila**, em vez do Redis | Durável e transacional com os dados, com uma peça a menos. O pico previsto é de 25 msg/s, e a PoC aguentou 500 msg/s | Depende do banco e exige limpar linhas antigas. Se passar de milhares de msg/s, a fila migra para SQS |
| **200 só depois de gravar** | Com o banco fora, responde 503 e o reenvio da Meta vira a rede de segurança. Assim nunca confirma o que não guardou | A resposta depende da latência de escrita |
| **`UNIQUE` + `ON CONFLICT`**, sem "verifica e depois grava" | É o banco que decide quem grava, de forma atômica, mesmo com reenvios simultâneos | Nenhum relevante |
| **Histórico, janela e status na mesma transação** | Uma queda no meio não deixa mensagem sem registro nem sem resposta prevista | Processamento precisa ser curto. A chamada de IA fica fora dessa transação |
| **Tentativas contadas ao pegar da fila**, não ao falhar | Uma mensagem que derruba o processo nunca chega ao tratamento de erro. Contando depois, ela voltaria para sempre | Uma queda do worker consome uma tentativa |
| **Outbox: a linha existe antes do envio** | Nenhum envio fica sem registro. É a resposta direta ao "ninguém soube dizer quais" | Uma escrita a mais por envio |
| **Janela deslizante por contato, no Postgres** | "Oi", "quero saber" e "da formação" viram 1 resposta, em vez de 3. No produto, seriam 1 chamada de IA com o contexto inteiro. A janela sobrevive a queda | Toda resposta espera ao menos a pausa (5 s por padrão, premissa a validar com conversas reais) |
| **Teto de espera (20 s) na janela** | Quem não para de digitar também é respondido | A resposta pode sair no meio do raciocínio da pessoa |
| **Limite por cliente com token bucket no Postgres** | Vale para qualquer número de réplicas e é configurável por cliente | Uma trava curta na linha do cliente a cada lote |
| **Cada cliente com seu lote e sua thread virtual** | Campanha grande ou envio lento só atrasam o próprio cliente. Respostas passam à frente das campanhas | Nenhum relevante nesta escala |
| **Kotlin** | É a linguagem que domino, e o desafio deixa livre | O produto é Node. Os mecanismos acima são SQL e valem em TypeScript, mas este código não entra direto lá |

## O limite honesto: envio único não existe

Se o processo cair entre enviar para a Meta e registrar o resultado, não há como saber se a mensagem saiu.
Como o requisito é nunca responder duas vezes, nesse caso a mensagem vira **`UNCERTAIN`**: **não é reenviada**
e fica visível em `/internal/dead-letters`. O lead pode ficar sem resposta nesse caso raro, mas nunca recebe
duas, e alguém fica sabendo. Para campanhas, a regra poderia ser outra. No produto real, o webhook de status
da Meta confirmaria esses casos sozinho.

## Provas

| Cenário | Resultado | Onde |
|---|---|---|
| 50 requisições idênticas em paralelo | 50 respostas 200, 1 registro, 1 resposta, 49 duplicatas contadas | `WebhookIdempotencyIT` e demo |
| Assinatura ausente, errada ou corpo alterado | 401, nada enfileirado | `WebhookSecurityIT` |
| Corpo que nunca processa, ou número desconhecido | Vai para `DEAD` após N tentativas com backoff, com replay | `InboundFailureIT` |
| Falha no meio de um lote | Nada gravado pela metade | `InboundFailureIT` |
| Worker morto | Trabalho retomado quando o lease expira, com limite de tentativas | `InboundFailureIT` |
| `kill -9` real com tráfego | 3.000 enviadas, 1.690 aceitas, **0 aceitas perdidas** | demo |
| Banco fora do ar | 503, e o reenvio é gravado uma vez | demo |
| 3 mensagens seguidas da mesma pessoa | 1 resposta ligada às 3 | `ReplyWindowIT` e demo |
| Pessoa que não para de digitar | Respondida no teto de espera | `ReplyWindowIT` |
| Reenvio da Meta durante a janela | Não adia a resposta | `ReplyWindowIT` |
| 200 mensagens disputando com a janela fechando | Todas respondidas, nenhuma em duas respostas | `ReplyWindowIT` |
| Janela aberta quando o processo cai | A resposta sai depois | `ReplyWindowIT` |
| Campanha de 20 mil em um cliente | Outro cliente é respondido sem atraso além da janela | `TenantIsolationIT` e demo |
| 75 msg/s (3x o pico) por 30 s, com 3% de reenvios | 0 perdas, 0 duplicatas, p99 de 3 ms | `PeakLoadIT` |
| 500 msg/s por 15 s, com 3% de reenvios | 0 perdas, 0 duplicatas, p99 de 2 ms | `PeakLoadIT` com `-Dload.rate=500 -Dload.seconds=15` |

Também sabotei cópias do código para confirmar que os testes falham quando deveriam. Sem `UNIQUE` e
`ON CONFLICT`, sem rate limit, sem isolamento entre clientes, com janela que não desliza, sem teto de espera
e com duplicata adiando a janela, os testes correspondentes ficaram vermelhos.

**Limites dessas medições:** rodaram em um notebook, com o gerador de carga na mesma JVM, o envio à Meta
simulado e a janela de 300 ms do perfil de teste. A latência medida é a da resposta HTTP, não a da resposta ao lead. Isso indica que o desenho aguenta a carga, mas não é o teste
de capacidade na AWS, que precisa ser feito no ambiente real antes do evento.

## O que ficou de fora

- Chamada real à API da Meta: `FakeWhatsAppGateway` aceita tudo com latência fixa.
- `GET /webhooks/whatsapp` de verificação da Meta e webhooks de status.
- Autenticação em `/internal/*`, que **não pode ir para produção assim**.
- Limite de tamanho do corpo antes de ler a requisição, que ficaria no gateway/ALB.
- Mensagem que chega enquanto a resposta já está saindo: abre uma nova janela, e a primeira resposta não é cancelada.
- Janelas configuráveis por cliente: hoje o valor é único. Cliente que já responde por humano pode nem querer espera.
- Ordem entre mensagens do mesmo contato: o horário da Meta é gravado, mas retries podem inverter o processamento.
- Limpeza das linhas `DONE` e `SENT`, métricas e alertas.
- Tela para tratar `DEAD` e `UNCERTAIN`: hoje há listagem e replay de entradas pela API.

## Com mais tempo

1. Webhook de status da Meta para resolver os `UNCERTAIN` e medir entrega real.
2. Métricas e alertas: idade da fila, DLQ acima de zero, taxa de duplicatas.
3. Teste de carga na AWS com o limite real da Meta, incluindo falha do banco gerenciado.
4. Limite de gasto de IA por cliente, usando o mesmo mecanismo do limite de envio (problema 3).
5. Trazer estes mecanismos para o serviço Node, começando pelo webhook, sem reescrever o produto.

## Uso de IA

Implementado com ajuda do Claude Code. Os testes, as sabotagens e a demo foram executados de fato,
e os números acima vêm dessas execuções.
