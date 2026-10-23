package net.shchoo.scafe.scafe.client.ui;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static com.googlecode.lanterna.TextColor.ANSI.*;
import static org.junit.jupiter.api.Assertions.*;

class LightModeTest {

    @Test
    void everyColourGoesToItsOpposite() {
        assertEquals(WHITE_BRIGHT, LightMode.invert(BLACK, true));
        assertEquals(BLACK_BRIGHT, LightMode.invert(WHITE, true));
        assertEquals(CYAN_BRIGHT, LightMode.invert(RED, true));
        assertEquals(CYAN, LightMode.invert(RED_BRIGHT, true));
        assertEquals(MAGENTA_BRIGHT, LightMode.invert(GREEN, true));
        assertEquals(YELLOW_BRIGHT, LightMode.invert(BLUE, true));
        assertEquals(BLACK, LightMode.invert(DEFAULT, true), "default text is light: dark now");
        assertEquals(WHITE_BRIGHT, LightMode.invert(DEFAULT, false), "default paper is black: white now");
        assertEquals(new TextColor.RGB(55, 155, 255), LightMode.invert(new TextColor.RGB(200, 100, 0), true));
        for (TextColor.ANSI color : TextColor.ANSI.values()) {
            if (color != DEFAULT) {
                assertEquals(color, LightMode.invert(LightMode.invert(color, true), true), "twice = back again: " + color);
            }
        }
    }

    @Test
    void theWholeScreenIsInvertedKeepingTheText() throws IOException {
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(Layout.TERMINAL_SIZE));
        screen.startScreen();
        screen.setCharacter(3, 2, new TextCharacter('#', GREEN_BRIGHT, BLUE, SGR.BOLD));
        LightMode.invert(screen);
        TextCharacter c = screen.getBackCharacter(3, 2);
        assertEquals("#", c.getCharacterString());
        assertEquals(MAGENTA, c.getForegroundColor());
        assertEquals(YELLOW_BRIGHT, c.getBackgroundColor());
        assertTrue(c.isBold());
        assertEquals(WHITE_BRIGHT, screen.getBackCharacter(0, 0).getBackgroundColor(), "blank paper turns white");
    }
}
