/*
 * Copyright (C) 2019 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jayo.http.internal

import jayo.Buffer
import jayo.buffered
import jayo.http.asRequestBody
import jayo.http.toMediaType
import jayo.writer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createFile

class ClientRequestBodyTest {
    private lateinit var filePath: Path

    @BeforeEach
    fun setup(@TempDir tempDir: Path) {
        filePath = tempDir.resolve("file.txt")
        filePath.createFile()
    }

    @Test
    fun testPath() {
        assertOnPath { path ->
            val requestBody = path.asRequestBody()

            assertThat(requestBody.contentByteSize()).isEqualTo(0L)
        }
    }

    @Test
    fun testPathRead() {
        assertOnPath(content = "Hello") { path ->
            val requestBody = path.asRequestBody()

            assertThat(requestBody.contentByteSize()).isEqualTo(5L)

            val buffer = Buffer()
            requestBody.writeTo(buffer)
            assertThat(buffer.readUtf8String()).isEqualTo("Hello")
        }
    }

    @Test
    fun testPathDefaultMediaType() {
        assertOnPath { path ->
            val requestBody = path.asRequestBody()

            assertThat(requestBody.contentType()).isNull()
        }
    }

    @Test
    fun testPathMediaType() {
        assertOnPath { path ->
            val contentType = "text/plain".toMediaType()

            val requestBody = path.asRequestBody(contentType)

            assertThat(requestBody.contentType()).isEqualTo(contentType)
        }
    }

    private inline fun <T> assertOnPath(
        content: String? = null,
        fn: (Path) -> T,
    ): T {
        filePath.writer().buffered().use { writer ->
            if (content != null) {
                writer.writeUtf8(content)
            }
        }

        return fn(filePath)
    }
}
