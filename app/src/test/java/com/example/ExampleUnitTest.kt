package com.example

import org.junit.Assert.*
import org.junit.Test

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
    val defaultService = com.example.data.remote.CloudinaryMediaService()
    assertTrue(defaultService.isConfigured())
    assertEquals("f06cifgw", defaultService.cloudName)
    assertEquals("ml_default", defaultService.uploadPreset)
  }
}
