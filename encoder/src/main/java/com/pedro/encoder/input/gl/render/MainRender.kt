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

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Build
import android.view.Surface
import androidx.annotation.RequiresApi
import com.pedro.encoder.input.gl.FilterAction
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import com.pedro.encoder.utils.ViewPort
import com.pedro.encoder.utils.gl.AspectRatioMode
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Created by pedro on 20/3/22.
 */
@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
class MainRender {
  private val cameraRender = CameraRender()
  private val screenRender = ScreenRender()
  // GPX R41 — the record/stream route's own independent second camera input (gpxstream-app issue
  // #272, Decision 4). Null until a caller actually asks for it via initSecondarySource, so a
  // consumer that never touches this allocates no extra texture, FBO or draw call.
  private var secondaryCameraRender: CameraRender? = null
  private var width = 0
  private var height = 0
  private var previewWidth = 0
  private var previewHeight = 0
  private var context: Context? = null
  private var filterRenders = mutableListOf<BaseFilterRender>()
  private val running = AtomicBoolean(false)
  // GPX R41 — the texture id the filter chain last produced, tracked so
  // drawScreenEncoderFromSecondary can restore screenRender's texId after borrowing it. Without
  // this, a record-target draw pointed at the secondary source would leave screenRender still
  // pointed at the secondary texture for whatever the same frame draws next (photo, if requested) —
  // reOrderFilters below is the only other writer, and it always runs before any encoder-target draw
  // that needs the primary/filtered texture, so this is never stale when read.
  private var lastTexId: Int = -1

  fun initGl(context: Context, encoderWidth: Int, encoderHeight: Int, previewWidth: Int, previewHeight: Int) {
    this.context = context
    width = encoderWidth
    height = encoderHeight
    this.previewWidth = previewWidth
    this.previewHeight = previewHeight
    cameraRender.initGl(width, height, context, previewWidth, previewHeight)
    screenRender.setStreamSize(encoderWidth, encoderHeight)
    screenRender.setTexId(cameraRender.texId)
    screenRender.initGl(context)
    running.set(true)
  }

  fun isReady(): Boolean = running.get()

  fun drawSource() {
    cameraRender.draw()
  }

  fun drawFilters(isPreview: Boolean) {
    val validFilters = filterRenders.filter {
      if (isPreview) it.renderMode != RenderMode.OUTPUT else it.renderMode != RenderMode.PREVIEW
    }
    reOrderFilters(validFilters)
    validFilters.forEach { it.draw() }
  }

  fun drawScreen(
    width: Int, height: Int, mode: AspectRatioMode, rotation: Int,
    flipStreamVertical: Boolean, flipStreamHorizontal: Boolean, viewPort: ViewPort?
  ) {
    screenRender.draw(width, height, mode, rotation, flipStreamVertical,
      flipStreamHorizontal, viewPort)
  }

  fun drawScreenEncoder(
    width: Int, height: Int, isPortrait: Boolean, rotation: Int,
    flipStreamVertical: Boolean, flipStreamHorizontal: Boolean, viewPort: ViewPort?
  ) {
    screenRender.drawEncoder(width, height, isPortrait, rotation, flipStreamVertical,
      flipStreamHorizontal, viewPort)
  }

  /**
   * GPX R41 — draws the second camera source's own (unfiltered) texture into whatever surface is
   * currently current, instead of the primary/filtered one drawScreenEncoder above samples.
   * Restores screenRender's texId to [lastTexId] before returning, so a later same-frame draw call
   * that relies on the filter chain's output (photo) is unaffected by this having run.
   *
   * @return false if the secondary source was never initialized (nothing to draw from) — the
   * caller falls back to [drawScreenEncoder] in that case.
   */
  fun drawScreenEncoderFromSecondary(
    width: Int, height: Int, isPortrait: Boolean, rotation: Int,
    flipStreamVertical: Boolean, flipStreamHorizontal: Boolean, viewPort: ViewPort?
  ): Boolean {
    val secondary = secondaryCameraRender ?: return false
    screenRender.setTexId(secondary.texId)
    screenRender.drawEncoder(width, height, isPortrait, rotation, flipStreamVertical,
      flipStreamHorizontal, viewPort)
    screenRender.setTexId(lastTexId)
    return true
  }

  fun drawScreenPreview(
    width: Int, height: Int, isPortrait: Boolean,
    mode: AspectRatioMode, rotation: Int, flipStreamVertical: Boolean, flipStreamHorizontal: Boolean,
    viewPort: ViewPort?
  ) {
    screenRender.drawPreview(width, height, isPortrait, mode, rotation, flipStreamVertical, flipStreamHorizontal, viewPort)
  }

  fun release() {
    running.set(false)
    cameraRender.release()
    // GPX R41 — the second source, if one was ever brought up.
    secondaryCameraRender?.release()
    secondaryCameraRender = null
    for (baseFilterRender in filterRenders) baseFilterRender.release()
    filterRenders.clear()
    screenRender.release()
  }

  private fun setFilter(position: Int, baseFilterRender: BaseFilterRender) {
    val id = filterRenders[position].previousTexId
    val renderHandler = filterRenders[position].renderHandler
    filterRenders[position].release()
    filterRenders[position] = baseFilterRender
    filterRenders[position].previousTexId = id
    filterRenders[position].initGl(width, height, context, previewWidth, previewHeight)
    filterRenders[position].renderHandler = renderHandler
  }

  private fun addFilter(baseFilterRender: BaseFilterRender) {
    filterRenders.add(baseFilterRender)
    baseFilterRender.initGl(width, height, context, previewWidth, previewHeight)
    baseFilterRender.initFBOLink()
  }

  private fun addFilter(position: Int, baseFilterRender: BaseFilterRender) {
    filterRenders.add(position, baseFilterRender)
    baseFilterRender.initGl(width, height, context, previewWidth, previewHeight)
    baseFilterRender.initFBOLink()
  }

  private fun clearFilters() {
    for (baseFilterRender in filterRenders) {
      baseFilterRender.release()
    }
    filterRenders.clear()
  }

  private fun removeFilter(position: Int) {
    filterRenders.removeAt(position).release()
  }

  private fun removeFilter(baseFilterRender: BaseFilterRender) {
    baseFilterRender.release()
    filterRenders.remove(baseFilterRender)
  }

  private fun reOrderFilters(filters: List<BaseFilterRender>) {
    for (i in filters.indices) {
      val texId = if (i == 0) cameraRender.texId else filters[i - 1].texId
      filters[i].previousTexId = texId
    }
    val texId = if (filters.isEmpty()) cameraRender.texId else filters[filters.size - 1].texId
    screenRender.setTexId(texId)
    lastTexId = texId
  }

  fun setFilterAction(filterAction: FilterAction, position: Int, baseFilterRender: BaseFilterRender) {
    when (filterAction) {
      FilterAction.SET -> if (filterRenders.size > 0) {
        setFilter(position, baseFilterRender)
      } else {
        addFilter(baseFilterRender)
      }
      FilterAction.SET_INDEX -> setFilter(position, baseFilterRender)
      FilterAction.ADD -> addFilter(baseFilterRender)
      FilterAction.ADD_INDEX -> addFilter(position, baseFilterRender)
      FilterAction.CLEAR -> clearFilters()
      FilterAction.REMOVE -> removeFilter(baseFilterRender)
      FilterAction.REMOVE_INDEX -> removeFilter(position)
    }
  }

  fun filtersCount(): Int {
    return filterRenders.size
  }

  fun setPreviewSize(previewWidth: Int, previewHeight: Int) {
    for (i in filterRenders.indices) {
      filterRenders[i].setPreviewSize(previewWidth, previewHeight)
    }
  }

  fun updateFrame() {
    cameraRender.updateTexImage()
  }

  fun getSurfaceTexture(): SurfaceTexture {
    return cameraRender.surfaceTexture
  }

  fun getSurface(): Surface {
    return cameraRender.surface
  }

  /**
   * GPX R41 — whether the second camera source has been brought up. Callers use this to decide
   * whether there is anything to update/draw from it this frame, and a target's own source
   * selector falls back to the primary source when this is false (the second source was never
   * actually attached, even though a target asked for it).
   */
  fun hasSecondarySource(): Boolean = secondaryCameraRender != null

  /**
   * GPX R41 — brings up the second camera input's GL resources (its own external-OES texture,
   * SurfaceTexture and FBO) if they do not exist yet. Must run on the thread already holding the
   * current EGL context — the same requirement [initGl] itself has. Idempotent: a second call is a
   * no-op, matching [getSurfaceTexture]'s "call after start render" contract for the primary one.
   */
  fun initSecondarySource(context: Context) {
    if (secondaryCameraRender != null) return
    val render = CameraRender()
    render.initGl(width, height, context, previewWidth, previewHeight)
    secondaryCameraRender = render
  }

  /** GPX R41 — [getSurfaceTexture] counterpart for the second source. Null until [initSecondarySource] runs. */
  fun getSecondarySurfaceTexture(): SurfaceTexture? = secondaryCameraRender?.surfaceTexture

  /** GPX R41 — [getSurface] counterpart for the second source. Null until [initSecondarySource] runs. */
  fun getSecondarySurface(): Surface? = secondaryCameraRender?.surface

  /** GPX R41 — [updateFrame] counterpart for the second source. No-op if never initialized. */
  fun updateSecondarySource() {
    secondaryCameraRender?.updateTexImage()
  }

  /** GPX R41 — [drawSource] counterpart for the second source. No-op if never initialized. */
  fun drawSecondarySource() {
    secondaryCameraRender?.draw()
  }

  fun setCameraRotation(rotation: Int) {
    cameraRender.setRotation(rotation)
  }

  fun setCameraFlip(isFlipHorizontal: Boolean, isFlipVertical: Boolean) {
    cameraRender.setFlip(isFlipHorizontal, isFlipVertical)
  }
}
