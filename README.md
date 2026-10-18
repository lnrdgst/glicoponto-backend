# Glicoponto Backend

## Pré-requisitos

- Java 21
- Docker Desktop em execução para os testes de integração PostgreSQL

O Maven Wrapper distribui Maven 3.10.0. No Windows, execute `./mvnw.cmd clean verify`.

## Desenvolvimento local

Ative o perfil `local` e mantenha todas as credenciais fora do repositório:

```powershell
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:GLICOPONTO_DB_URL = 'jdbc:postgresql://localhost:5432/glicoponto'
$env:GLICOPONTO_DB_USERNAME = 'glicoponto'
$env:GLICOPONTO_DB_PASSWORD = '<senha-local>'
./mvnw.cmd spring-boot:run
```

O Hibernate somente valida o schema (`ddl-auto=validate`) e o Flyway é responsável por migrations. Ainda não existem migrations ou tabelas de domínio nesta fundação; o Flyway cria apenas seu histórico de infraestrutura ao conectar a um banco vazio.

Sem um datasource válido, a aplicação falha durante a inicialização de modo explícito. Isso é intencional: não há fallback para banco embutido ou Railway.

## Segurança e observabilidade

Somente `GET /actuator/health` é público e não exibe detalhes internos. Todas as demais rotas exigem autenticação. Autenticação de produto não faz parte deste marco.
