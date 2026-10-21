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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

// GPX R51 — covers the one thing that needs no GL context: the second camera's rotation is
// remembered by MainRender whether or not the second source exists yet, so initSecondarySource can
// apply it to the render it builds (the second texture is fetched late). Bringing the second source
// up, applying the remembered value to it and the picture itself need a live EGL context and cannot
// run on the plain JVM; those are device-checked.
class MainRenderSecondaryRotationTest {

  private val mainRender = MainRender()

  @Test
  fun `the second camera starts unturned`() {
    assertEquals(0, mainRender.secondaryRotation)
  }

  @Test
  fun `a rotation set before the second source exists is remembered`() {
    assertFalse(mainRender.hasSecondarySource())
    mainRender.setSecondaryRotation(270)
    assertEquals(270, mainRender.secondaryRotation)
    assertFalse(mainRender.hasSecondarySource())
  }

  @Test
  fun `the last rotation set wins`() {
    mainRender.setSecondaryRotation(270)
    mainRender.setSecondaryRotation(90)
    assertEquals(90, mainRender.secondaryRotation)
  }

  @Test
  fun `a rotation of the second camera does not touch the first camera's`() {
    mainRender.setCameraRotation(180)
    mainRender.setSecondaryRotation(90)
    assertEquals(90, mainRender.secondaryRotation)
    mainRender.setCameraRotation(0)
    assertEquals(90, mainRender.secondaryRotation)
  }

  @Test
  fun `hostile angles are remembered as given and never throw`() {
    for (angle in listOf(-90, 0, 360, 450, Int.MAX_VALUE, Int.MIN_VALUE)) {
      mainRender.setSecondaryRotation(angle)
      assertEquals(angle, mainRender.secondaryRotation)
    }
  }
}
