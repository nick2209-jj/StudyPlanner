package com.example.studyplanner

import com.example.studyplanner.model.FreeTimeSlot
import com.google.firebase.firestore.PropertyName
import org.junit.Assert.assertEquals
import org.junit.Test

class FreeTimeSlotTest {

    @Test
    fun recurringFlagUsesFirestoreRuleFieldName() {
        val getter = FreeTimeSlot::class.java.getMethod("isRecurring")
        val annotation = requireNotNull(getter.getAnnotation(PropertyName::class.java))

        assertEquals("isRecurring", annotation.value)
    }
}
