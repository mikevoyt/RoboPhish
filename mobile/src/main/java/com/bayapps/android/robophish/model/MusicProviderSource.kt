/*
 * Copyright (C) 2014 The Android Open Source Project
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
package com.bayapps.android.robophish.model

import robophish.model.YearData

interface MusicProviderSource {
    suspend fun years(): List<YearData>
    suspend fun showsInYear(year: String): List<ShowData>
    suspend fun tracksInShow(showId: String): List<TrackData>
}

data class ShowData(
    val id: String,
    val date: String,
    val venue: String,
    val location: String,
    val taperNotes: String?
)

data class TrackData(
    val id: String,
    val title: String,
    val durationMs: Long,
    val artist: String,
    val album: String,
    val showId: String,
    val showDate: String,
    val artUrl: String,
    val sourceUrl: String,
    val taperNotes: String?,
    val set: String?,
    val setName: String?
)
