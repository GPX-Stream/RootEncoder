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
package com.pedro.encoder.input.video

import com.pedro.encoder.input.video.CameraHelper.Facing

interface CameraCallbacks {
  fun onCameraChanged(facing: Facing)
  // GPX R38 — cameraId identifies which camera the callback is about: the manager's own
  // cameraId field is reassigned as soon as a new open attempt starts, with no generation
  // gate on that assignment, so a consumer reading it after the fact (there was no other way
  // to know) could see a newer attempt's id than the one this callback is actually for. See
  // gpxstream-app issue #253.
  fun onCameraError(cameraId: String, error: String)
  fun onCameraOpened(cameraId: String)
  fun onCameraDisconnected(cameraId: String)
}