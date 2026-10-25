package net.shchoo.scafe.scafe.client.ui;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;
import com.googlecode.lanterna.terminal.swing.SwingTerminal;
import com.googlecode.lanterna.terminal.swing.SwingTerminalFrame;
import org.junit.jupiter.api.Test;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class WindowMouseTest {
    @Test
    void pixelsToCells() {
        assertEquals(new TerminalPosition(3, 2), WindowMouse.cell(3 * 9 + 4, 2 * 17 + 16, 9, 17));
        assertEquals(new TerminalPosition(0, 0), WindowMouse.cell(0, 0, 9, 17));
    }

    private static SwingTerminal terminalIn(Component component) {
        if (component instanceof SwingTerminal terminal) {
            return terminal;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                SwingTerminal found = terminalIn(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void send(Component to, int id, int x, int y, int button) {
        to.dispatchEvent(new MouseEvent(to, id, System.currentTimeMillis(),
                button == MouseEvent.BUTTON3 ? InputEvent.BUTTON3_DOWN_MASK : 0, x, y, 1, false, button));
    }

    /** A real lanterna window: what it gets from the mouse comes out of its input as MouseActions on the right cell. */
    @Test
    void theWindowsMouseComesInWithTheKeys() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        SwingTerminalFrame frame = new SwingTerminalFrame("test");
        try {
            WindowMouse.listen(frame);
            SwingTerminal terminal = terminalIn(frame);
            assertNotNull(terminal);
            int w = terminal.getFontConfiguration().getFontWidth(), h = terminal.getFontConfiguration().getFontHeight();

            send(terminal, MouseEvent.MOUSE_PRESSED, 3 * w + 1, 2 * h + 1, MouseEvent.BUTTON1);
            KeyStroke key = frame.pollInput();
            MouseAction click = assertInstanceOf(MouseAction.class, key);
            assertEquals(MouseActionType.CLICK_DOWN, click.getActionType());
            assertEquals(1, click.getButton());
            assertEquals(new TerminalPosition(3, 2), click.getPosition());

            send(terminal, MouseEvent.MOUSE_PRESSED, 5 * w + 1, 1, MouseEvent.BUTTON3);
            assertEquals(3, ((MouseAction) frame.pollInput()).getButton(), "right");

            send(terminal, MouseEvent.MOUSE_MOVED, 7 * w + 1, 4 * h + 1, MouseEvent.NOBUTTON);
            send(terminal, MouseEvent.MOUSE_MOVED, 7 * w + 2, 4 * h + 2, MouseEvent.NOBUTTON);
            MouseAction move = (MouseAction) frame.pollInput();
            assertTrue(move.isMouseMove());
            assertEquals(new TerminalPosition(7, 4), move.getPosition());
            assertNull(frame.pollInput(), "the same cell again says nothing");

            send(terminal, MouseEvent.MOUSE_EXITED, 0, 0, MouseEvent.NOBUTTON);
            assertEquals(WindowMouse.OUTSIDE, ((MouseAction) frame.pollInput()).getPosition(), "leaving: no tile");
        } finally {
            frame.dispose();
        }
    }
}
