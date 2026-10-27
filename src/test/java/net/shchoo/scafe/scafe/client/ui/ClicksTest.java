package net.shchoo.scafe.scafe.client.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClicksTest {
    @Test
    void theTopmostAreaUnderThePointerGetsTheClick() {
        Clicks clicks = new Clicks();
        List<String> got = new ArrayList<>();
        clicks.add(Clicks.BASE, 0, 0, 10, 10, (b, x, y) -> got.add("map " + b + " " + x + "," + y));
        clicks.add(Clicks.BASE, 2, 2, 2, 2, (b, x, y) -> got.add("node"));
        clicks.add(Clicks.POPUP, 5, 5, 3, 1, (b, x, y) -> got.add("popup"));
        assertTrue(clicks.click(Clicks.Button.LEFT, 1, 1));
        assertTrue(clicks.click(Clicks.Button.RIGHT, 3, 3));
        assertTrue(clicks.click(Clicks.Button.LEFT, 6, 5));
        assertFalse(clicks.click(Clicks.Button.LEFT, 20, 20), "nothing there");
        assertEquals(List.of("map LEFT 1,1", "node", "popup"), got, "drawn later wins; a higher layer wins over that");

        clicks.clear();
        assertFalse(clicks.click(Clicks.Button.LEFT, 1, 1), "cleared every frame");
    }

    @Test
    void aModalLayerTakesEveryClick() {
        Clicks clicks = new Clicks();
        List<String> got = new ArrayList<>();
        clicks.add(Clicks.BASE, 0, 0, 10, 10, (b, x, y) -> got.add("map"));
        clicks.addEverywhere(Clicks.MODAL, (b, x, y) -> got.add("bank"));
        clicks.click(Clicks.Button.LEFT, 1, 1);
        assertEquals(List.of("bank"), got);
    }

    @Test
    void theWheelScrollsTheListUnderItUnlessSomethingsOverIt() {
        Clicks clicks = new Clicks();
        List<Integer> got = new ArrayList<>();
        clicks.add(Clicks.BASE, 0, 0, 10, 10, (b, x, y) -> {});
        clicks.addScroll(Clicks.BASE, new Rect(0, 0, 10, 10), got::add);
        assertTrue(clicks.scroll(1, 2, 2));
        assertTrue(clicks.scroll(-1, 2, 2));
        assertFalse(clicks.scroll(1, 20, 20), "no list there");
        clicks.add(Clicks.POPUP, 1, 1, 3, 3, (b, x, y) -> {});
        assertFalse(clicks.scroll(1, 2, 2), "a popup's in the way");
        assertTrue(clicks.scroll(1, 8, 8), "beside it, still");
        assertEquals(List.of(1, -1, 1), got);
    }
}
