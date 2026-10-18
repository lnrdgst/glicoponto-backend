# Glicoponto Backend

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

O Hibernate somente valida o schema (`ddl-auto=validate`) e o Flyway é responsável por migrations. Ainda não existem migrations ou tabelas de domínio nesta fundação; o Flyway cria apenas seu histórico de infraestrutura ao conectar a um banco vazio.

Sem um datasource válido, a aplicação falha durante a inicialização de modo explícito. Isso é intencional: não há fallback para banco embutido ou Railway.

## Segurança e observabilidade

Somente `GET /actuator/health` é público e não exibe detalhes internos. Todas as demais rotas exigem autenticação. Autenticação de produto não faz parte deste marco.
