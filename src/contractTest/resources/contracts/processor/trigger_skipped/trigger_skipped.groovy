import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description("POST /api/processor/trigger when already running returns 409 SKIPPED")
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
        status 409
        headers {
            contentType(applicationJson())
        }
        body([
            status: "SKIPPED",
            message: $(producer(regex('.*')), consumer("Processing cycle already in progress")),
            timestamp: $(producer(regex('.*')), consumer("2024-01-01T00:00:00"))
        ])
        bodyMatchers {
            jsonPath('$.timestamp', byRegex('.*'))
        }
    }
}
