/*
 * Copyright 2019 The Android Open Source Project
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

package android.app.timedetector;

import static android.app.timezonedetector.ParcelableTestSupport.assertRoundTripParcelable;
import static android.app.timezonedetector.ParcelableTestSupport.roundTripParcelable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.util.TimestampedValue;

import org.junit.Test;

public class ManualTimeSuggestionTest {

    private static final TimestampedValue<Long> ARBITRARY_TIME =
            new TimestampedValue<>(1111L, 2222L);

    @Test
    public void testEquals() {
        ManualTimeSuggestion one = new ManualTimeSuggestion(ARBITRARY_TIME);
        assertEquals(one, one);

        ManualTimeSuggestion two = new ManualTimeSuggestion(ARBITRARY_TIME);
        assertEquals(one, two);
        assertEquals(two, one);

        TimestampedValue<Long> differentTime = new TimestampedValue<>(
                ARBITRARY_TIME.getReferenceTimeMillis() + 1,
                ARBITRARY_TIME.getValue());
        ManualTimeSuggestion three = new ManualTimeSuggestion(differentTime);
        assertNotEquals(one, three);
        assertNotEquals(three, one);

        // DebugInfo must not be considered in equals().
        one.addDebugInfo("Debug info 1");
        two.addDebugInfo("Debug info 2");
        assertEquals(one, two);
    }

    @Test
    public void testParcelable() {
        ManualTimeSuggestion suggestion = new ManualTimeSuggestion(ARBITRARY_TIME);
        assertRoundTripParcelable(suggestion);

        // DebugInfo should also be stored (but is not checked by equals()
        suggestion.addDebugInfo("This is debug info");
        ManualTimeSuggestion rtSuggestion = roundTripParcelable(suggestion);
        assertEquals(suggestion.getDebugInfo(), rtSuggestion.getDebugInfo());
    }
}
