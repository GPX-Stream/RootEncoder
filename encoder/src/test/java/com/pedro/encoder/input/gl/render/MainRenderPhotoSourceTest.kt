/*
 * Copyright (C) 2024 pedroSG94.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pedro.encoder.input.gl.render

import com.pedro.encoder.utils.gl.AspectRatioMode
import org.junit.Assert.assertFalse
import org.junit.Test

// GPX R50 — covers the one decision that needs no GL context: a photo draw from the second camera
// answers false, touching nothing, when that camera was never brought up, so the caller falls back
// to the first camera's draw. Bringing the second source up (initSecondarySource) and the draw
// itself need a live EGL context and cannot run on the plain JVM; those are device-checked.
class MainRenderPhotoSourceTest {

  private val mainRender = MainRender()

  @Test
  fun `a photo draw from the second camera answers false when it was never brought up`() {
    assertFalse(mainRender.hasSecondarySource())
    assertFalse(
      mainRender.drawScreenFromSecondary(
        320, 180, AspectRatioMode.NONE, 0, false, false, null
      )
    )
  }

  @Test
  fun `the answer is false for hostile sizes and flags too`() {
    // Zero, negative and huge sizes with every flip and rotation must not reach the GL calls
    // when there is no second source to draw from.
    val sizes = listOf(0, -1, Int.MAX_VALUE)
    for (w in sizes) {
      for (h in sizes) {
        for (flip in listOf(false, true)) {
          assertFalse(
            mainRender.drawScreenFromSecondary(
              w, h, AspectRatioMode.Adjust, 270, flip, !flip, null
            )
          )
        }
      }
    }
  }
}
