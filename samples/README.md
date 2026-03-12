# Sample API Request Bodies (Per Batch)

These files show the exact JSON format sent to the downstream process API for each batch.

| File | Description |
|------|-------------|
| `batch_001_of_001.request.json` | Single-batch file (e.g. 2 records) — one API call |
| `batch_001_of_003.request.json` | Batch 1 of 3 — first 100 records (sample shows 2) |
| `batch_002_of_003.request.json` | Batch 2 of 3 — records 101–200 (sample shows 2) |
| `batch_003_of_003.request.json` | Batch 3 of 3 — remaining records (sample shows 1) |

All requests use the **message envelope** format:

- `message.aboutVersions.aboutVersion` — version metadata
- `message.requestData.defaultEventList` — array of loan/event records for this batch

With default batch size 100, a 250-record file produces 3 batches; the last batch may have fewer than 100 records.
