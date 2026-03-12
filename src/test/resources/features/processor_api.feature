# language: en
Feature: Processor REST API
  As a ControlM job or operator
  I want to trigger and monitor the NAS file processor via REST
  So that I can run processing cycles and check health and status

  Scenario: Health check returns UP
    When I request GET "/api/processor/health"
    Then the response status is 200
    And the response body contains "status" with value "UP"
    And the response body contains "service" with value "nas-file-processor"

  Scenario: Status returns running flag when processor is idle
    Given the processor is idle
    When I request GET "/api/processor/status"
    Then the response status is 200
    And the response body contains "running" with value "false"

  Scenario: Status returns running flag when processor is busy
    Given the processor is busy
    When I request GET "/api/processor/status"
    Then the response status is 200
    And the response body contains "running" with value "true"

  Scenario: Trigger when idle returns SUCCESS
    Given the processor is idle
    And the processing cycle will return no files
    When I request POST "/api/processor/trigger"
    Then the response status is 200
    And the response body contains "status" with value "SUCCESS"
    And the response body contains "filesFound" with value "0"

  Scenario: Trigger when already running returns 409 SKIPPED
    Given the processor is busy
    When I request POST "/api/processor/trigger"
    Then the response status is 409
    And the response body contains "status" with value "SKIPPED"
    And the response body contains "message"

  Scenario: Trigger when cycle has failures returns 207
    Given the processor is idle
    And the processing cycle will return one failed file
    When I request POST "/api/processor/trigger"
    Then the response status is 207
    And the response body contains "status" with value "COMPLETED_WITH_ERRORS"
    And the response body contains "failed" with value "1"

  Scenario: Archive when enabled returns SUCCESS
    Given the archive service is enabled
    When I request POST "/api/processor/archive"
    Then the response status is 200
    And the response body contains "status" with value "SUCCESS"

  # Archive when disabled (503) is covered by contract test ArchiveDisabledBase - requires no ArchiveService bean
