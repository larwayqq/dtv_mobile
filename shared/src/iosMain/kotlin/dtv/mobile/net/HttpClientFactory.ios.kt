package dtv.mobile.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders
import io.ktor.http.headers
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

actual fun createHttpClient(): HttpClient {
  return HttpClient(Darwin) {
    install(HttpTimeout) {
      requestTimeoutMillis = 15_000
      connectTimeoutMillis = 10_000
      socketTimeoutMillis = 15_000
    }
    install(ContentNegotiation) {
      json(
        Json {
          ignoreUnknownKeys = true
          isLenient = true
        },
      )
    }
    defaultRequest {
      headers {
        append(HttpHeaders.AcceptLanguage, "zh-CN,zh;q=0.9")
        append(
          HttpHeaders.UserAgent,
          "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1",
        )
      }
    }
  }
}
