package com.sentinel.admin.ui.map

import kotlin.math.abs

sealed class ValidationResult {
    data object Valid : ValidationResult()
    data class Invalid(val reason: String) : ValidationResult()

    val isValid: Boolean get() = this is Valid
    val errorMessage: String? get() = (this as? Invalid)?.reason
}

object GeofenceGeometryValidator {

    const val MIN_RADIUS_METERS = 10.0f
    const val MAX_RADIUS_METERS = 50_000.0f

    /**
     * Validates latitude and longitude coordinates.
     * Preserves genuine (0.0, 0.0) coordinates.
     */
    fun validateCoordinate(lat: Double?, lon: Double?): ValidationResult {
        if (lat == null || lon == null) {
            return ValidationResult.Invalid("Coordinates cannot be null")
        }
        if (!lat.isFinite() || !lon.isFinite()) {
            return ValidationResult.Invalid("Coordinates must be finite numbers")
        }
        if (lat < -90.0 || lat > 90.0) {
            return ValidationResult.Invalid("Latitude must be within [-90.0, 90.0] (was $lat)")
        }
        if (lon < -180.0 || lon > 180.0) {
            return ValidationResult.Invalid("Longitude must be within [-180.0, 180.0] (was $lon)")
        }
        return ValidationResult.Valid
    }

    /**
     * Validates geofence circle radius.
     * Enforces explicit minimum of 10 meters and maximum of 50,000 meters (50 km).
     */
    fun validateRadius(radiusMeters: Float?): ValidationResult {
        if (radiusMeters == null) {
            return ValidationResult.Invalid("Radius cannot be null")
        }
        if (!radiusMeters.isFinite()) {
            return ValidationResult.Invalid("Radius must be a finite number")
        }
        if (radiusMeters < MIN_RADIUS_METERS) {
            return ValidationResult.Invalid("Radius must be at least ${MIN_RADIUS_METERS.toInt()} meters (was $radiusMeters m)")
        }
        if (radiusMeters > MAX_RADIUS_METERS) {
            return ValidationResult.Invalid("Radius cannot exceed ${MAX_RADIUS_METERS.toInt()} meters (was $radiusMeters m)")
        }
        return ValidationResult.Valid
    }

    /**
     * Validates center coordinates and radius for circle geofences.
     */
    fun validateCircle(lat: Double?, lon: Double?, radiusMeters: Float?): ValidationResult {
        val coordResult = validateCoordinate(lat, lon)
        if (!coordResult.isValid) return coordResult
        return validateRadius(radiusMeters)
    }

    /**
     * Validates polygon vertices using a strict single-ring policy:
     * - Normalizes optional terminal closure duplicate (if last == first)
     * - At least 3 vertices
     * - Finite, in-bounds coordinates ([-90, 90], [-180, 180])
     * - No zero-length edges (consecutive identical vertices)
     * - No repeated interior vertices (self-touching rings)
     * - Antimeridian span <= 180 degrees
     * - Non-adjacent edge intersection and touching/overlap rejection
     * - Scale-aware degeneracy and area validation (accepts valid small geofences like 11m squares)
     */
    fun validatePolygon(rawVertices: List<Pair<Double, Double>>): ValidationResult {
        // 1. Normalize terminal closure duplicate if present
        val vertices = if (rawVertices.size >= 4 && rawVertices.first() == rawVertices.last()) {
            rawVertices.dropLast(1)
        } else {
            rawVertices
        }

        if (vertices.size < 3) {
            return ValidationResult.Invalid("Polygon requires at least 3 vertices (provided ${vertices.size})")
        }

        // 2. Verify every vertex is in bounds
        for ((idx, v) in vertices.withIndex()) {
            val coordResult = validateCoordinate(v.first, v.second)
            if (coordResult is ValidationResult.Invalid) {
                return ValidationResult.Invalid("Vertex $idx invalid: ${coordResult.reason}")
            }
        }

        val n = vertices.size

        // 3. Reject zero-length edges (consecutive identical vertices)
        for (i in 0 until n) {
            val nextIdx = (i + 1) % n
            if (vertices[i] == vertices[nextIdx]) {
                return ValidationResult.Invalid("Polygon contains zero-length edge between vertex $i and $nextIdx")
            }
        }

        // 4. Distinct vertices check: reject repeated interior vertices / self-touching rings
        val distinctCount = vertices.toSet().size
        if (distinctCount < n) {
            return ValidationResult.Invalid("Polygon contains repeated vertices (touching ring)")
        }

        // 5. Antimeridian check
        val minLon = vertices.minOf { it.second }
        val maxLon = vertices.maxOf { it.second }
        if (abs(maxLon - minLon) > 180.0) {
            return ValidationResult.Invalid("Antimeridian crossing geometry is unsupported")
        }

        // 6. Self-intersection & non-adjacent segment touches/overlaps
        for (i in 0 until n) {
            val a1 = vertices[i]
            val a2 = vertices[(i + 1) % n]

            for (j in (i + 1) until n) {
                // Adjacent segments share a vertex; skip them
                if (j == (i + 1) % n || (i == 0 && j == n - 1)) continue

                val b1 = vertices[j]
                val b2 = vertices[(j + 1) % n]

                if (segmentsIntersectOrTouch(a1, a2, b1, b2)) {
                    return ValidationResult.Invalid("Polygon edges self-intersect or touch between segments ($i) and ($j)")
                }
            }
        }

        // 7. Scale-aware area validation (Shoelace formula)
        var signedArea = 0.0
        for (i in 0 until n) {
            val j = (i + 1) % n
            val (x1, y1) = vertices[i]
            val (x2, y2) = vertices[j]
            signedArea += (x1 * y2 - x2 * y1)
        }
        signedArea /= 2.0

        val minLat = vertices.minOf { it.first }
        val maxLat = vertices.maxOf { it.first }
        val spanLat = maxLat - minLat
        val spanLon = maxLon - minLon
        val bboxDiagonalSq = spanLat * spanLat + spanLon * spanLon

        // Scale-aware degeneracy check: reject collinearity while preserving valid small shapes
        if (abs(signedArea) <= 1e-13 || (abs(signedArea) / maxOf(bboxDiagonalSq, 1e-12)) < 1e-6) {
            return ValidationResult.Invalid("Polygon has zero area (collinear or degenerate vertices)")
        }

        return ValidationResult.Valid
    }

    private fun segmentsIntersectOrTouch(
        p1: Pair<Double, Double>,
        p2: Pair<Double, Double>,
        p3: Pair<Double, Double>,
        p4: Pair<Double, Double>
    ): Boolean {
        val eps = 1e-12

        fun crossProduct(a: Pair<Double, Double>, b: Pair<Double, Double>, c: Pair<Double, Double>): Double {
            return (b.first - a.first) * (c.second - a.second) - (b.second - a.second) * (c.first - a.first)
        }

        fun onSegment(a: Pair<Double, Double>, b: Pair<Double, Double>, p: Pair<Double, Double>): Boolean {
            val minX = minOf(a.first, b.first) - eps
            val maxX = maxOf(a.first, b.first) + eps
            val minY = minOf(a.second, b.second) - eps
            val maxY = maxOf(a.second, b.second) + eps
            return p.first in minX..maxX && p.second in minY..maxY
        }

        val cp1 = crossProduct(p3, p4, p1)
        val cp2 = crossProduct(p3, p4, p2)
        val cp3 = crossProduct(p1, p2, p3)
        val cp4 = crossProduct(p1, p2, p4)

        // Strict straddle crossing
        val straddle1 = (cp1 > eps && cp2 < -eps) || (cp1 < -eps && cp2 > eps)
        val straddle2 = (cp3 > eps && cp4 < -eps) || (cp3 < -eps && cp4 > eps)
        if (straddle1 && straddle2) return true

        // Endpoint touching or collinear overlap on non-adjacent edges
        if (abs(cp1) <= eps && onSegment(p3, p4, p1)) return true
        if (abs(cp2) <= eps && onSegment(p3, p4, p2)) return true
        if (abs(cp3) <= eps && onSegment(p1, p2, p3)) return true
        if (abs(cp4) <= eps && onSegment(p1, p2, p4)) return true

        return false
    }
}
