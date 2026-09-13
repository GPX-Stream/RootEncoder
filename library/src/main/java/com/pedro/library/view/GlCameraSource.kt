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
package com.pedro.library.view

/**
 * GPX R41 — which of a [GlInterface]'s two open camera sources a render target (stream or record)
 * draws its base picture from. See [GlInterface.setStreamSource] / [GlInterface.setRecordSource].
 *
 * [PRIMARY] is the camera every consumer has always had — [GlInterface.getSurfaceTexture] /
 * [GlInterface.getSurface]. [SECONDARY] is the second, independent capture this fork change adds —
 * [GlInterface.getSecondarySurfaceTexture] / [GlInterface.getSecondarySurface] — and stays fully
 * inert (no GL resources allocated) until a caller actually asks for it, so a consumer that never
 * touches this stays byte-for-byte on today's single-camera behavior.
 */
enum class GlCameraSource { PRIMARY, SECONDARY }
