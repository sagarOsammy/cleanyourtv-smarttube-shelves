package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ShelfImagesTest {
    @Test public void sampleSizeKeepsAtLeastTheCardWidth() {
        assertEquals(1, ShelfImages.sampleSize(320, 320));
        assertEquals(2, ShelfImages.sampleSize(1279, 320));
        assertEquals(4, ShelfImages.sampleSize(1280, 320));
        assertEquals(4, ShelfImages.sampleSize(1920, 320));
        assertEquals(1, ShelfImages.sampleSize(100, 320));
    }
}
