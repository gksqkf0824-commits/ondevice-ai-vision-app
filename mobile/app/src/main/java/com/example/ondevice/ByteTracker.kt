package com.example.ondevice

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * Simplified ByteTrack for mobile on-device detection.
 *
 * Two-stage association:
 *   Stage 1 – active tracks   ↔ high-score detections  (IoU ≥ matchIouThresh)
 *   Stage 2 – remaining tracks ↔ low-score detections  (IoU ≥ secondMatchIouThresh)
 *   Stage 3 – lost tracks      ↔ leftover high detections (re-ID)
 *
 * EMA smoothing on bounding boxes reduces per-frame jitter.
 * Recently-lost tracks are included in output for up to [carryOverFrames] frames
 * so brief misses don't cause visible flicker.
 */
class ByteTracker(
    private val maxLostFrames: Int = 1,
    private val carryOverFrames: Int = 1,
    private val highScoreThresh: Float = 0.45f,
    private val matchIouThresh: Float = 0.3f,
    private val secondMatchIouThresh: Float = 0.4f,
    private val smoothAlpha: Float = 0.85f
) {
    data class Detection(
        val label: String,
        val score: Float,
        val classId: Int,
        val box: RectF
    )

    data class Track(
        val trackId: Int,
        val classId: Int,
        val label: String,
        var box: RectF,
        var score: Float,
        var lostFrames: Int = 0
    )

    private var nextId = 1
    private val activeTracks = mutableListOf<Track>()
    private val lostTracks = mutableListOf<Track>()

    fun update(detections: List<Detection>): List<Track> {
        val highDets = detections.filter { it.score >= highScoreThresh }
        val lowDets = detections.filter { it.score < highScoreThresh }

        // Stage 1: active tracks ↔ high-score detections
        val (m1, unmatched1, unmatchedHigh) = greedyMatch(activeTracks, highDets, matchIouThresh)
        for ((track, det) in m1) {
            track.box = lerp(track.box, det.box, smoothAlpha)
            track.score = det.score
            track.lostFrames = 0
        }

        // Stage 2: remaining active tracks ↔ low-score detections
        val (m2, unmatched2, _) = greedyMatch(unmatched1, lowDets, secondMatchIouThresh)
        for ((track, det) in m2) {
            track.box = lerp(track.box, det.box, smoothAlpha)
            track.score = det.score
            track.lostFrames = 0
        }

        // Stage 3: lost tracks ↔ unmatched high-score detections (re-identification)
        val (m3, _, newDets) = greedyMatch(lostTracks, unmatchedHigh, matchIouThresh)
        for ((track, det) in m3) {
            track.box = det.box
            track.score = det.score
            track.lostFrames = 0
            activeTracks.add(track)
        }
        lostTracks.removeAll { t -> m3.any { (recovered, _) -> recovered.trackId == t.trackId } }

        // Move unmatched active tracks to lost
        for (track in unmatched2) {
            track.lostFrames++
            activeTracks.remove(track)
            lostTracks.add(track)
        }

        // Create new tracks for unmatched high-score detections
        for (det in newDets) {
            activeTracks.add(
                Track(
                    trackId = nextId++,
                    classId = det.classId,
                    label = det.label,
                    box = RectF(det.box),
                    score = det.score
                )
            )
        }

        // Prune expired lost tracks
        lostTracks.removeAll { it.lostFrames > maxLostFrames }

        // Return active tracks + recently-lost tracks (carry-over prevents flicker)
        return (activeTracks + lostTracks.filter { it.lostFrames <= carryOverFrames }).toList()
    }

    fun reset() {
        activeTracks.clear()
        lostTracks.clear()
        nextId = 1
    }

    private fun lerp(a: RectF, b: RectF, t: Float) = RectF(
        a.left + t * (b.left - a.left),
        a.top + t * (b.top - a.top),
        a.right + t * (b.right - a.right),
        a.bottom + t * (b.bottom - a.bottom)
    )

    private fun greedyMatch(
        tracks: List<Track>,
        detections: List<Detection>,
        iouThresh: Float
    ): Triple<List<Pair<Track, Detection>>, List<Track>, List<Detection>> {
        if (tracks.isEmpty()) return Triple(emptyList(), emptyList(), detections.toList())
        if (detections.isEmpty()) return Triple(emptyList(), tracks.toList(), emptyList())

        data class Hit(val ti: Int, val di: Int, val iou: Float)

        val hits = tracks.indices.flatMap { ti ->
            detections.indices.mapNotNull { di ->
                if (tracks[ti].classId != detections[di].classId) null
                else iou(tracks[ti].box, detections[di].box)
                    .takeIf { it >= iouThresh }
                    ?.let { Hit(ti, di, it) }
            }
        }.sortedByDescending { it.iou }

        val usedT = mutableSetOf<Int>()
        val usedD = mutableSetOf<Int>()
        val matched = mutableListOf<Pair<Track, Detection>>()
        for (h in hits) {
            if (h.ti in usedT || h.di in usedD) continue
            matched += tracks[h.ti] to detections[h.di]
            usedT += h.ti
            usedD += h.di
        }

        return Triple(
            matched,
            tracks.filterIndexed { i, _ -> i !in usedT },
            detections.filterIndexed { i, _ -> i !in usedD }
        )
    }

    private fun iou(a: RectF, b: RectF): Float {
        val inter = max(0f, min(a.right, b.right) - max(a.left, b.left)) *
            max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union <= 0f) 0f else inter / union
    }
}
