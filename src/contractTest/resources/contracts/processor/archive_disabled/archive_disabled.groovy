import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description("POST /api/processor/archive when disabled returns 503 DISABLED")
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
        status 503
        headers {
            contentType(applicationJson())
        }
        body([
            status: "DISABLED",
            message: $(producer(regex('.*')), consumer("Archive is disabled")),
            timestamp: $(producer(regex('.*')), consumer("2024-01-01T00:00:00"))
        ])
        bodyMatchers {
            jsonPath('$.timestamp', byRegex('.*'))
        }
    }
}
