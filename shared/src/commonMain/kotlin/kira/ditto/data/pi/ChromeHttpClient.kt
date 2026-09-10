package kira.ditto.data.pi

import io.ktor.client.HttpClient

internal expect fun createChromeHttpClient(): HttpClient
