#!/usr/bin/env python3
"""Generate a ~100MB message envelope JSON file for testing. Writes to nas/inbound/."""
import json
import os

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
OUTPUT_PATH = os.path.join(SCRIPT_DIR, "..", "nas", "inbound", "large_100mb_test.json")
RECORD_COUNT = 50_000
PADDING = "x" * 2000  # ~2KB per record

def main():
    inbound_dir = os.path.dirname(OUTPUT_PATH)
    os.makedirs(inbound_dir, exist_ok=True)

    with open(OUTPUT_PATH, "w") as f:
        f.write('{"message":{"aboutVersions":{"aboutVersion":{"createdDateTime":"2026-02-05","aboutVersionIdentifier":"v1.0.0","requestingSystem":"nasFileProcessor"}},"requestData":{"defaultEventList":[')
        for i in range(RECORD_COUNT):
            if i > 0:
                f.write(",")
            rec = {"loan_id": f"LN-{i}", "servicer_id": "SVC-001", "data": PADDING}
            f.write(json.dumps(rec))
        f.write("]}}}")

    size_mb = os.path.getsize(OUTPUT_PATH) / (1024 * 1024)
    print(f"Created {OUTPUT_PATH} ({size_mb:.1f} MB, {RECORD_COUNT} records)")

if __name__ == "__main__":
    main()
