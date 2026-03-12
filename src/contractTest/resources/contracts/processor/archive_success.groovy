import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description("POST /api/processor/archive when enabled returns 200 SUCCESS")
    request {
        method POST()
        urlPath("/api/processor/archive")
        headers {
            contentType(applicationJson())
            accept(applicationJson())
        }
        body('{}')
    }
    response {
        status OK()
        headers {
            contentType(applicationJson())
        }
        body([
            status: "SUCCESS",
            ran: $(producer(regex('true|false')), consumer(true)),
            completedCount: $(producer(regex('[0-9]+')), consumer(0)),
            errorCount: $(producer(regex('[0-9]+')), consumer(0)),
            responsesCount: $(producer(regex('[0-9]+')), consumer(0)),
            timestamp: $(producer(regex('.*')), consumer("2024-01-01T00:00:00"))
        ])
        bodyMatchers {
            jsonPath('$.timestamp', byRegex('.*'))
        }
    }
}
