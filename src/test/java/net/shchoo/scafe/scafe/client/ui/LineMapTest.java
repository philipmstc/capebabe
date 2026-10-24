package net.shchoo.scafe.scafe.client.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LineMapTest {
    @Test
    void linesJoinWhereTheyMeet() {
        assertEquals('\u250c', StationView.Tracks.glyph(2 | 8), "down and right: a corner");
        assertEquals('\u2518', StationView.Tracks.glyph(1 | 4));
        assertEquals('\u251c', StationView.Tracks.glyph(1 | 2 | 8), "a tee");
        assertEquals('\u253c', StationView.Tracks.glyph(1 | 2 | 4 | 8), "a crossing");
        assertEquals('\u2500', StationView.Tracks.glyph(4 | 8));
        assertEquals('\u2502', StationView.Tracks.glyph(1));
    }
}
