import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description("GET /api/processor/status returns 200 with running and timestamp")
    request {
        method GET()
        urlPath("/api/processor/status")
        headers {
            accept(applicationJson())
        }
    }
    response {
        status OK()
        headers {
            contentType(applicationJson())
        }
        body([
            running: $(producer(regex('true|false')), consumer(true)),
            timestamp: $(producer(regex('.*')), consumer("2024-01-01T00:00:00"))
        ])
        bodyMatchers {
            jsonPath('$.timestamp', byRegex('.*'))
        }
    }
}
