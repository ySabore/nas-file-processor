import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description("POST /api/processor/trigger when not running returns 200 and SUCCESS")
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
        status OK()
        headers {
            contentType(applicationJson())
        }
        body([
            status: "SUCCESS",
            filesFound: $(producer(regex('[0-9]+')), consumer(0)),
            succeeded: $(producer(regex('[0-9]+')), consumer(0)),
            partial: $(producer(regex('[0-9]+')), consumer(0)),
            failed: $(producer(regex('[0-9]+')), consumer(0)),
            timestamp: $(producer(regex('.*')), consumer("2024-01-01T00:00:00"))
        ])
        bodyMatchers {
            jsonPath('$.timestamp', byRegex('.*'))
        }
    }
}
