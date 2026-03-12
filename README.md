# NAS File Processor — Spring Boot (Gradle)

## Contents

- [Overview](#overview)
- [Response File Layout](#response-file-layout)
- [Input JSON Formats](#input-json-formats)
- [Large File Support](#large-file-support-100mb)
- [Malformed JSON Handling](#malformed-json-handling)
- [Completed / Error File Naming](#completed--error-file-naming)
- [Archive](#archive-disk-space-management)
- [Email Notification](#email-notification)
- [Trigger Response Structure](#trigger-response-structure)
- [Architecture](#architecture)
- [ControlM Integration](#controlm-integration)
- [Building & Running](#building--running)
- [Configuration](#configuration)
- [Downstream API Contract](#downstream-process-api-contract)
- [Testing & Coverage](#testing--coverage)
- [Helper Scripts](#helper-scripts)
- [Contributing](#contributing)

---

## Overview

This application monitors a NAS inbound folder for large JSON files, processes them in batches of 100 records via an external API, and routes files to the appropriate folder. For every file it writes:
- **One response JSON file per API batch call**
- **One final consolidated response JSON file** per source file

```
NAS Folders:
  nas/inbound/     ← Input JSON files (ControlM checks here every 10 min)
  nas/processing/  ← Working copy during processing (original stays in inbound until done)
  nas/completed/   ← Successfully processed files (moved from inbound)
  nas/error/       ← Failed files + .error.txt sidecar (timestamped)
  nas/responses/   ← Per-file subfolders with batch request/response + FINAL (see layout below)
  nas/archive/     ← Archived old files (when nas.archive.enabled=true)
```

**Recursive scan & structure:** When `nas.scan-recursive=true`, the app scans subfolders under `inbound` (e.g. `inbound/servicer_A/`, `inbound/servicer_B/`) and **preserves that structure** in `completed`, `error`, `processing`, and `responses`. Empty folders are removed after files are moved.

**Lock file:** When `nas.use-lock-file=true`, each file being processed gets a `.lock` file in `inbound`, so the same file is not picked up twice (important when ControlM and the in-app scheduler both run).

**Parallel batches:** Batch API calls run in parallel (configurable via `nas.parallel-batch-limit`, default 5) for faster processing.

**Run on startup:** When `processor.run-on-startup=true` (default), one processing cycle runs automatically when the app starts, so any files already in `nas/inbound` are picked up immediately.

---

## Response File Layout

Each processed file gets a **per-run response folder** named with the file base name plus a **timestamp** (`yyyyMMdd_HHmmss`) so multiple runs of the same file don’t overwrite each other:

```
nas/responses/
  orders_20240315_120000/              ← baseName + _yyyyMMdd_HHmmss (one folder per run)
    batch_001_of_003/                  ← per-batch subfolder
      request.json                     ← request sent to API (for cross-check)
      response.json                    ← response from API
    batch_002_of_003/
      request.json
      response.json
    ...
    orders.FINAL.json                  ← consolidated summary
```

When `nas.scan-recursive=true`, the inbound folder structure is preserved (e.g. `servicer_A/file.json` → `responses/servicer_A/file_20240315_120000/`).

Each batch has its own subfolder (`batch_NNN_of_TTT/`) containing both the request and response for easy comparison.

### Per-batch files (`batch_NNN_of_TTT/`)

- **`request.json`** — The request body sent to the API (message envelope with `defaultEventList`).
- **`response.json`** — The batch response from the API:
```json
{
  "sourceFile"      : "orders.json",
  "batchNumber"     : 1,
  "totalBatches"    : 3,
  "recordCount"     : 100,
  "status"          : "SUCCESS",
  "httpStatusCode"  : 200,
  "apiResponseBody" : "{ \"accepted\": 100 }",
  "errorMessage"    : null,
  "requestTime"     : "2024-03-15T12:00:01",
  "responseTime"    : "2024-03-15T12:00:02",
  "durationMs"      : 843
}
```

### Final response (`<name>.FINAL.json`)

Uses the same message envelope format as the API request:

```json
{
  "message": {
    "aboutVersions": {
      "aboutVersion": {
        "createdDateTime": "2026-03-05",
        "aboutVersionIdentifier": "v1.0.0",
        "requestingSystem": "nasFileProcessor"
      }
    },
    "responseData": {
      "sourceFile"          : "orders.json",
      "overallStatus"       : "SUCCESS",
      "processingStartTime" : "2024-03-15T12:00:00",
      "processingEndTime"   : "2024-03-15T12:00:05",
      "totalDurationMs"     : 5120,
      "totalRecords"        : 250,
      "totalBatches"        : 3,
      "successfulBatches"   : 3,
      "failedBatches"       : 0,
      "processedRecords"    : 250,
      "summaryMessage"      : "All 3 batches processed successfully. 250/250 records sent.",
      "batchResponses"      : [ ... ]
    }
  }
}
```

---

## Input JSON Formats

The processor supports two input formats:

### 1. Message envelope (recommended)

```json
{
  "message": {
    "aboutVersions": { "aboutVersion": { ... } },
    "requestData": {
      "defaultEventList": [
        { "loan_id": "LN-2024-10041", "servicer_id": "SVC-001", ... },
        ...
      ]
    }
  }
}
```

- Records are read from `message.requestData.defaultEventList`
- **Deduplication**: Records are deduplicated by `loan_id` (first occurrence kept).

### 2. Legacy formats

- Root array: `[ { "id": 1 }, ... ]`
- Object wrapper: `{ "data": [...] }`, `{ "records": [...] }`, or `{ "items": [...] }`

---

## Large File Support (100MB+)

For files > 1MB, the processor uses a **streaming parser** that reads records one-by-one without loading the entire file into memory:

- Supports files up to 100MB+ without OOM
- No `readTree` on the message body — element-by-element streaming
- Tested with `JsonFileProcessorServiceTest.processFile_with100MBMessageEnvelope_shouldSucceedWithoutOOM`

For very large files, consider increasing JVM heap: `-Xmx512m` or `-Xmx1g`.

---

## Malformed JSON Handling

When a file in `nas/inbound` contains invalid JSON:

1. **Parse error** → `FileReadException` thrown
2. **File moved** → `nas/inbound` → `nas/error` (timestamped filename)
3. **Error sidecar** → `filename_timestamp.json.error.txt` with reason (e.g. `Read error: Failed to parse JSON: badfile.json`)
4. **Response folder** → Error FINAL response written with error details
5. **Processing copy** → Deleted from `nas/processing/`

The processor continues with other files; the failed file is not retried.

---

## Completed / Error File Naming

- **Timestamp format:** All suffixes use `yyyyMMdd_HHmmss` (e.g. `20260309_001345` = 2026-03-09 00:13:45). No random component.
- **Response folder:** Every run creates a new folder: `{baseName}_yyyyMMdd_HHmmss`. The same file processed multiple times gets separate response folders.
- **Completed:** If the same path already exists in `completed`, the new file gets a timestamp in the filename to avoid overwriting: `loan_default_latest_1_20260309_001346.json`.
- **Error:** Error files are always written with a timestamp: `file_yyyyMMdd_HHmmss.json` and `file_yyyyMMdd_HHmmss.json.error.txt`.

---

## Archive (Disk Space Management)

To manage disk space as file sizes grow, the processor can archive old files from `completed`, `error`, and `responses` to `nas/archive/YYYY-MM/`.

**Behavior:**
- Runs daily at 2 AM (configurable via `nas.archive.cron`)
- **Recursively** scans `completed`, `error`, and `responses` (supports nested structure like `completed/servicer_A/`)
- **Preserves folder structure** when archiving (e.g. `completed/servicer_A/file.json` → `archive/YYYY-MM/completed/servicer_A/file.json`)
- Moves files older than `nas.archive.retention-days` (default: 30)
- For error folder: `.error.txt` sidecars are moved with their parent `.json` files
- For responses: per-file response folders (those containing `batch_*` or `*.FINAL.json`) are archived with structure preserved
- **Deletes empty folders** in `completed`, `error`, and `responses` after archiving (ignores `.DS_Store` and `*.lock`)

**Configuration:**
```yaml
nas.archive:
  enabled: false
  retention-days: 30
  folder: nas/archive
  cron: "0 0 2 * * *"   # daily at 2 AM
```

**Archive layout:**
```
nas/archive/
  2026-03/
    completed/
      servicer_A/
      servicer_B/
    error/
    responses/
      servicer_A/
      servicer_B/
  2026-02/
    ...
```

Disabled by default. Enable with `nas.archive.enabled=true`.

---

## Email Notification

When a processing cycle completes, the app can send a summary email to a configurable distribution list. This is **disabled by default**.

**Behavior:**
- **Enable/disable:** `nas.email.enabled=true` (default: `false`)
- **Recipients:** `nas.email.distribution` — list of email addresses (in YAML or as `nas.email.distribution[0]=...`, `nas.email.distribution[1]=...` in properties)
- **When:** After each processing cycle that handles at least one file
- **Attachments:**
  - **JSON** — Full response with same structure as `POST /api/processor/trigger`: `details` grouped by folder (e.g. `servicer_A`, `servicer_B`, `root`), plus `status`, `filesFound`, `succeeded`, `partial`, `failed`, `timestamp`. Filename: `processing-response-YYYY-MM-DD_HHmmss.json`
  - **HTML** — Same data as a styled report (summary table + per-folder tables). Filename: `processing-response-YYYY-MM-DD_HHmmss.html`
- **Subject/body:** Short summary (subject includes success/partial/failed counts; body has a small table; full data is in the attachments)

**Configuration:**
```yaml
nas.email:
  enabled: false
  distribution:
    - team@example.com
    - ops@example.com

# When nas.email.enabled=true, configure your SMTP server:
spring.mail:
  host: smtp.example.com
  port: 587
  username: ${MAIL_USERNAME:}
  password: ${MAIL_PASSWORD:}
  properties.mail.smtp.auth: true
  properties.mail.smtp.starttls.enable: true
```

**Testing email (MailHog):** Use the `email-test` profile with a local MailHog instance to capture emails without sending to real addresses:

1. Start MailHog: `docker run -d -p 1025:1025 -p 8025:8025 mailhog/mailhog`
2. Run the app: `./gradlew bootRun --args='--spring.profiles.active=email-test'`
3. Trigger processing (e.g. put a JSON in `nas/inbound` and `curl -X POST http://localhost:8080/api/processor/trigger`)
4. View captured emails at http://localhost:8025

Profile `email-test` is defined in `application-email-test.yml` (SMTP host `localhost`, port `1025`, no auth). **This file is optional:** if you remove it, the app runs as usual with email disabled by default.

---

## Trigger Response Structure

`POST /api/processor/trigger` returns a JSON body with **details grouped by folder** when using recursive structure:

```json
{
  "status": "SUCCESS",
  "filesFound": 4,
  "succeeded": 4,
  "partial": 0,
  "failed": 0,
  "timestamp": "2026-03-08T23:49:42.989737",
  "details": {
    "servicer_A": [
      { "fileName": "servicer_A/loan_default_latest_1.json", "status": "SUCCESS", "totalRecords": 1000, "processedRecords": 1000, "durationMs": 234, ... },
      { "fileName": "servicer_A/loan_default_latest_2.json", "status": "SUCCESS", ... }
    ],
    "servicer_B": [
      { "fileName": "servicer_B/loan_default_latest_1.json", "status": "SUCCESS", ... }
    ]
  }
}
```

Top-level files (no subfolder) are grouped under `"root"`.

---

## Architecture

```
ControlM (every 10 min) → POST /api/processor/trigger
         │
         ▼
FileOrchestrationService      (AtomicBoolean prevents overlapping runs)
         │
         ├─ FileManagementService      scan / move / lock files; delete empty dirs
         ├─ ResponseFileWriterService  write batch + final response JSON (structure preserved)
         ├─ EmailNotificationService   send summary + JSON/HTML attachments (when nas.email.enabled=true)
         └─ JsonFileProcessorService  parallel batch API calls (parallelBatchLimit)
                  │  Jackson Streaming API (memory-efficient, supports 100MB+)
                  ▼
             batch of 100 → ApiCallerService → @Recover on failure
                  │
                  ├─ writeBatchRequest()   → batch_NNN/request.json
                  ├─ writeBatchResponse() → batch_NNN/response.json
                  └─ writeFinalResponse() → <file>.FINAL.json
                  │
                  └─ moveToCompleted / moveToError

ArchiveScheduler (when nas.archive.enabled=true)
         │
         └─ ArchiveService  → move old files to nas/archive/YYYY-MM/
```

---

## ControlM Integration

### File processing (every 10 min)

```bash
curl -X POST http://<host>:8080/api/processor/trigger --fail-with-body
```

| HTTP | Meaning |
|------|---------|
| 200 | All files OK |
| 207 | Some batch failures (check FINAL response files) |
| 409 | Already running — skip |
| 5xx | Alert |

**Check status:** `GET http://<host>:8080/api/processor/status` returns `{ "running": true|false, "timestamp": "..." }`. **Health:** `GET http://<host>:8080/api/processor/health` returns `{ "status": "UP", "service": "nas-file-processor" }`.

### Archive (daily, when `nas.archive.enabled=true`)

ControlM can trigger the archive job on a schedule (e.g. daily after 2 AM) to move old files from `completed`, `error`, and `responses` to `nas/archive/YYYY-MM/`:

```bash
curl -X POST http://<host>:8080/api/processor/archive --fail-with-body
```

| HTTP | Meaning |
|------|---------|
| 200 | Archive ran successfully (body includes `completedCount`, `errorCount`, `responsesCount`) |
| 500 | Archive ran but failed (check `errorMessage` in body) |
| 503 | Archive is disabled (`nas.archive.enabled=false`) |

**Options:**

- **ControlM-driven:** Schedule a ControlM job to call `POST /api/processor/archive` daily (e.g. 2:30 AM). Disable the in-app schedule by setting `nas.archive.cron` to a value that never runs (e.g. `0 0 31 2 *` for “never”), or leave the default and let the app run archive at 2 AM in addition.
- **App-driven:** Rely on the built-in cron (`nas.archive.cron=0 0 2 * * *`). No ControlM job needed for archive.

---

## Building & Running

**JAR (production):**
```bash
./gradlew clean bootJar
export API_AUTH_TOKEN=your-bearer-token
java -jar build/libs/nas-file-processor-1.0.0.jar
```

**Gradle (development):**
```bash
./gradlew bootRun
# Or with a profile, e.g. email-test:
./gradlew bootRun --args='--spring.profiles.active=email-test'
```

With default settings, the app runs one processing cycle on startup (`processor.run-on-startup=true`), then listens for `POST /api/processor/trigger` (e.g. from ControlM or a 10‑minute in-app schedule).

---

## Configuration

Configuration can be in **`application.yml`** or **`application.properties`**; the same keys are used (e.g. `nas.scan-recursive=true` in properties, or `nas.scan-recursive: true` in YAML).

```yaml
nas:
  folders:
    inbound:    nas/inbound
    processing: nas/processing
    error:      nas/error
    completed:  nas/completed
  batch-size: 100
  parallel-batch-limit: 5    # max concurrent API calls per file
  file-extension: .json
  scan-recursive: true       # scan subfolders, preserve structure
  use-lock-file: true

nas.folders.responses: nas/responses

# archive: move old files, preserve structure, delete empty folders (disabled by default)
nas.archive:
  enabled: false
  retention-days: 30
  folder: nas/archive
  cron: "0 0 2 * * *"

# email: send summary + JSON/HTML attachments after each cycle (disabled by default)
nas.email:
  enabled: false
  distribution:
    - team@example.com
    - ops@example.com

# SMTP when nas.email.enabled=true
spring.mail:
  host: smtp.example.com
  port: 587
  username: ${MAIL_USERNAME:}
  password: ${MAIL_PASSWORD:}
  properties.mail.smtp.auth: true
  properties.mail.smtp.starttls.enable: true

api:
  # In local/dev we usually point to a process API running on localhost.
  # In higher environments override via API_BASE_URL or profile-specific YAML.
  base-url:        http://localhost:8081
  endpoint:        /api/v1/process
  about-version-identifier: v1.0.0
  requesting-system: nasFileProcessor
  timeout-seconds: 30
  max-retries:     3
  retry-delay-ms:  2000
  auth-token:      ${API_AUTH_TOKEN:your-token-here}
```

**Other useful options:** `processor.run-on-startup: true` (run one cycle on startup); `scheduler.enabled: true`, `scheduler.cron: "0 */10 * * * *"` (in-app trigger every 10 min when not using ControlM).

> **Note:** `ApiConfig` validates that `api.base-url` is set and is **not**
> the placeholder `your-api-endpoint.com`. If that placeholder is present,
> the application will fail fast on startup with a clear error message.

---

## Downstream Process API Contract

The NAS processor sends requests to the downstream process API at `api.base-url + api.endpoint`
using a **message envelope** format:

```json
{
  "message": {
    "aboutVersions": {
      "aboutVersion": {
        "createdDateTime": "2026-03-05",
        "aboutVersionIdentifier": "v1.0.0",
        "requestingSystem": "nasFileProcessor"
      }
    },
    "requestData": {
      "defaultEventList": [
        {
          "loan_id": "LN-2024-10041",
          "servicer_id": "SVC-00187",
          "reported_date": "2026-03-05",
          "bankruptcy": { "...": "..." },
          "foreclosure": { "...": "..." },
          "events": [ { "event_id": "EVT-001", "event_code": "DLQ-30", "...": "..." } ]
        }
      ]
    }
  }
}
```

Per-batch, `ApiCallerService` sends:

- HTTP `POST` to `api.endpoint` (e.g. `/api/v1/process`)
- `Content-Type: application/json`
- `Authorization: Bearer ${API_AUTH_TOKEN}` (if configured)
- Body: the **message envelope** with `message.requestData.defaultEventList` containing the batch slice of records.

The downstream API is expected to return `200 OK` and a body that is persisted
verbatim into `BatchApiResponse.apiResponseBody` (and thus into the batch
and final response JSON files).

---

## Testing & Coverage

- Unit tests are run with:

  ```bash
  ./gradlew test
  ```

- Code coverage is generated via JaCoCo:

  ```bash
  ./gradlew test jacocoTestReport
  open build/reports/jacoco/test/html/index.html
  ```

- The test suite exercises the core flow end‑to‑end at the **service layer**,
  including:
  - `JsonFileProcessorService` (happy path, malformed JSON, wrapped `{ "data": [...] }`,
    message envelope with deduplication, **100MB message envelope**)
  - `FileManagementService` (inbound scanning, recursive structure, processing/completed/error moves, lock files, empty-folder cleanup)
  - `ResponseFileWriterService` (batch + FINAL responses, success / partial / failure, structure preserved)
  - `ApiCallerService` against a `MockWebServer` downstream API
  - `FileOrchestrationService`, `ProcessorController`, `FileProcessingScheduler`,
    and `ProcessOnStartupRunner`
  - `ArchiveService` (recursive archive, structure preserved, empty-folder cleanup)
  - `EmailNotificationService` (summary email and JSON/HTML attachments when enabled)

- **Automation tests** (no external services required):
  - **E2E** (`E2eAutomationTest`): full Spring context, temp NAS dirs, MockWebServer as the process API; verifies `POST /api/processor/trigger` → file processed → moved to completed.
  - **Contract** (Groovy/Spock): `ProcessorApiContractSpec` and `ProcessorApiContractArchiveDisabledSpec` verify all REST API contracts (health, status, trigger, archive).
- **Spring Cloud Contract** (`Contract.make` Groovy DSL): contracts in `src/contractTest/resources/contracts/processor/` define request/response for all APIs. Generated verifier tests run with `./gradlew contractTest`. WireMock **stub/mock files** are generated under `build/stubs/` (e.g. `build/stubs/META-INF/.../mappings/processor/*.json`) for consumer-side stub runs.
  - **Cucumber BDD**: feature files in `src/test/resources/features/`, step definitions and runner in `src/test/java/.../cucumber/`. Run with `./gradlew test --tests "com.example.nasprocessor.cucumber.CucumberRunner"`. Reports: `build/reports/cucumber/`.
- Run everything (clean + test): `./scripts/run-automation-tests.sh`, or `./gradlew test`. Run contract verifier + stubs: `./gradlew generateContractTests generateClientStubs contractTest`.

- **CI**: GitHub Actions (`.github/workflows/ci.yml`) runs `./gradlew test` on push/PR to `main`/`master`.

Current overall line coverage is **≥ 80%** (see the JaCoCo report for exact numbers).

---

## Helper Scripts

From the project root:

- **Restart NAS file processor** (port 8080, pointing at `http://localhost:8081`):

  ```bash
  ./scripts/restart-both.sh
  ```

  - Stops any processes on ports `8080` and `8081`.
  - Starts the NAS file processor on `8080` with `api.base-url=http://localhost:8081`.
  - You are responsible for starting the downstream process API on port `8081`.

- **Local end‑to‑end test** (small sample file + downstream API on `8081`):

  ```bash
  ./scripts/run-e2e.sh
  ```

  - Assumes a process API is running at `http://localhost:8081/api/v1/process`.
  - Uses `nas/inbound/e2e_test.json` (or restores it from a previous error run)
    and verifies that:
    - the file moves to `nas/completed/`, and
    - a successful FINAL response is written under `nas/responses/`.

- **Generate 100MB test file** (for large-file testing):

  ```bash
  python3 scripts/generate_100mb_test_file.py
  ```

  - Creates `nas/inbound/large_100mb_test.json` (~98 MB, 50,000 records).
  - Uses message envelope format; suitable for testing via Postman trigger.

---

## Contributing

1. Create a feature branch from `master`: `git checkout -b feature/your-change`.
2. Make your changes and run tests: `./gradlew test`.
3. Commit and push the branch, then open a pull request against `master`.
4. Ensure CI passes; address any review feedback before merge.

**Code review agent:** Each PR runs the [code-review-agent](https://github.com/ySabore/code-review-agent) (see workflow `.github/workflows/code-review.yml`). It reports style/consistency issues in the Actions log. Config: `.code-review.yaml` (set `output.fail_on_issues: true` to make the check block the PR).
