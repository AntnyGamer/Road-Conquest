package com.roadconquest.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RoadGroupingSharedReferenceTest {
    private fun road(reference: String) = RoadGrouping.Road(
        segmentId = reference,
        name = "Example Road",
        reference = reference,
        geometryJson = "[]",
        minLatitude = 0.0,
        maxLatitude = 0.0,
        minLongitude = 0.0,
        maxLongitude = 0.0
    )

    @Test fun directSetLookupMatchesPreviousSharedReferenceRule() {
        val references = listOf(
            "", " ", "I-295", "I 295", "US-1;US-9",
            "US9;I-295", "A1", "A-1", "Main Street",
            "I-95;US-1;US-9", ";", "I-76;US 30", "i295",
            "US-322;NJ-55", "NJ-55;US-322"
        )
        for (left in references) for (right in references) {
            val a = road(left)
            val b = road(right)
            // Compare every combination against the exact old method-reference
            // decision, including blank, multiplex and punctuation variants.
            val oldResult = a.referenceKeys.isNotEmpty() && b.referenceKeys.isNotEmpty() &&
                a.referenceKeys.any(b.referenceKeys::contains)
            assertEquals("refs '$left' and '$right'",
                oldResult, RoadGrouping.sharedReference(a, b))
        }
    }
}
