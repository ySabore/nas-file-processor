import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description("POST /api/processor/trigger when cycle has failures returns 207 COMPLETED_WITH_ERRORS")
    request {
        method POST()
        urlPath("/api/processor/trigger")
        headers {
            contentType(applicationJson())
            accept(applicationJson())
        }
        body('{}')
    }
    response {
        status 207
        headers {
            contentType(applicationJson())
        }
        body([
            status: "COMPLETED_WITH_ERRORS",
            filesFound: $(producer(regex('[0-9]+')), consumer(1)),
            succeeded: $(producer(regex('[0-9]+')), consumer(0)),
            partial: $(producer(regex('[0-9]+')), consumer(0)),
            failed: $(producer(regex('[0-9]+')), consumer(1)),
            timestamp: $(producer(regex('.*')), consumer("2024-01-01T00:00:00"))
        ])
        bodyMatchers {
            jsonPath('$.timestamp', byRegex('.*'))
        }
    }
}
