# Development setup

The user selected Gradle instead of Maven after task 2.1. The original Maven
probe remains in the historical verification log; Gradle availability was checked
during the build-tool amendment.

Task 2.1 inventories the local tools and repairs Java discovery. Task 2.2 selects
the version baseline below. Task 2.4 now provides the backend scaffold described at the end of this document.
The backend remains Java, Spring Boot, JPA and MySQL.

## Observed environment (2026-09-25)

| Tool | Observed result | Follow-up |
| --- | --- | --- |
| Java / javac | Homebrew OpenJDK / javac 21.0.12.1 | Compile/run and fresh-shell checks passed |
| Gradle | No global `gradle`; backend Wrapper 9.8.0 now available | Use backend/gradlew; verified on Java 21 |
| Node.js | 24.16.0 active; 20.20.2 and 22.22.3 also installed through nvm | Upgrade to selected 24.21.0 in 2.7 |
| npm | 11.13.0 | Use bundled 11.19.0 with selected Node in 2.7 |
| pnpm | 10.0.0 | Available |
| Yarn | Corepack shim present; version lookup failed on registry DNS | Optional, unverified; npm/pnpm work |
| Docker CLI | 29.4.3 | Available |
| Docker Compose | 5.1.4 | Available |
| Docker engine | Socket missing; server unavailable | Start Docker Desktop before tasks 2.6/2.9 |
| Python | 3.14.5 | Meets pack's Python 3.9+ requirement; pack validator passed |

This inventory is for an Apple Silicon Mac. Installed versions are observations,
separate from the selected project baseline below. No MySQL container, backend,
frontend, or mock service was started in this task.

## Java repair

The previous `~/.zshenv` called `/usr/libexec/java_home -v 11` unconditionally,
even though no JDK was installed. Both `java` and `javac` resolved to macOS stubs.

Install the [Homebrew OpenJDK 21 formula](https://formulae.brew.sh/formula/openjdk%4021):

```sh
brew install openjdk@21
```

The local shell repair preserves an existing JAVA_HOME containing both Java and
javac. Otherwise it selects `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`,
falling back to quiet macOS discovery if Homebrew 21 is absent, and prepends the
selected JDK's bin directory when needed. This machine-specific configuration is
in the user's shell, not an application requirement. Other hosts should set
JAVA_HOME to their installed JDK and add its bin directory to PATH.

The original shell file is backed up at `~/.zshenv.relay-2.1.bak`. Restoring that
backup reverts this task's shell change (and restores the old Java 11 lookup).
No system-wide JDK symlink was created: `/usr/libexec/java_home` and `/usr/bin/java`
may still fail outside the configured shell. Use `java` from the configured PATH.
Open a new terminal, or apply the change to an existing zsh session:

```sh
source ~/.zshenv
java -version
javac -version
python3 scripts/check_toolchain.py
```

The diagnostic prints executable paths and versions, compiles/runs a temporary
Java class, and confirms malformed Java is rejected. It installs nothing and
cleans up its temporary files. Gradle and Docker engine absence is explicitly
reported without failing this availability task; these must work when their
respective foundation tasks are verified. Core tool failures fail the diagnostic.

## Verification and remaining work

Exact commands and results: [toolchain verification](toolchain-verification.txt).
Roadmap and acceptance criteria: [project plan](../PROJECT_PLAN.md).
Pinned pack prerequisite: [pack README](source-review/pack/README.md).

Task 2.2 selected the documented baseline. Task 2.4 supplies Gradle Wrapper;
tasks 2.6 and 2.9 must verify a running Docker engine, MySQL and component startup.
API routes remain unimplemented; this task adds none.

## Selected version baseline — task 2.2

Selected on 2026-09-25. These are the initial project pins for the upcoming scaffold,
not a claim that dependencies have been installed or tested together. Keep Java
application source at language level 21. Use **Gradle Groovy DSL** (`build.gradle`,
`settings.gradle`) and **npm** with `package-lock.json`; a single backend module
needs no extra Kotlin build plugin or separate dependency-management plugin.

| Component | Selected version |
| --- | --- |
| Java JDK | 21.0.12.1 |
| Spring Boot | 4.1.1 |
| Gradle Wrapper | 9.8.0 |
| Node.js | 24.21.0 |
| npm | 11.19.0 |
| MySQL image | 8.4.11 |
| Python | 3.14.5 |
| Docker CLI baseline | 29.4.3 |
| Docker Compose baseline | 5.1.4 |
| JSON Schema validator | 3.0.6 |
| Vite | 8.3.1 |
| Vite React plugin | 6.1.1 |
| React | 19.3.0 |
| React DOM | 19.3.0 |
| TypeScript | 7.0.2 |
| Node types | 24.13.6 |
| React types | 19.3.0 |
| React DOM types | 19.3.0 |
| React Router | 8.4.0 |

Task 2.7 installed Node 24.21.0/npm 11.19.0 through nvm, with archive checksum
verification. Use `nvm use` from frontend/ to select it; no global default was changed.
Node 24.16.0/npm 11.13.0 remain available as the earlier installation.
Java and Python match the baseline already. Docker CLI/Compose numbers are the
observed local tooling baseline, not the newest upstream versions or a verified
server pairing. A working engine, image pull and Compose behavior remain 2.6/2.9.

### Compatibility and primary sources

- **Java / Boot / Gradle:** [Boot 4.1.1 requirements](https://docs.spring.io/spring-boot/system-requirements.html)
  allow Java 17–26 and Gradle 8.14+ within 8.x or 9.x. The
  [Gradle compatibility matrix](https://docs.gradle.org/9.8.0/userguide/compatibility.html)
  supports running on Java 21. Use JDK 21 for the daemon and the Java toolchain.
  [Boot's current release](https://spring.io/projects/spring-boot/) and the saved
  released BOM establish the selected Boot version; no milestone/snapshot is used.
  The installed [Homebrew JDK 21](https://formulae.brew.sh/formula/openjdk%4021)
  is retained. Gradle 9.8.0 is marked non-snapshot in its published release metadata.
- **Dependency alignment:** Use Gradle's native `platform` import of
  `org.springframework.boot:spring-boot-dependencies:4.1.1` on application and test
  configurations, as described by [Boot's Gradle guide](https://docs.spring.io/spring-boot/gradle-plugin/managing-dependencies.html).
  Select the Boot plugin at the same version. Do not independently override Spring,
  Hibernate, Jackson or migration versions. The table below comes directly from
  the [published BOM](version-sources/boot-bom.pom), not a resolved build report.
- **Node / frontend:** [Node 24 is LTS](https://nodejs.org/en/about/previous-releases).
  Node 24.21.0 satisfies Vite/plugin's `^20.19.0 || >=22.12.0`, npm's
  `^20.17.0 || >=22.9.0`, TypeScript's `>=16.20.0`, and React Router's `>=22.22.0`.
  [Vite 8.3 receives regular fixes](https://vite.dev/releases); plugin 6.1.1 accepts
  Vite 8. React DOM 19.3.0 requires React `^19.3.0`; the selected React and type
  packages align. [React 19.3](https://react.dev/blog/2026/09/09/react-19-3) and
  [TypeScript 7](https://www.typescriptlang.org/) are stable releases. Use React
  Router in client-side library mode for the five existing console views; no SSR
  framework, React Compiler or optional Vite peer features are required.
  Vite transpilation does not replace a separate TypeScript check. Exact engines,
  required/optional peers and versions are retained in the registry snapshots.
- **MySQL:** Select `mysql:8.4.11` from the 8.4 LTS line. The
  [official image catalog](https://github.com/docker-library/official-images/blob/master/library/mysql)
  lists this tag for amd64 and arm64v8 (saved [catalog](version-sources/mysql-image.txt)).
  The [release notes](https://dev.mysql.com/doc/relnotes/mysql/8.4/en/) also list
  8.4.12, but that tag is absent from the retrieved image catalog; do not invent
  availability or use the floating `lts` tag, which now selects a different line.
  Task 2.6 records the verified digest in [image provenance](mysql-image-provenance.json). Connector/J 9.7.0 is Boot-managed and
  [supports MySQL 8.0 and later](https://dev.mysql.com/doc/relnotes/connector-j/en/news-9-7-0.html).
  Keep the BOM driver instead of independently adopting the newer 26.x line.
- **Migrations / SQL:** Include both Boot's Flyway starter and
  `org.flywaydb:flyway-mysql`; [MySQL support is a separate Flyway module](https://documentation.red-gate.com/flyway/reference/database-driver-reference/mysql).
  Redgate's displayed verified-version list omits 8.4. The selected
  [Flyway 12.4.0 source](version-sources/flyway-mysql.java) accepts MySQL 8.4
  within its supported range (minimum 5.1, free-edition baseline 8.0, newer-version
  advisory above 9.4). This is a source-based compatibility inference, not a claim
  that Redgate explicitly lists 8.4 as verified. Actual migration verification is 2.9/3.10.
  MySQL 8.4 documents [enforced CHECK constraints](https://dev.mysql.com/doc/refman/8.4/en/create-table-check-constraints.html),
  [JSON storage](https://dev.mysql.com/doc/refman/8.4/en/json.html) and
  [locking reads including SKIP LOCKED](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html).
  These support the designed row constraints, snapshots and queue claims. Null
  semantics still require NOT NULL alongside CHECK; cross-table invariants remain
  transactional service rules. SQL/mapping correctness is not established by version selection.
- **JSON Schema:** Select `com.networknt:json-schema-validator:3.0.6` with Boot's
  Jackson 3.1.5. The [validator documentation](https://github.com/networknt/json-schema-validator)
  describes Java 17+/Jackson 3 support in its 3.x line, Draft 2020-12, bundled
  meta-schemas and annotation-only format by default. The selected POM requests
  Jackson 3.1.4; Boot advances that patch to 3.1.5. Candidate 3.0.7 requests Jackson
  3.2.1, so it is deliberately not selected for this BOM. Do not silently let it
  upgrade Jackson or force it down to 3.1.5. This is a compatibility pin, not a
  claim of a separate maintenance guarantee for an older patch. Recheck releases
  when implementing 5.7. Configure a local-only schema loader, reject external refs,
  bundle standard meta-schemas and preserve annotation-only format semantics.
  Test actual validator behavior and regex compatibility with Draft 2020-12 in
  3.7/5.7; selecting the library does not enforce those restrictions automatically.
- **Python / mocks:** Retain [Python 3.14.5](https://www.python.org/downloads/release/python-3145/).
  It exceeds the [pack's Python 3.9+ prerequisite](source-review/pack/README.md);
  pack validation already runs. The standard-library mocks need no pip dependencies.

### Boot-managed dependency baseline

| BOM property | Version |
| --- | --- |
| flyway.version | 12.4.0 |
| hibernate.version | 7.4.5.Final |
| jackson-bom.version | 3.1.5 |
| junit-jupiter.version | 6.0.3 |
| mysql.version | 9.7.0 |
| spring-data-bom.version | 2026.0.1 |
| spring-framework.version | 7.0.9 |
| spring-security.version | 7.1.1 |
| testcontainers.version | 2.0.5 |

In 2.4 use the Boot 4 starters for Web MVC, Validation, Data JPA, Security,
Actuator and Flyway, the MySQL driver, and appropriate starter test modules.
Use Jakarta imports for JPA/Validation. JUnit and Testcontainers use the BOM
versions; add the MySQL module for real database tests. No H2 substitute is used
for the MySQL acceptance tests. The JSON Schema dependency enters with its
implementation task, rather than adding unused dependencies to the foundation.

### Reproducible setup policy

Task 2.4 creates all Gradle Wrapper files and pins the binary distribution to
9.8.0. Set `distributionSha256Sum` to the official value captured in
[gradle.sha256](version-sources/gradle.sha256); verify the wrapper JAR with Gradle's
published checksum as part of wrapper creation. Keep generated dependency locks
with the scaffold and regenerate them only during deliberate dependency changes.
Use native BOM constraints and inspect resolved dependencies for unexpected upgrades.

Task 2.7 creates `.nvmrc` for 24.21.0, records npm 11.19.0 in `packageManager`,
pins direct frontend dependencies exactly and saves `package-lock.json`.
Use `npm ci` for subsequent reproducible installation. Do not create pnpm/yarn
lockfiles. The native platform packages selected by tooling must be retained in
the lockfile; verify on this Apple Silicon host. Backend wrapper and dependency locks were added in task 2.4; frontend package-lock.json was added in 2.7. Task 2.6 pins the selected MySQL tag plus verified digest.

Check maintenance releases and advisories before implementing the relevant
scaffold and before final verification. Update this baseline, wrapper/locks and
evidence together if versions change; run the affected build/integration tests.
No dynamic `latest`, `+`, snapshots or prereleases in project dependency pins.
Registry `/latest` URLs below are evidence queries, not installation commands.
All work remains local; build scans and remote publication are not part of setup.

### Verification boundary and later commands

**Document/metadata verification only; no application dependency resolution or
integration tests were run in task 2.2.** Run the offline document check now:

```sh
python3 scripts/check_versions.py
```

After the respective scaffolds exist, run these planned commands from the named
directories (not executed in 2.2):

| Task / directory | Command or check | Expected result |
| --- | --- | --- |
| 2.4 / backend | `./gradlew --version` | Gradle 9.8.0 running on JDK 21 |
| 2.4 / backend | `./gradlew dependencies --configuration runtimeClasspath` | BOM alignment; MySQL and Flyway MySQL module present |
| 2.4 / backend | `./gradlew test bootJar` | Tests pass and executable jar built |
| 2.6 / root | `docker compose config` then `docker compose up -d` | Valid service configuration, selected image and healthy MySQL |
| 2.7 / frontend | `node --version` and `npm --version` | Selected Node/npm pair |
| 2.7 / frontend | `npm ci` then `npm run build` | Lockfile respected; script must include TypeScript checking and Vite build |
| 2.9 / backend | Startup against MySQL with Flyway | Migration completes; API ready; worker validates schema |
| 3.10 / backend | Real MySQL repository tests | Enforced constraints, JSON round-trip, transactional rollback and locking verified |
| 5.7 / backend | Schema validator tests | Seed schemas, invalid outputs, references, format behavior and repair boundaries verified |

Exact metadata provenance/hashes: [source manifest](version-sources/provenance.json).
Machine-readable selected pins: [baseline](version-sources/baseline.json).
Actual task outcomes: [version verification](versions-verification.txt).

## Project layout and environment examples — task 2.3

The `backend/`, `frontend/`, `docs/` and `scripts/` directories now exist.
The backend now contains the Spring Boot Gradle scaffold from task 2.4. The
frontend contains the task 2.7 React/Vite scaffold and configuration example. Existing
requirements, pinned source evidence and verification scripts remain in place.

| File / directory | Purpose |
| --- | --- |
| [backend/.env.example](../backend/.env.example) | Planned API/worker settings; blank mode, model and secret values require local configuration |
| [frontend/.env.example](../frontend/.env.example) | Public API URL only |
| [.gitignore](../.gitignore) | Local environments, dependencies, generated builds, editor state and scratch data |
| docs/ | Requirements, designs, setup and verification evidence |
| scripts/ | Repeatable local verification tools |

To prepare local copies from the project root, the following commands refuse to
overwrite an existing local file. They are optional preparation, not startup:

```sh
(umask 077; set -C; cat backend/.env.example > backend/.env)
(umask 077; set -C; cat frontend/.env.example > frontend/.env.local)
```

**No automatic dotenv loader is implemented.** Merely copying the backend file
does not export variables or configure Spring Boot. Task 2.4 binds RELAY_API_PORT;
task 2.5 binds database/mode settings as described below. Do not
source untrusted dotenv files as shell code. Vite configuration is wired in 2.7.

Backend settings mirror [the architecture contract](ARCHITECTURE.md). Choose
`api` or `worker` per process; the blank `RELAY_MODE` prevents a ready-made implicit
mode. Use the same configured database for both. `relay` is a proposed local
schema/user name, not an account created by this task. Fill the DB password and
management token only in the ignored local file. Select the model when its adapter
is configured; an empty model is not claimed runnable. The AI key remains blank
for local mocks; real credentials belong only in backend configuration in 5.9.
Compose initialization and secret files are documented in the task 2.6 section below.

The HTTP allowlist contains only the local mock origin. API origin configuration
names the planned console at port 5173. Timing/retry values match
[the recovery design](RECOVERY.md); the lease exceeds renewal + longest call + two
transaction deadlines. These are design examples, not runtime validation. The
mock-http provider returns prose, so it cannot demonstrate successful AI schema
validation; canned tests and the real-provider demo retain their planned tasks.

All frontend environment values must be treated as public. The example contains
only `VITE_RELAY_API_BASE_URL`; the management token is entered through the future
console access flow and never included in frontend environment/build files.
Workflow webhook secrets belong to workflow configuration, not these templates.

The ignore policy covers root and nested `.env` variants, retaining only the
reviewed `.env.example` filename. It also covers Gradle/Python/Vite caches,
`node_modules`, builds, local logs and editor files. Put local database dumps and
other private scratch artifacts under ignored `local-data/` or `tmp/`.
Do not store credentials in source files: ignore rules do not protect files that
are already tracked. Source files, SQL migrations, Gradle Wrapper files (including
its JAR), Gradle dependency locks, `package-lock.json`, docs and saved verification
evidence remain eligible for local version control. No project Git repository was
initialized and nothing was published.

Run `python3 scripts/check_project_layout.py` to verify the templates and actual
Git ignore behavior in a disposable temporary repository. Expected result: all
layout, key/default, secret-boundary and ignore/retain cases pass. Runtime tests
for database/mode/frontend wiring remain tasks 2.5–2.9; scaffold HTTP tests are recorded below. Actual layout results: [layout verification](project-layout-verification.txt).


## Backend scaffold — task 2.4

The backend now contains Java source, automated tests, the verified Gradle 9.8.0
Wrapper, Groovy build files and dependency locks. Main class:
[RelayApplication](../backend/src/main/java/com/relay/RelayApplication.java).
It uses Java 21 and Boot 4.1.1 with Web MVC, Validation, JPA, Security, Flyway,
Actuator, Connector/J and the Flyway MySQL module. Required resolved versions match
the baseline; [runtime dependency report](backend-runtime-dependencies.txt).
No global Gradle installation is needed. Worker bootstrap is implemented in 2.5;
entities, queue execution, workflow routes and management-token authentication remain later tasks.

From `backend/`, build and test:

```sh
./gradlew --gradle-user-home .gradle/user-home --no-daemon test bootJar
```

The explicit user-home keeps downloaded artifacts/caches in the ignored backend
`.gradle` directory. First use needs network access to Gradle, its plugin repository
and Maven Central. Maven Central is a package repository; the build tool is Gradle.
The sandbox used by the agent needs approval for Gradle's local lock socket and
HTTP test-server bind. Normal terminal use does not require an agent approval.

The expected output is BUILD SUCCESSFUL, 78 passing test invocations (including 23 HTTP tests) and
`build/libs/relay-backend-0.1.0.jar`. Gradle may show tasks as UP-TO-DATE on a repeat
run; that does not mean tests reran. Tests in this task used a real random-port
HTTP server with scaffold profile; no MySQL test passed or was attempted.

Start the explicit database-free foundation:

```sh
java -jar build/libs/relay-backend-0.1.0.jar --spring.profiles.active=scaffold
```

It binds only 127.0.0.1 at port 8080; `RELAY_API_PORT` can select another port,
or `--server.port=0` can request a free port. Stop with Ctrl-C. A port already
occupied prevents startup; do not terminate unrelated processes. The scaffold
profile is not a third product execution mode: it is an opt-in foundation probe
and must not be combined with a nonblank RELAY_MODE. Product mode and database
variables are now bound as documented below; engine/provider settings remain planned.

Scaffold startup excludes DataSource, JPA and Flyway auto-configuration and
registers an unavailable database health indicator. Liveness is 200 UP;
readiness and aggregate health are 503 OUT_OF_SERVICE. Do not treat this profile
as a functional application. The default configuration retains persistence
wiring, uses `ddl-auto=validate`, disables SQL initialization and Open EntityManager
in View, and cannot start without database configuration. Domain V1 migration is provided in3.1;
API migration ownership and worker bootstrap are implemented in 2.5; real DB startup verification remains 2.9. No fallback database exists.

Only the three exact health GET paths are public. Other paths/methods return 401;
there is no generated login password, session or working bearer authentication.
Health details/components are hidden; internal error dispatch preserves malformed
JSON HTTP 400 without exposing exception details. See the full
[health API reference](../API_DOCUMENTATION.md).

From the project root, reproduce the packaged smoke test:

```sh
python3 scripts/check_backend_smoke.py
```

It launches its own temporary-port process, checks 200/503/401 results, stops that
process, and verifies missing/invalid mode, mixed scaffold, missing DB and credential-bearing JDBC URL startup failures.
Logs go to ignored `backend/build/`. It does not start MySQL or use provider keys.

Gradle archive/wrapper checksums were verified before use:
[wrapper provenance](backend-wrapper-provenance.json). Application dependencies are
locked in `backend/gradle.lockfile`; the Boot plugin version is explicit in
`backend/build.gradle` (no separate plugin-classpath lockfile). Review and regenerate locks deliberately
with `./gradlew --gradle-user-home .gradle/user-home --no-daemon test bootJar --write-locks`
when changing dependencies; then run a build without `--write-locks`.
Do not hand-edit locks. No build scan, repository publication or deployment was used.
The Boot test infrastructure emitted a Mockito agent/CDS warning on Java 21;
all tests passed, and no application mock or provider was involved.

Actual outcomes: [backend scaffold verification](backend-scaffold-verification.txt).
MySQL Compose and startup/reset procedures are documented below; frontend scaffold is documented below (2.7).


## API and worker launch — task 2.5

The same executable jar selects its process role from `RELAY_MODE`. The environment
processor runs after configuration loading and before web context selection.
Only exact lowercase `api` and `worker` are accepted; missing, blank, unknown or
combined values fail startup. Spring profiles named api/worker are rejected: use
RELAY_MODE. The scaffold diagnostic requires mode unset or blank.

| Setting | Bootstrap behavior |
| --- | --- |
| RELAY_MODE | Required api or worker |
| RELAY_DB_URL | Required jdbc:mysql://host[:port]/schema; single host, schema letters/digits/underscore, port 1–65535; no userinfo, query or fragment |
| RELAY_DB_USER / RELAY_DB_PASSWORD | Required nonblank values in both modes |
| RELAY_DEMO_TOKEN | Required nonblank for API; unused by worker; authentication is task 3.3 |
| RELAY_API_PORT | API integer 1–65535, default 8080; ignored by worker |
| RELAY_DB_TX_TIMEOUT_MS | Integer 1000–60000 milliseconds, default 5000 |

Both processes derive the same datasource and Flyway URL/credentials. These
settings, web type, local bind address and schema policy take precedence over
conflicting generic Spring properties. Supply secrets through your local process
environment; backend .env is not loaded automatically. For example, in each shell
from backend/, once MySQL and migrations exist:

```sh
export RELAY_DB_URL=jdbc:mysql://localhost:3306/relay
export RELAY_DB_USER=relay
read -r -s RELAY_DB_PASSWORD
export RELAY_DB_PASSWORD
```

Enter the configured password at the silent prompt. In the API shell also read
and export the chosen local management token, then launch:

```sh
read -r -s RELAY_DEMO_TOKEN
export RELAY_DEMO_TOKEN
RELAY_MODE=api java -jar build/libs/relay-backend-0.1.0.jar
```

In the worker shell, with the same database environment:

```sh
RELAY_MODE=worker java -jar build/libs/relay-backend-0.1.0.jar
```

API runs Flyway migration before completing startup. Worker calls validate/info
only and rejects missing history, pending migrations or incompatible history;
future versions are not ignored. It never migrates, repairs, baselines or cleans.
Start the matching API migration first. Both modes use JPA schema validation,
disable automatic SQL initialization and Open EntityManager in View, disable
Flyway clean/baselining and connection retries. Worker has no servlet context or
HTTP security beans; Spring keep-alive keeps a successfully started process alive
until Ctrl-C. Queue polling and execution remain Phase 4.

The shared pool has maximum size 5 and READ_COMMITTED isolation. The transaction
timeout also bounds pool acquisition/initialization and JDBC connect/socket waits,
and supplies JPA query/lock timeout hints. Pool validation timeout is 1000 ms.
The 1000–60000 bootstrap range is a bounded subset of the recovery design's general
integer rule. It keeps waits finite without accepting subsecond driver/transaction
settings; actual MySQL timeout/locking behavior needs integration verification.
These per-operation limits are not a total process startup deadline. JDBC URL
parameters are excluded so timeout and credential configuration have one source.
Configuration validation errors name the setting/rule without echoing its value;
migration strategy errors omit nested database exceptions.

Verification: 78 automated tests pass, including the 23 existing real HTTP tests,
configuration boundaries/property binding, migration strategy mocks and an actual
non-web worker Spring context with explicitly test-only database exclusions.
[LaunchConfigurationTest](../backend/src/test/java/com/relay/bootstrap/LaunchConfigurationTest.java),
[DatabaseStartupConfigurationTest](../backend/src/test/java/com/relay/bootstrap/DatabaseStartupConfigurationTest.java),
and [WorkerContextTest](../backend/src/test/java/com/relay/bootstrap/WorkerContextTest.java)
cover bootstrap behavior. Packaged invalid-configuration checks and scaffold probes
pass via the smoke script above. Exact results: [launch verification](launch-modes-verification.txt).

**Task 2.9 verifies Java application startup against isolated MySQL using a test-only migration.** Task3.1 supplies the domain migration; start API first on a fresh DB, then the worker.
Compose is provided in task 2.6 below; Java component startup is 2.9, and domain migrations are 3.1.
Run the commands above against the configured database in those tasks and verify
API readiness, worker schema rejection/acceptance and clean process shutdown.

## MySQL Compose — task 2.6

[compose.yaml](../compose.yaml) runs only MySQL; the API, worker, frontend and mocks
remain host processes. It pins MySQL 8.4.11 to the verified multi-platform digest in
[image provenance](mysql-image-provenance.json). Docker selects the native architecture.
The named volume `relay_mysql_data` persists data; port 3306 is published only on
127.0.0.1. MySQL initializes schema `relay` and account `relay`, with privileges on
that schema; root credentials are separate. No domain tables are created here.

From the project root, with Docker Desktop running:

```sh
python3 scripts/init_mysql_secrets.py
docker compose config --quiet
docker compose up -d --wait --wait-timeout 180
docker compose ps
```

The helper creates independent random passwords under ignored `local-data/mysql/`
with file mode 0600 and directory mode 0700 on creation. Repeating it preserves
existing values; an empty or non-regular existing secret fails. It prints no
passwords. Compose mounts these files as secrets using the official image's
`MYSQL_PASSWORD_FILE` and `MYSQL_ROOT_PASSWORD_FILE` support. They are ordinary
local files, not an encrypted secrets vault. Missing files prevent startup.
The health check performs an authenticated `SELECT 1` as relay over TCP; it does
not use mysqladmin ping, which can succeed after authentication failure.
Expected: the mysql service is healthy. First image download/initialization can
require extra time; inspect `docker compose logs --tail 100 mysql` on failure.
An unhealthy service remains available for diagnosis; `up --wait` returns nonzero.

Connect with the bundled client without printing or placing passwords in arguments:

```sh
docker compose exec mysql sh -c 'MYSQL_PWD="$(cat /run/secrets/mysql_password)" exec mysql --protocol=TCP -h 127.0.0.1 -u relay -D relay'
```

Run `SELECT VERSION(), DATABASE();` — expect `8.4.11` and `relay`; type `exit` to leave.
The backend does not load Compose's environment or secret files itself. From the
project root, export its matching settings before starting API or worker as described
in task 2.5 (also export the API management token in the API shell):

```sh
export RELAY_DB_URL=jdbc:mysql://localhost:3306/relay
export RELAY_DB_USER=relay
export RELAY_DB_PASSWORD="$(cat local-data/mysql/password)"
```

Copying [.env.example](../.env.example) to root `.env` is optional for Compose
port/secret-path overrides. It is distinct from backend/.env and contains no
password values. To use another host port, set `RELAY_MYSQL_PORT=3307` in root
.env or your shell, recreate with `docker compose up -d --wait --wait-timeout 180`,
and change the backend JDBC port to match. Use an available port in 1–65535.
Port 0 is reserved for automated tests' ephemeral allocation. A port conflict
fails startup; inspect the owner or choose a free port. Do not stop unrelated services.
The secret initializer always writes the default local-data/mysql directory;
if overriding RELAY_MYSQL_SECRET_DIR, provision that directory's two password files
separately. Keep the same root .env/shell settings throughout lifecycle commands.

Normal shutdown and restart preserve the named volume:

```sh
docker compose down
docker compose up -d --wait --wait-timeout 180
```

`docker compose stop mysql` also preserves data; restart with the same up command.
No automatic restart policy is configured: start the database explicitly after
Docker Desktop restarts. Repeated up reuses data. Initialization variables/files
only create accounts on an empty data directory; changing a password file does
not rotate an existing account and will make the health check fail. Restore the
matching file or deliberately rotate the database account and all consumers together.

**Destructive local reset:** stop API/worker first and preserve anything you need.
The following commands delete this Compose project's database volume and every
record in it. Run only when you intentionally want an empty development database:

```sh
docker compose down --volumes
docker compose up -d --wait --wait-timeout 180
```

Secret files remain, so the same account/password initializes the fresh schema.
Do not use volume/system prune as a reset. The agent's lifecycle test resets only
its unique disposable project, never the ordinary relay volume. Compose reset creates an empty relay schema. Start API to apply the task3.1
domain migration; repeatable seed loading is implemented in3.9 below. Worker rejects missing migration history.

Reproduce automated infrastructure checks:

```sh
python3 scripts/check_mysql_compose.py
```

This requires Docker and the pinned image (Docker pulls it if absent). It creates
private temporary credentials, an ephemeral localhost port and a uniquely named
project; verifies config, missing secrets, secret preservation, authenticated SQL,
rejected credentials/privileges, repeated startup, persistence and destructive reset;
then removes only its own containers/network/volume. No existing data is used.
All these checks passed on 2026-09-25. The ordinary local service also started
healthy on 127.0.0.1:3306 and returned version 8.4.11/schema relay; it was left running.
Exact results are recorded in [MySQL verification](mysql-compose-verification.txt).
Java-to-MySQL connectivity and Flyway readiness remain task 2.9; database domain
constraints/transactions remain 3.1/3.10. There are no HTTP route changes in 2.6.

Sources: [official MySQL image](https://hub.docker.com/_/mysql),
[Compose up and health waiting](https://docs.docker.com/reference/cli/docker/compose/up/),
[Compose down and explicit volume deletion](https://docs.docker.com/reference/cli/docker/compose/down/).

## Frontend scaffold — task 2.7

The React/TypeScript/Vite console provides Workflows, Runs and Approvals navigation,
five placeholder routes under `/console`, root redirect and unknown-page recovery.
It displays no invented records, login success, database state or enabled actions.
Workflow detail and run detail accept encoded IDs and render them as escaped text.
The shell moves focus to the page heading on navigation and wraps at narrow widths.
Access setup is implemented in3.4 below; data views, polling and decisions remain6.3–6.12.

From the project root:

```sh
cd frontend
source ~/.nvm/nvm.sh
nvm use
npm ci
```

The local Node 24.21.0 installation includes npm 11.19.0. `.nvmrc`, engines and
packageManager record these pins; use the directory's selected version for every
npm command. The global nvm default was not changed. All direct packages use exact
versions from the baseline; `@playwright/test` 1.63.0 is added only for verification
(Node >=20 per registry metadata). Dependencies resolve through package-lock.json;
no pnpm/yarn lockfiles or global Vite installation are needed.

If frontend/.env.local does not exist, copy .env.example to it, without overwriting
existing local configuration. A local copy was prepared during this task. It sets:

```dotenv
VITE_RELAY_API_BASE_URL=http://localhost:8080
```

This must be an explicit HTTP(S) origin with no credentials, path, query or fragment.
Missing/invalid configuration fails Vite startup/build. All VITE_ values are public;
never include tokens, database passwords or provider keys. Restart Vite after changes.

```sh
npm run dev
```

Open http://127.0.0.1:5173/console/workflows. The development server binds only to
127.0.0.1 and fails if 5173 is busy; it does not silently change ports. Stop your
own server with Ctrl-C. Direct detail links, reload and browser back/forward work.
The UI uses local static resources; Connect explicitly requests the protected workflow list.

The development proxy forwards only `/workflows`, `/runs`, `/approvals`, `/hooks`
and `/actuator` namespaces (exact prefix boundary) to the configured backend,
preserving paths/query/status. `/console` and lookalike prefixes are not proxied.
The reusable client uses the browser origin in development to reach this proxy,
and the configured backend origin in a production build. Proxy behavior is tested
against a disposable HTTP stub; no new backend endpoints or CORS configuration
were added. Direct cross-origin access now uses task3.4 CORS, described below.
`npm run preview` serves the built shell at 127.0.0.1:4173 without the dev API proxy;
it is a local preview, not a deployment. A later static host needs index.html
fallback for `/console/*`, while preserving API routing.

`src/api.ts` returns JSON as unknown for later domain validators; HTTP 204 returns
undefined. It accepts only root-relative API namespace paths, blocks cross-origin
redirect following, omits cookies, and attaches a supplied bearer token per request
without storing it. HTTP errors retain status only; format/network/timeout/cancel
errors contain fixed safe messages, never raw response bodies. Default timeout is
15 seconds (configurable 1–60000 ms for tests/callers); AbortSignal cancels pending
reads. Every request is sent once, including mutations. Cancellation/timeout does
not prove the server rolled back an action. Session invalidation, schema validation,
unknown-outcome reconciliation, retry/polling policy and caching remain later UI work.

Verify from frontend/:

```sh
npm test
npm run build
npx playwright install chromium
npm run test:browser
```

Build runs strict TypeScript checking before Vite. Native Node tests cover the API
transport and a real local Vite proxy. Chromium browser tests use a temporary
production preview process, deep links/reload, current navigation, back/forward,
keyboard focus, safe IDs and 320px overflow. Test output/screenshots stay ignored
under frontend/test-results. This is scaffold coverage, not full accessibility or
end-to-end workflow acceptance. Exact actual results and initial regression:
[frontend verification](frontend-scaffold-verification.txt).

Primary references: [Vite server/proxy options](https://vite.dev/config/server-options),
[React Router declarative routing](https://reactrouter.com/start/declarative/routing).

## Supplied mocks and fixtures — task 2.8

Use `python3 scripts/run_mock.py world` and `python3 scripts/run_mock.py provider`
from the root in separate terminals. Python standard library only; localhost ports
9210/9001. Stop with Ctrl-C. The unchanged fixture/source snapshot is reused with
full hash verification. See [mock setup, contracts and tests](MOCKS.md) for commands,
license provenance, reset/failure injection, and the prose-provider/ledger limitations.
Run `python3 scripts/check_local_mocks.py` for isolated verification. No real API key
is needed and no automatic backend/provider integration is claimed.


## Component startup verification — task 2.9

The packaged API and non-web worker have been verified against real MySQL8.4.11
using a disposable Compose project and a temporary SQL migration. This establishes
Connector/J/JPA/Flyway startup, API readiness, worker validation and database outage
recovery. It does not implement the domain tables scheduled for3.1.

Reproduce from the project root after building the backend jar:

```sh
python3 scripts/check_component_startup.py
```

Requires Java21, Python, Docker and the pinned MySQL image. The checker creates
private temporary credentials, an isolated named volume and free localhost ports;
all Java output goes to ignored backend/build/component-check/. It starts the
worker against an empty schema (expected failure), runs API migration and readiness,
starts a matching non-web worker, stops/restarts only its own database to check
readiness503/liveness200/recovery200, and repeats API startup to verify migration
is not reapplied. Pending, altered-checksum and future-version histories must fail
worker startup; wrong credentials must fail API startup. It removes only its own
processes/volume/network. It never writes to the ordinary relay database.

The temporary V1__startup_probe.sql creates a single probe table/row outside the
repository; it is not a production migration or a substitute for3.1. On the normal
empty relay schema, task3.1 now lets API apply the domain migration before worker
startup. The isolated scaffold still provides database-free UI/health demonstrations. No real AI key is needed for these checks.

Existing frontend5173, scaffold API8080 and mocks9001/9210 were probed read-only and
returned200 during this task. They were not stopped/reset. This confirms component
availability, not workflow execution or browser authentication. Full production-like
schema/engine/API/browser integration remains the later implementation phases.
Actual commands/results: [component verification](component-startup-verification.txt).


## Domain schema and normal database startup — task3.1

The backend jar now includes V1__relay_schema.sql. It creates workflows, runs,
steps, step_attempts, approvals and queue_jobs plus Flyway's history table. It adds
no workflow records or API routes. This task verifies the migration on a disposable
database; your existing relay database was not modified automatically.

To apply it to your local relay database, start MySQL with the earlier Compose
instructions, stop your own scaffold server if using port8080, then from the root:

```sh
export RELAY_DB_URL=jdbc:mysql://localhost:3306/relay
export RELAY_DB_USER=relay
export RELAY_DB_PASSWORD="$(cat local-data/mysql/password)"
read -r -s RELAY_DEMO_TOKEN
export RELAY_DEMO_TOKEN
RELAY_MODE=api java -jar backend/build/libs/relay-backend-0.1.0.jar
```

Type your chosen local demo token at the silent prompt. It authenticates management requests in API mode as described in3.3 below. Do not pass scaffold profile.
Expected: Flyway applies version1 once and API readiness is200. Repeated startup
validates history without rerunning V1. Use matching DB credentials in another
terminal and run `RELAY_MODE=worker java -jar backend/build/libs/relay-backend-0.1.0.jar`.
Worker validates the applied migration and remains alive with no HTTP interface;
queue execution still remainsPhase4. Empty, incompatible or pending schema fails.

Build/verify from backend with the selected Java21 runtime:

```sh
./gradlew --gradle-user-home .gradle/user-home --no-daemon --offline test bootJar
```

From root run `python3 scripts/check_component_startup.py --domain` for the packaged
schema tests on disposable MySQL. Without --domain packaged V1 plus a temporary V2 probe
migration are used; JPA validation requires the domain tables. Both modes clean up only their own database/resources;
logs are ignored under backend/build/component-check. No H2 substitutes are used.

Do not edit V1 after applying it. Make later changes in a new numbered migration.
MySQL DDL is not a single rollbackable migration transaction: a failed multi-statement
migration can leave partial tables. Startup fails; inspect logs/history/schema before
repair. Do not automatically baseline, clean, repair or ignore checksums. For a
throwaway development database only, the documented explicit Compose volume reset
followed by API startup replays migrations. For valuable data, preserve it and prepare
an explicit recovery migration/procedure instead of deleting it. Never point the
old test-only startup migration at the normal schema. UTC/strict-mode writer settings,
JPA mappings, cross-row transactions and query plans are verified in3.2/3.10 onward.
Actual evidence: [domain schema verification](domain-schema-verification.txt).


## JPA and API foundation — task3.2

Six entities/repositories, composite IDs, string enums, identifier validation, safe
summary DTOs and scoped domain error advice are implemented. No domain CRUD or
authentication routes were added. Normal API startup applies V1 and Hibernate now
validates the real entity mappings before startup completes.

From backend/, run:

```sh
./gradlew --gradle-user-home .gradle/user-home --no-daemon --offline test bootJar
```

From the project root, run:

```sh
python3 scripts/check_persistence.py
```

This creates disposable MySQL and runs Gradle mysqlTest with a non-UTC JVM to check
UTC storage, then removes only its own resources. Credentials are passed through
process environment. Regular tests need no database; mysqlTest requires its DB and
fails rather than skipping. Do not run fixture-writing tests against ordinary relay.

The component checker now uses domain V1 plus temporary V2 (pending probe V3) when
called without flags; --domain retains the packaged-only schema checks. This change
is necessary because mapped entities cannot validate against only a probe table.
No migration/dependency version or existing user database changed. Entities do not
implement lifecycle authorization, approval decisions or worker dispatch. Actual
commands/results: [persistence verification](persistence-foundation-verification.txt).


## Management token — task3.3

Normal API mode now authenticates management namespaces with Authorization: Bearer
and RELAY_DEMO_TOKEN. Generate a private random token, set it in the API environment,
and restart API after changing it. Accepted configuration uses HTTP bearer characters
without whitespace; hex tokens generated by Python secrets.token_hex(32) work.
The worker does not need the management token. Scaffold mode remains health-only.

Missing/wrong token returns401 JSON; a valid token passes the management boundary but
now reaches the implemented workflow CRUD routes; decision routes remain unimplemented. Health remains
public; webhook routes remain denied until4.1. The single credential identifies
`demo-operator`; no client-supplied decision-maker name is accepted. Approval evidence
can be recorded through the internal guarded entity method, but actual decision/run/job
transactions remain5.2/5.3. CORS and frontend access are implemented in task3.4 below.

Run the existing Gradle test/bootJar command and `python3 scripts/check_persistence.py`
for security regressions and realMySQL decision-evidence persistence. No local user DB
or running service was changed. Detailed headers/statuses and limitations:
[API authentication](../API_DOCUMENTATION.md#management-authentication--task33).
Actual results: [authentication verification](authentication-verification.txt).


## Browser access and CORS (task3.4)

Start the API and frontend using the Gradle/npm commands above. Open the console,
enter the API process's `RELAY_DEMO_TOKEN` in **Management token**, then press Enter or
**Connect**. Wrong credentials show a token error. Valid credentials now confirm API access through GET/workflows (implemented in3.6).
Connect/Disconnect UI is verified with contract-compatible browser test doubles; real list/CORS is also verified;
the placeholder data screens remain later work.

The password field clears on submission. Credentials stay in memory and reload clears
the session. Disconnect (or Cancel while connecting) aborts pending requests and returns
focus to token entry. Protected401 clears the session; old responses cannot restore it.
Never put the management token into a VITE variable. There is no automatic mutation retry.

API `RELAY_ALLOWED_ORIGINS` defaults to `http://localhost:5173`; specify comma-separated
exact origins if needed, e.g. `http://localhost:5173,http://127.0.0.1:4173` for production
preview. Do not add a trailing slash. A blank value disables cross-origin access.
Restart your API after changing this setting. The Vite development proxy uses the
browser's own origin; production preview calls `VITE_RELAY_API_BASE_URL` directly and
needs its exact origin allowed. A browser CORS failure can appear as a network error.

Verification (Node baseline selected with `source ~/.nvm/nvm.sh && nvm use` in frontend):
`npm test`, `npm run build`, `npm run test:browser`. After building backend bootJar,
run `python3 scripts/check_component_startup.py --browser` from the root for real
Chromium preflight/readable401/authenticated200/unlisted-origin denial on a disposable
MySQL/API. This requires Docker and installed Playwright Chromium. It cleans up its
owned resources and does not alter your regular database or running services.
Evidence: [browser access verification](browser-access-verification.txt).


## Workflow draft APIs (task3.6)

Rebuild with `./gradlew --gradle-user-home .gradle/user-home --no-daemon --offline test bootJar`
in backend, then restart your API using the existing launch instructions and the same
DB/token settings. No regular user service is restarted by the task verification.
The console's Connect button can now confirm a valid token through GET/workflows;
it does not yet render the workflow list. No OpenAI key is required.

Create/edit via the [API examples](../API_DOCUMENTATION.md). GET/list and GET/detail
return persisted drafts; PUT is a full replacement with immutable id. Webhook secrets
are omitted from reads, so preserve the original definition for future edits. Repeat
POST yields409. Publishing is implemented in3.8 below; triggering remains later work.

Run `python3 scripts/check_persistence.py --workflows` from the root to exercise real
HTTP CRUD/concurrency/restart on disposable MySQL. Run the same command without the
flag for existing JPA integration tests. Results: [workflow API verification](workflow-api-verification.txt).

## Publish validation (task3.7)

The backend now validates a parsed workflow's catalog parameters, edges, templates,
literal node constraints and AI output schemas through `PublishValidator`. Task3.8 now attaches this component to the publish endpoint and atomic publication. Draft creation
and editing still accept unfinished graph/parameter definitions. No browser action or AI
provider key is needed for this task.

The planned `com.networknt:json-schema-validator:3.0.6` dependency is now pinned and
locked. Standard Draft2020-12 meta-schemas come from its jar; supplied remote schema
references are rejected and schema fetching is disabled. No dependency upgrade to a
newer release was made as part of this task.

From backend, run `./gradlew --gradle-user-home .gradle/user-home --no-daemon --offline test bootJar`.
The automated tests validate the four original seeds, exercise invalid publish definitions
and prove that schema references do not call an isolated HTTP server. See
[publish validation results](publish-validation-verification.txt).


## Publication and snapshots (task3.8)

Rebuild the backend with the Gradle test/bootJar command above and restart your API
using its existing DB/token environment. After creating a valid draft, publish with:

```sh
curl -i -X POST http://127.0.0.1:8080/workflows/wf_demo/publish \
  -H "Authorization: Bearer $RELAY_DEMO_TOKEN"
```

Send no body. Expect200 and status published; invalid definitions yield400 with a reason
and field path. An unchanged repeat preserves published_at. Editing returns status draft
and retains the previous publication. GET detail now includes redacted published_definition
and published_secret_configured, with the current draft still in definition. Concurrent
edits can yield409 on publication: reread before deciding to retry. Full reference and
create example: [API documentation](../API_DOCUMENTATION.md).

The internal snapshot helper is tested against MySQL, but trigger endpoints, initial
queueing and execution are still later tasks. No run is started by publishing. No ChatGPT
API key or new frontend input is required; UI data screens remain later work.

Root verification commands: `python3 scripts/check_persistence.py --publication` for
publication/snapshot/race tests, and `python3 scripts/check_persistence.py --workflows`
for existing CRUD regression. Both own and remove disposable MySQL databases. They do
not restart your normal services or alter your database. See [results](publication-verification.txt).


## Repeatable seed loading (task3.9)

Normal API startup now loads the four supplied workflows as Published after migrations
and entity initialization, before startup completes. `RELAY_LOAD_SEEDS` defaults to `true`.
Rebuild the backend with `./gradlew --gradle-user-home .gradle/user-home --no-daemon --offline test bootJar`
and restart your API with its usual DB/token settings when you want to use this behavior.
No normal API process or ordinary database was changed during task verification.

The source file is bundled unchanged, including IDs, mock addresses, webhook secrets,
and graphs. The IDs are wf_support_triage, wf_expense_approval, wf_slow_fulfillment and
wf_runaway. The loader validates all definitions, then inserts missing IDs atomically.
It never overwrites, resets or republishes an existing ID—even an edited draft or a
preexisting workflow with different content. Existing timestamps/revisions and unrelated
workflows are preserved. Thus an edited seed may remain draft after restart; use normal
PUT/publish APIs if you deliberately want to change it. There is no automatic reset.

Startup logs only `Workflow seeds: created=N, preserved=N`. Fresh DB expects4/0;
unchanged restart expects0/4. An existing subset is preserved while missing IDs are
inserted. Two API instances can load concurrently without duplicate IDs; duplicate/lock
conflicts retry the entire transaction at most3 times. Invalid seed data or exhausted
DB failures abort startup; failed batches leave no partial inserts. Worker and scaffold
processes never load seeds. The worker can only execute workflows once its later
execution tasks are implemented; seeding itself creates no runs/jobs or external calls.

For an intentionally empty database or isolated fixtures, set this in the API environment
before launch (the dotenv file is still not loaded automatically):

```sh
export RELAY_LOAD_SEEDS=false
```

Only lowercase `true` and `false` are accepted in API mode. Unset the variable or set it
to true to restore normal startup loading; changing it requires an API restart. Disabled
loading does not delete previously loaded workflows. Keep the existing management token;
no ChatGPT/OpenAI key is needed. GET/workflows should list four published seed IDs on a
fresh normal startup. The console can confirm Connect, but data tables remain6.x work.

Run `python3 scripts/check_persistence.py --seeds` from the project root for disposable
MySQL startup/restart/concurrency/rollback verification. Other integration runners explicitly
set RELAY_LOAD_SEEDS=false to preserve their independent empty-database fixtures.
Results: [seed verification](seed-loading-verification.txt).

## Phase 3 Testcontainers verification

With Java21 and Docker running, from `backend/`:

```sh
./gradlew --gradle-user-home .gradle/user-home --no-daemon --offline test bootJar testcontainersTest
```

For a first dependency download, omit `--offline` (keep the committed dependency lock).
Docker may need network access for the pinned MySQL image and Testcontainers' Ryuk
cleanup image even when Gradle is offline. Testcontainers2.0.5 comes from the existing
Spring Boot BOM. The Java suite uses `mysql@sha256:0744ee5ef89ce6ccfa13de3e579fe6b9e27f93dd70da9c06d2c908b1b193fb8d`,
the same MySQL8.4.11 content as Compose. The digest-only syntax avoids Testcontainers'
combined tag/digest compatibility parsing issue.

`testcontainersTest` explicitly runs five comprehensive integration cases, sequentially,
each in a fresh container with a dynamic port and generated test credentials. It strips
inherited application configuration; no `.env`, existing database or running Relay API is
used. Each container is closed after its class, including assertion failures, and Ryuk
provides abnormal-process cleanup. No reusable containers or silent Docker-unavailable
skips are enabled. If Docker is unreachable the task fails; start Docker and rerun.
On Docker Desktop, use its normal active socket configuration; custom remote Docker
hosts may require the standard Testcontainers Docker environment settings.

Coverage: migration creation/restarts/worker validation/checksum rejection; all six JPA
repositories and SQL semantics; authenticated HTTP CRUD; publish validation and edit
races; immutable run snapshots through republish/restart; atomic/repeatable seed loading;
and seeded definition editing/publishing through HTTP. The ordinary `test` task remains
Docker-independent. Existing Compose-backed `scripts/check_persistence.py` commands
remain available. Engine claim/query-plan behavior belongs to Phase4 when those queries
are implemented. Actual results: [Testcontainers evidence](testcontainers-verification.txt).

## Phase 4 deterministic worker

Build/test from `backend/` with Docker running:

```sh
./gradlew --gradle-user-home .gradle/user-home --no-daemon --offline test bootJar testcontainersTest
```

The integration task also builds the executable jar for its packaged worker crash test.
Tests own their MySQL containers, loopback mock world, proxy and worker processes; they
never reset the ordinary local database or use your running mock world. Python3 is needed
for the unchanged supplied mock handler. Test reports are under `backend/build/reports/tests/`.
See [recorded results](phase4-verification.txt).

For manual use, first run the existing API startup instructions so migrations/seeds are
ready. In another terminal start `python3 scripts/run_mock.py world` from the repository
root (if your mock world is already listening on9210, keep using it). In a backend terminal,
export the same `RELAY_DB_URL`, `RELAY_DB_USER`, `RELAY_DB_PASSWORD` used by the API, then:

```sh
RELAY_MODE=worker java -jar build/libs/relay-backend-0.1.0.jar
```

The worker is non-web, polls MySQL, and does not require the management demo token.
Only one worker process is supported; stop it before launching another. The API needs its
existing token. Use the manual/hook examples in [API documentation](../API_DOCUMENTATION.md)
to receive202 and a run ID. Since Phase6, follow runs with `GET /runs/{runId}` or the console
(see [console demo](CONSOLE_DEMO.md)); the SQL below remains useful for low-level diagnosis:

```sql
SELECT run_id,status,steps_executed,error FROM runs ORDER BY created_at DESC;
SELECT run_id,sequence,node_id,status,final_attempt,error FROM steps ORDER BY run_id,sequence;
SELECT run_id,step_sequence,attempt_no,status,cause FROM step_attempts ORDER BY run_id,step_sequence,attempt_no;
```

`wf_runaway` with `{"order_id":"ord_2001"}` is a deterministic loop/cap example. Expense
amount50 takes the notification path. Workflows reaching AI or approval nodes fail safely
until Phase5 implements them. There is no requirement to add a ChatGPT/OpenAI key for Phase4.

Engine configuration is in `backend/.env.example`; Java does not automatically load dotenv.
`MOCK_WORLD_URL` defaults to `http://localhost:9210`. `RELAY_HTTP_ALLOWED_ORIGINS` defaults to
that origin and may be an exact comma-separated list of HTTP(S) origins (scheme, host and
port; no credentials/path/query/fragment). Keep the provided localhost seed destinations
consistent with the allowlist. Origins are trusted deployment settings, not workflow input;
there is no unrestricted internet destination fallback or redirect following.

All recovery defaults/ranges follow [RECOVERY.md](RECOVERY.md). API acceptance freezes the
validated policy into each run; retry budgets, transport deadlines and lease settings are
read from it on recovery. Poll interval is process configuration. Invalid/unsupported stored
policy blocks that job and logs an operational error; other eligible runs still progress.
Database connection limits also remain bounded by the deployment's bootstrap configuration.

The packaged entry point fixes the JDK HTTP retry settings before client initialization:
`jdk.httpclient.disableRetryConnect=true`, `jdk.httpclient.enableAllMethodRetry=false`, and
`jdk.httpclient.redirects.retrylimit=1`. Only persisted engine attempts may retry. These are
[documented Java21 HTTP properties](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/module-summary.html).
Gradle tests set the same properties at JVM startup. Programmatic embedded launchers must
set them before constructing any HTTP client; the supported runtime is the packaged main.

## Phase5 AI and human control

**Current provider:** user-selected OpenRouter, configured and live-verified2026-09-28.
See OpenRouter setup below. Earlier direct-OpenAI quota diagnostics are historical;
no OpenAI credit purchase is required to use this verified OpenRouter configuration.

Java21/Gradle and MySQL startup remain as documented above. Rebuild from `backend` with
`./gradlew --gradle-user-home .gradle/user-home --no-daemon --offline bootJar` and restart
your API and single worker to use Phase5. Both processes need their normal DB configuration;
the API needs the management token. Approval listing/decisions and cancellation are now
implemented; the frontend approval and run-trace screens remain Phase6. Use the examples
in [API_DOCUMENTATION.md](../API_DOCUMENTATION.md#human-approvals-and-cancellation--phase5).

Default worker AI mode is `mock-http`. Start the unchanged mock from the repository root:

```sh
python3 scripts/run_mock.py provider
```

The default origin is `http://localhost:9001`, model `alpha-small`, with a local mock-only
bearer token when the key is blank. The mock returns prose, so an AI JSON-schema workflow
fails clearly after one corrective request; this is expected and does not demonstrate a
successful classifier. Tests use separate controlled JSON fixtures for successful schema
and branch behavior. The mock is useful for HTTP auth, usage,503/429 and timeout testing.
No real key is needed for ordinary development/regression tests.

For real AI, Phase5 provides the OpenAI Responses adapter. Its endpoint is fixed to
`https://api.openai.com/v1/responses`; `RELAY_AI_BASE_URL` cannot redirect a real key.
Use a model ID your API account can access; the application has no default real model.
A ChatGPT subscription is not used as an API credential. From the repository root run:

```sh
python3 scripts/configure_ai.py
```

Enter the model ID and API key at the local terminal's hidden key prompt. The helper
writes ignored `backend/.env.ai` with owner-only0600 permissions and refuses to overwrite
an existing file. Never paste a key into chat, source code, frontend variables or a shared
terminal transcript. To change it later, edit that private local file. The three fields are
`RELAY_AI_MODE=openai`, `RELAY_AI_MODEL`, and `RELAY_AI_API_KEY`. Credential files are not
loaded automatically by Spring Boot.

Explicit live verification (one synthetic prompt, at most one schema repair, potentially
billable; no customer payload or database connection):

```sh
python3 scripts/verify_live_ai.py
```

The runner reads only the three allowed literal fields, does not execute shell content,
and passes them in the test process environment. It runs the opt-in Gradle `liveAiTest`.
The key is absent from command arguments and normal test output. Ordinary `test` and
`testcontainersTest` exclude live calls; the latter also strips inherited app credentials.
Success means a live HTTP response produced schema-valid JSON and reported usage. Failure
reports a safe code, such as `http_401`, `http_404`, `http_429` or `http_timeout`; fix local
account/model/access as needed and rerun explicitly. It does not prove every payload will
be classified correctly. Actual live verification status appears in Phase5 evidence. On2026-09-28 the configured key authenticated and model was listed, but generation returned429 insufficient_quota / credit_balance_exhausted. Add API credit in the provider account and rerun the explicit probe; a passing live generation is still outstanding.

To use the saved configuration for your worker, open its terminal from the repository
root, load your existing DB settings as usual, then explicitly load this helper-generated
file and start the packaged worker:

```sh
set -a
source backend/.env.ai
set +a
RELAY_MODE=worker java -jar backend/build/libs/relay-backend-0.1.0.jar
```

Only source the local file you generated and control. Do not print the environment. Stop
the existing worker first; keep one active dispatcher. Frozen prepared requests keep their
provider/model and repair prompt across retries; changing provider/origin can fail an
already prepared request with `ai_provider_configuration_changed`. Start a new run after
switching providers. API acceptance freezes timeout/retry policy, so configure both API
and worker consistently. Defaults: AI30s, HTTP10s, lease60s, renewal10s, DB5s; preserve
`lease > renewal + max(call timeout) + 2*DB timeout` when changing values.

The adapter separates fixed instructions/schema from untrusted user input, disables tools,
and validates strict JSON and schema locally. Usage and latency persist per attempt; no
credential appears in persisted request bodies/headers. A response lost during recovery
has unknown usage and may be billed again after retry; no exactly-once provider billing
is claimed. Cancellation stops new attempts and lets the bounded in-flight result settle.

Official protocol references consulted2026-09-28:
[OpenAI text generation](https://developers.openai.com/api/docs/guides/text) and
[API quickstart](https://developers.openai.com/api/docs/quickstart). The adapter aggregates
raw Responses output-text parts, keeps instructions separate from input and sends
`store=false`. Local schema validation remains authoritative.

Verification commands: Gradle `test bootJar testcontainersTest`,
`python3 scripts/test_ai_configuration.py`, the13 document consistency scripts, and the
explicit live command above. [Phase5 evidence](phase5-verification.txt) distinguishes
executed results from outstanding live verification. Containers/mock processes used by
tests are disposable and separate from the user's `relay` database.

### Resolving task5.9 credit exhaustion

The adapter now reports `ai_credit_balance_exhausted` for OpenAI's corresponding429
instead of retrying a generic rate-limit error. This requires adding API credits to the
organization associated with the configured key. Spend-limit errors instead require
reviewing the reported organization/project limit; a temporary rate limit still follows
Retry-After. Changing the model or repeatedly rerunning the test does not restore credits.
Check the correct API organization/project's billing settings, then run
`python3 scripts/verify_live_ai.py` once after the account change takes effect. A passing
live test is still required before marking5.9 complete. No account billing changes are
performed by the application. See [official error guidance](https://developers.openai.com/api/docs/guides/error-codes).

## OpenRouter setup — task5.9 amendment

User selected OpenRouter on2026-09-28. This is a separate credential destination from
direct OpenAI; the earlier OpenAI billing findings are historical and do not establish
OpenRouter's balance. The screenshot's OPENAI_API_KEY/OPENAI_BASE_URL are SDK conventions;
Relay uses its existing RELAY_* variables and a fixed endpoint instead.

The current private `backend/.env.ai` was switched without displaying or duplicating the
key to `RELAY_AI_MODE=openrouter` and `RELAY_AI_MODEL=openai/gpt-5-mini`. The supplied
OpenRouter key remains private, owner-only0600 and ignored. Do not replace that file with
the example. For a fresh setup with no private file yet:

```sh
python3 scripts/configure_ai.py --provider openrouter
```

Enter the prefixed model ID and the OpenRouter key at the hidden prompt. The helper refuses
to overwrite an existing file. To change an existing setup, edit that local file privately.
OpenRouter keys start with sk-or-v1-; the helper and adapter reject recognizable mismatches
before network dispatch. There is no automatic prefix addition or provider/model fallback.
The private migration used the already-selected gpt-5-mini as openai/gpt-5-mini.

Run the same explicit probe from the root:

```sh
python3 scripts/verify_live_ai.py
```

It reads the selected real-provider mode, makes one synthetic request with at most one
schema repair, and requires valid JSON plus usage. No real calls occur in ordinary tests.
HTTP402 maps to ai_payment_required: check OpenRouter credits/key limits.401/403 means
credentials/access need correction;429 is a temporary rate limit unless otherwise identified.
Errors never print the credential or provider's raw message. See the actual result in
[Phase5 evidence](phase5-verification.txt).

For normal execution, rebuild the backend and restart the single worker with the private
file loaded using the existing explicit `set -a; source backend/.env.ai; set +a` steps
above, alongside its usual DB settings. Keep one active worker. New runs use the newly
selected provider; frozen requests from previous modes are not silently redirected.
The default30-second AI deadline,1MiB response limit, schema validation, repair budget,
usage persistence and approval gates apply equally to OpenRouter. Direct OpenAI-specific
store=false does not constitute a storage/privacy guarantee for OpenRouter.
Official reference: [OpenRouter chat completions](https://openrouter.ai/docs/api/api-reference/chat/create-a-chat-completion).
