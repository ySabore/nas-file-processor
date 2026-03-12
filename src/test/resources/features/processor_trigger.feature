# language: en
Feature: Trigger processing cycle
  In order to process inbound files
  ControlM calls the trigger endpoint
  And receives a summary of processed files or 409 if already running

  Scenario: Trigger when idle returns 200 SUCCESS
    Given the processor is idle
    And the processing cycle will return no files
    When I request POST "/api/processor/trigger"
    Then the response status is 200
    And the response body contains "status" with value "SUCCESS"

  Scenario: Trigger when busy returns 409 SKIPPED
    Given the processor is busy
    When I request POST "/api/processor/trigger"
    Then the response status is 409
    And the response body contains "status" with value "SKIPPED"
    And the response body contains "message"
