import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description("GET /api/processor/health returns 200 and UP status")
    request {
        method GET()
        urlPath("/api/processor/health")
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
            status: "UP",
            service: "nas-file-processor",
            timestamp: $(producer(regex('.*')), consumer("2024-01-01T00:00:00"))
        ])
        bodyMatchers {
            jsonPath('$.timestamp', byRegex('.*'))
        }
    }
}
