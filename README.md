# Glicemio Backend

## Pré-requisitos

- Java 21
- Docker Desktop em execução para os testes de integração PostgreSQL

O Maven Wrapper distribui Maven 3.10.0. No Windows, execute `./mvnw.cmd clean verify`.

## Desenvolvimento local

Copie `.env.example` para `.env`, informe somente a senha local e inicie diretamente pelo Maven Wrapper. O `.env` é ignorado pelo Git e carregado pelo Spring Boot apenas quando presente no diretório de execução:

```powershell
Copy-Item .env.example .env
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```

Preencha também as quatro variáveis `SUPABASE_JWT_*` do `.env` com os valores públicos do projeto Supabase. A aplicação falha ao iniciar se a configuração JWT estiver ausente ou se o algoritmo não for `ES256`.

O Flyway cria o schema de identidade, alimentos, refeições, itens e glicemias pela migration `V1__identity_and_meals.sql`. A persistência deste domínio usa Spring JDBC com transações; a configuração existente do Hibernate continua em `ddl-auto=validate`. Os nomes do banco e do usuário PostgreSQL existentes são preservados. As variáveis `GLICEMIO_DB_*` também aceitam as antigas `GLICOPONTO_DB_*` como fallback.

Sem um datasource válido, a aplicação falha durante a inicialização de modo explícito. Isso é intencional: não há fallback para banco embutido ou Railway.

## Segurança e observabilidade

Somente `GET /actuator/health` é público. Todas as demais rotas exigem um access token Bearer JWT do Supabase. O backend valida assinatura ES256 por JWKS, issuer, audience `authenticated`, expiração, role, anonimato e subject UUID; HTTP Basic e login por formulário não são aceitos.

O endpoint de health não exibe detalhes internos.

Tokens sem `exp` são rejeitados. Os níveis de log de Spring Web e JDBC são configurados explicitamente para evitar registro de payloads clínicos pelo modo DEBUG; não habilite logs de corpos de requisição/resposta ou de parâmetros SQL em ambientes com dados reais.

## API de refeições

As rotas `/api/v1/foods`, `/api/v1/meals`, `/api/v1/meals/{mealId}/items` e `/api/v1/glucose-readings` permitem criação, consulta, atualização por revisão e exclusão lógica. A propriedade é determinada exclusivamente pelo JWT validado.

Refeições expõem `timeReference`: `meal_start` (início), `meal_end` (término) ou `unknown` (referência não confirmada). Criações legadas sem esse campo usam `unknown`; edições que o omitem preservam o valor existente. `null` e códigos inválidos são rejeitados. A classificação não altera o horário `at` nem o vínculo com glicemias. O frontend deverá enviar `meal_start` explicitamente para novas refeições registradas pelo início.

Consulte [o contrato e relatório técnico](docs/meals-domain.md) para payloads, unidades, idempotência, isolamento, compatibilidade Dexie e limitações de episódios.

`./mvnw.cmd clean verify` executa testes com PostgreSQL 17 efêmero via Testcontainers e Flyway. A configuração de testes desabilita a importação do `.env`, força o perfil `test` e obtém os dados de conexão exclusivamente do container. Não é necessário iniciar o perfil `local` para validar a implementação.
