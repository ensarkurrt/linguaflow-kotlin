package dev.linguaflow

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ConfigTest {
  @Test fun `rejects unsafe branch key and overlay`() {
    assertThrows(IllegalArgumentException::class.java) { LinguaFlowConfig("secret") }
    assertThrows(IllegalArgumentException::class.java) { LinguaFlowConfig("br_live_ok", overlay = "../tenant") }
  }
}
