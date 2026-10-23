# Domínio de refeições — primeira versão persistente

## Modelo e decisões

Java 21, Spring Boot 4.1.1, PostgreSQL e Flyway. A implementação usa Spring JDBC transacional, com SQL explícito no `DomainRepository`; não adiciona entidades JPA. O starter JPA e `ddl-auto=validate` existentes foram preservados. Não há senha própria, categorias, nutrientes ou entidade clínica artificial.

| Tabela | Dados e vínculos |
| --- | --- |
| `app_user` | UUID interno, `issuer`, `subject`, criação; identidade externa única por `(issuer, subject)` |
| `food` | UUID, proprietário, nome, metadados |
| `meal` | UUID, proprietário, `kind`, `at`, `time_reference`, `description` opcional, metadados |
| `meal_item` | UUID, proprietário, refeição obrigatória, alimento opcional, `food_name` histórico obrigatório, quantidade e unidade, metadados |
| `glucose_reading` | UUID, proprietário, valor em mg/dL, `at`, contexto e nota opcionais, refeição e referência legada de episódio opcionais, metadados |

Metadados comuns: `created_at`, `updated_at`, `deleted_at` opcional e `revision` positiva, inicialmente 1. Datas são `timestamptz`; REST aceita instantes ISO 8601 com offset explícito e responde em UTC. Preserva milissegundos originais e aceita até microssegundos; precisão superior é rejeitada. Não presume timezone para datas sem offset. Datas aceitas entre os anos 0001 e 9999. Atualizações mantêm `createdAt` e avançam `updatedAt` monotonicamente.

Valores e quantidades usam `BigDecimal` e `NUMERIC`, com até 12 dígitos inteiros e 6 fracionários, estritamente positivos. A coluna usa `NUMERIC` sem typmod e `CHECK` de limite/escala: o PostgreSQL rejeita excesso de casas em vez de arredondar antes da constraint. Bean Validation aplica os mesmos limites na API.

## Migration e integridade

`src/main/resources/db/migration/V1__identity_and_meals.sql` cria as cinco tabelas, constraints e índices. Não altera nome, role ou credenciais do banco PostgreSQL existente. Nenhuma migration foi executada no banco persistente nesta entrega.

No fechamento temporal, V1 ainda estava sem versionamento: o histórico Git não contém migrations anteriores, e as execuções documentadas nesta sessão ocorreram somente em Testcontainers. Por isso o campo foi incorporado à própria V1, sem criar V2. `meal.time_reference` é `VARCHAR(12) NOT NULL DEFAULT 'unknown'`, com `ck_meal_time_reference` permitindo somente `meal_start`, `meal_end` e `unknown`. A coluna `at`, índices, UUIDs e relacionamentos foram mantidos. Nenhum banco persistente foi conectado para esta validação e nenhum registro clínico existente foi atualizado. Depois deste versionamento, preservar o checksum de V1 e fazer mudanças futuras por migrations incrementais.

As FKs `(user_id, meal_id)` e `(user_id, food_id)` referenciam chaves únicas `(user_id, id)`, impedindo associação entre proprietários mesmo por SQL direto. Não há `ON DELETE CASCADE`. Índices cobrem proprietário/período, refeição dos itens, alimento dos itens e referências de glicemias.

O provisionamento faz `INSERT ... ON CONFLICT (issuer, subject) DO NOTHING`, seguido de leitura da identidade com `FOR UPDATE`. A propriedade vem exclusivamente do JWT validado. Todas as consultas de domínio usam `user_id`. Um UUID pertencente a outro usuário retorna 404 em leitura/alteração/exclusão; colisão na criação retorna 409 genérico.

A transação mantém um lock da identidade interna até terminar a operação, serializando operações do mesmo usuário. Assim, checar vínculos e excluir uma refeição não concorre com inserir ou atualizar um vínculo. Usuários diferentes não compartilham esse lock. Essa escolha simples também serializa leituras do mesmo usuário; deve ser medida antes de escalar.

### Episódios futuros

`episode_id` é UUID opcional preservado como referência legada opaca dentro do proprietário da glicemia. Não existe tabela nem FK de episódios, e esta API não consulta, resolve ou valida a existência desse UUID. Não é uma associação clínica validada. Nenhum dado de outro episódio pode ser acessado por esse campo.

Quando existir o domínio de episódios: criar a chave única `(user_id, id)` em episódios; reconciliar referências pendentes por proprietário; não associar automaticamente UUID de outro proprietário; tratar referências inválidas explicitamente e só então adicionar/validar FK `(user_id, episode_id)` sem cascata. Até isso ocorrer, existência e propriedade de episódios são uma limitação conhecida.

## Contratos REST

Todas as rotas exigem `Authorization: Bearer <JWT Supabase>`. Propriedades JSON desconhecidas são rejeitadas, inclusive `userId`. A validação JWT existente permanece: ES256/JWKS, issuer, audience, expiração, role autenticada, não anonimato e subject UUID.

| Coleção | Operações |
| --- | --- |
| `/api/v1/foods` | POST, GET; GET/PUT/DELETE em `/{id}` |
| `/api/v1/meals` | POST, GET; GET/PUT/DELETE em `/{id}` |
| `/api/v1/meals/{mealId}/items` | POST, GET; GET/PUT/DELETE em `/{id}` |
| `/api/v1/glucose-readings` | POST, GET; GET/PUT/DELETE em `/{id}` |

POST exige UUID `id` do cliente e aceita `createdAt`/`updatedAt` originais opcionais. Sem ambos, usa o instante atual; somente `createdAt` resulta em `updatedAt=createdAt`; somente `updatedAt` exige que seja maior ou igual à criação gerada. `updatedAt < createdAt` é rejeitado. Não há importação automática.

POST novo retorna 201 com `Location`; repetição com o mesmo UUID, conteúdo e metadados fornecidos retorna 200 com o registro existente, sem avançar revisão. Payload diferente retorna 409. Metadados omitidos não são comparados na repetição. Repetir uma criação após alteração só funciona se os campos enviados ainda coincidirem com o estado atual; depois de exclusão retorna 409, sem ressuscitar o registro. Esta semântica não é um log de idempotência nem motor de sincronização.

PUT substitui os campos de negócio e exige `revision` positiva atual no corpo. UUID vem do caminho; não enviar `id`, `createdAt`, `updatedAt`, `deletedAt` ou proprietário no PUT. Campos opcionais omitidos são limpos, com a exceção de `meal.timeReference`, cujo valor existente é preservado quando omitido. Retorna 200 e revisão incrementada. DELETE exige `?revision=N`, retorna 204 e incrementa revisão; revisão desatualizada retorna 409. Registros excluídos deixam de aparecer em GET/listagens e retornam 404 nas operações subsequentes. Não há restauração nesta versão.

GET das coleções retorna array de ativos, ordenado por instante e UUID, com `limit` (1–200, padrão 100) e `offset` (>=0, padrão 0). Refeições/glicemias aceitam `from` inclusivo e `to` exclusivo, com offset explícito; se ambos informados, `from < to`. Paginação por offset não garante um snapshot sob alterações simultâneas.

Erros 400 de validação, 401 de autenticação, 404 de recurso indisponível e 409 de conflito usam Problem Details com mensagens genéricas, sem valores recebidos, SQL, claims ou dados clínicos.

### Referência temporal de refeições e backup JSON v1

| `timeReference` | Significado do instante `at` |
| --- | --- |
| `meal_start` | Início da alimentação |
| `meal_end` | Término da alimentação |
| `unknown` | Referência temporal não confirmada |

POST aceita esses códigos exatos, distinguindo maiúsculas/minúsculas. A ausência em payload legado resulta em `unknown`; `null`, números, arrays e códigos desconhecidos retornam 400. A desserialização distingue campo ausente de `null` explícito. O frontend passará a enviar `meal_start` para novas refeições, sem alterar a semântica de registros anteriores.

PUT preserva `timeReference` quando omitido e permite classificação explícita, inclusive de `unknown` para `meal_start`/`meal_end`, com a revisão atual. Continua exigindo `kind` e `at`: para alterar somente a classificação, enviar os mesmos dados de negócio existentes. O backend mantém `at` igual ao instante informado; não calcula nem reescreve horários com base na classificação. `createdAt` é preservado, enquanto `updatedAt` e `revision` avançam pela edição. Não foi introduzido segundo timestamp de término.

Backups JSON v1 sem `timeReference` continuam aceitos como payloads legados, preservando UUID, `at`, `createdAt` e `updatedAt`. Não se infere classificação a partir de usuário, descrição, hora ou qualquer outro dado. Não houve importação de backup, acesso ao IndexedDB ou edição de arquivos JSON existentes. A futura classificação de registros antigos confirmados como término exigirá identificação autenticada do proprietário, confirmação explícita e uma operação auditável; esta entrega não realiza essa reclassificação.

O vínculo `glucose_reading.meal_id` permanece inalterado ao editar a classificação da refeição; nem os dados nem a revisão da glicemia são modificados. Não há intervalos derivados persistidos nem algoritmos de análise. Análises futuras deverão interpretar intervalos em relação ao início para `meal_start`, ao término para `meal_end`, e não atribuir significado clínico específico ao intervalo para `unknown`.

### Payloads de criação

Alimento:

```json
{"id":"11111111-1111-4111-8111-111111111111","name":"Alimento sintético"}
```

Refeição legada válida, sem itens:

```json
{"id":"22222222-2222-4222-8222-222222222222","kind":"lunch","at":"2024-03-02T12:34:56.123Z","description":"Descrição legada sintética","createdAt":"2024-03-02T12:34:56.123Z","updatedAt":"2024-03-02T12:34:56.123Z"}
```

Essa criação retorna `timeReference: "unknown"`. Nova refeição registrada pelo início:

```http
POST /api/v1/meals
Content-Type: application/json

{"id":"55555555-5555-4555-8555-555555555555","kind":"lunch","at":"2024-03-02T12:34:56.123Z","description":null,"timeReference":"meal_start","createdAt":"2024-03-02T12:34:56.123Z","updatedAt":"2024-03-02T12:34:56.123Z"}
```

Resposta 201 (também é o formato de leitura):

```json
{"id":"55555555-5555-4555-8555-555555555555","kind":"lunch","at":"2024-03-02T12:34:56.123Z","description":null,"createdAt":"2024-03-02T12:34:56.123Z","updatedAt":"2024-03-02T12:34:56.123Z","deletedAt":null,"revision":1,"timeReference":"meal_start"}
```

Classificação explícita de uma refeição legada (a revisão deve ser a atual):

```http
PUT /api/v1/meals/22222222-2222-4222-8222-222222222222
Content-Type: application/json

{"kind":"lunch","at":"2024-03-02T12:34:56.123Z","description":"Descrição legada sintética","revision":1,"timeReference":"meal_end"}
```

Retorna 200 com `timeReference: "meal_end"`, o mesmo `at` e `revision: 2`. Uma edição com o mesmo corpo, revisão atual e sem `timeReference` preserva `meal_end`. Exemplos omitem o cabeçalho Bearer por legibilidade; autenticação é obrigatória.

Item no caminho da refeição:

```json
{"id":"33333333-3333-4333-8333-333333333333","foodId":"11111111-1111-4111-8111-111111111111","foodName":"Alimento sintético","quantity":1.234567,"unit":"g"}
```

Glicemia sintética (valor sempre em mg/dL):

```json
{"id":"44444444-4444-4444-8444-444444444444","value":100.125,"at":"2024-03-02T12:34:56.123Z","context":"synthetic","note":"Somente teste","mealId":"22222222-2222-4222-8222-222222222222","episodeId":null}
```

Respostas usam os mesmos nomes de negócio e incluem `createdAt`, `updatedAt`, `deletedAt` e `revision`. Item inclui `mealId`. `kind` é texto obrigatório de até 80 caracteres, sem enum inventado para o contrato Dexie. `context` é texto opcional de até 200 caracteres. `name`/`foodName`: até 200; `description`/`note`: até 10000. Textos não são convertidos ou normalizados automaticamente.

A idempotência da criação de refeições compara também a classificação: um payload legado omitido equivale a `unknown`; reenviar o UUID com classificação diferente retorna 409, exigindo uma edição explícita com revisão.

### Unidades estáveis

| Código | Significado |
| --- | --- |
| `g`, `kg`, `mg` | grama, quilograma, miligrama |
| `mL`, `L` | mililitro, litro |
| `unit`, `portion` | unidade, porção |
| `tsp`, `tbsp`, `cup` | colher de chá, colher de sopa, xícara |

Códigos distinguem maiúsculas/minúsculas. Medidas domésticas não são convertidas para massa/volume nem possuem volume presumido nesta versão.

## Exclusão

- Alimento: lógica, sem alterar `foodName` de itens históricos; renomear também não altera o histórico.
- Item: lógica individual; não apaga alimento ou refeição. Pode manter referência a um alimento já excluído ao editar o item; novas associações exigem alimento ativo do próprio usuário.
- Glicemia: lógica; não apaga ou desvincula refeição/episódio automaticamente.
- Refeição: 409 enquanto houver qualquer item ou glicemia ativa. Referências excluídas são preservadas e não impedem a exclusão lógica da refeição.

## Arquivos

Adicionados: `meals/Contracts.java`, `DomainController.java`, `DomainService.java`, `DomainRepository.java`, `DomainException.java`, `ApiExceptionHandler.java`, migration V1, `MealsApiTests.java` e este relatório.

Fechamento temporal: adicionados `meals/TimeReference.java`, `MealTimeReferenceTests.java` e `config/SupabaseJwtPropertiesTests.java`; atualizados `Contracts.java`, `DomainController.java`, `DomainService.java`, V1, `SecurityConfiguration.java`, `SupabaseJwtDecoderTests.java`, configurações principal/de testes, README e este relatório. `DomainRepository.java` conserva o isolamento e as operações SQL existentes.

Renomeados para `br/com/glicemio`: aplicação principal, configuração de segurança/propriedades Supabase e testes existentes (incluindo os arquivos locais ainda não versionados). Alterados também: `pom.xml` (identidade Maven/nome), `application.properties`, `application-local.properties`, configuração de testes, `.env.example` e README. Alterações JWT previamente presentes foram preservadas. `.env` não foi lido nem editado; nomes reais de banco e role continuam compatíveis por fallback.

## Validação e integração

Comando obrigatório: `.\mvnw.cmd clean verify`. Testcontainers usa `postgres:17-alpine`, com Flyway executado no banco efêmero. Testes desabilitam importação de `.env`, usam perfil `test` e conexões `@ServiceConnection`. Não há fallback para banco local ou H2.

Validação inicial em 23/10/2026: **BUILD SUCCESS — 26 testes, 0 falhas, 0 erros, 0 ignorados** (17 de domínio/API, 5 de JWT assinado e 4 de inicialização/segurança). Flyway validou e aplicou V1 em PostgreSQL 17.11 efêmero. O JAR `target/glicemio-backend-0.0.1-SNAPSHOT.jar` foi gerado. `git diff --check` também passou. O Maven emitiu avisos HTTP 401 para feeds auxiliares de metadados; isso não impediu resolução, compilação, testes ou empacotamento.

No fechamento temporal, a suíte preserva os 26 testes anteriores e acrescenta 12 testes temporais/privacidade, 1 teste de expiração obrigatória e 2 de configuração fail-closed: **41 testes**. Os casos temporais cobrem os nove cenários solicitados, constraints SQL, idempotência da classificação e ausência de payloads clínicos em logs mesmo com `debug=true`.

Resultado final em 23/10/2026: `.\mvnw.cmd clean verify` concluiu com **BUILD SUCCESS, código de saída 0, 41 testes, 0 falhas, 0 erros e 0 ignorados**. Flyway validou e aplicou V1 com `time_reference` em PostgreSQL 17.11 efêmero. Revisão dos arquivos publicáveis, varredura de padrões de credenciais e `git diff --check` não identificaram bloqueios; `.env`, logs e artefatos de build permanecem fora do versionamento. Todos os dados clínicos usados nos testes são sintéticos.

### Revisão de segurança e integridade

Foram mantidos JWT Supabase, identidade por issuer/subject, consultas por proprietário, FKs compostas, controle de revisão, UUID idempotente, NUMERIC sem arredondamento silencioso, nomes históricos e exclusões lógicas sem cascata. Os testes executam novamente isolamento, concorrência e vínculos entre proprietários.

Duas correções comprovadas no fechamento:

- O teste `rejectsTokenWithoutExpiration` inicialmente falhou porque um JWT assinado sem `exp` era aceito pelo validador temporal padrão. Agora `exp` é obrigatório, além da validação de assinatura e validade temporal já existente. Testes de configuração verificam falha de inicialização das propriedades sem os parâmetros JWT ou com algoritmo diferente de ES256.
- Logs DEBUG anteriores mostraram DTOs de requisição/resposta com dados sintéticos. Spring Web e os loggers JDBC responsáveis receberam nível INFO explícito; o teste com `debug=true` verifica que marcadores sintéticos de corpo válido, resposta e validação não aparecem nos logs. Não foi adicionado logging de payloads, JWTs ou valores clínicos; não habilitar TRACE de parâmetros SQL/corpos HTTP em operação com dados reais.

Cobertura: JWT com assinaturas reais e rejeições; health público; CRUD e isolamento dos quatro recursos; identidade por issuer/subject e provisionamento concorrente; UUID idempotente; revisões; nomes históricos; múltiplos itens decimais; dados legados; timestamps; rejeição de payloads inválidos; constraints SQL diretas; exclusão sem cascata; corrida entre exclusão de refeição e criação de vínculo.

Pendências frontend: enviar `timeReference: "meal_start"` para novas refeições; interpretar ausência em backups v1 como `unknown`; preservar a classificação nas edições legadas e tratar 409; apresentar a diferença entre início, término e referência não confirmada. Conferir os valores reais de `kind`/`context` no código Dexie (frontend não presente neste repositório); mapear unidades para os códigos documentados; enviar revisão nas mutações; decidir com o usuário a vinculação remota dos registros anteriores à autenticação; enviar UUIDs e metadados originais somente nessa transferência explícita; reconciliar episódios antes de habilitar sua resolução. A eventual reclassificação histórica para `meal_end` precisa da confirmação autenticada e da operação auditável descritas acima. Não foi implementada importação nem sincronização completa.

Riscos: episódios ainda sem FK; lock por usuário limita paralelismo; paginação por offset sob alterações simultâneas; JPA não valida o mapeamento deste domínio JDBC (a migration e os testes PostgreSQL validam a persistência). Ao iniciar futuramente com perfil local, Flyway aplicará V1: revisar o schema persistente antes desse passo, que não foi executado aqui.

## Versionamento

Destino confirmado no GitHub: `https://github.com/lnrdgst/glicemio-backend`, branch padrão `main`, inicialmente no mesmo commit `d0da8d4` da branch local. A URL antiga de `origin` foi atualizada para esse destino canônico. Mensagem solicitada: `feat: implementa dominio de refeicoes com referencia temporal`. O hash final e o resultado do push para `origin/main` são informados na entrega após revisão do diff, testes e verificação de arquivos publicáveis. Não há deploy Railway nem execução de migrations no banco persistente nesta entrega.
