package net.shchoo.scafe.scafe.client.ui;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;
import com.googlecode.lanterna.terminal.swing.SwingTerminal;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * The mouse, for the game's own window. Lanterna's Swing terminal doesn't report clicks to the program (its mouse
 * capture only works in real terminals), so this listens to the window itself and feeds presses -- and, for hover,
 * moves into a new cell -- into its input as {@link MouseAction}s, which the game reads along with the keys.
 */
public final class WindowMouse {
    /** The cell to report when the pointer leaves the window (no tile under it). */
    public static final TerminalPosition OUTSIDE = new TerminalPosition(-1, -1);

    private WindowMouse() {}

    /** Starts listening to the terminal inside {@code window}; does nothing if there isn't one. */
    public static void listen(Container window) {
        SwingTerminal terminal = find(window);
        if (terminal == null) {
            return;
        }
        MouseAdapter adapter = new MouseAdapter() {
            private TerminalPosition last = OUTSIDE;

            @Override
            public void mousePressed(MouseEvent e) {
                int button = SwingUtilities.isLeftMouseButton(e) ? 1 : SwingUtilities.isRightMouseButton(e) ? 3 : 2;
                terminal.addInput(new MouseAction(MouseActionType.CLICK_DOWN, button, cell(terminal, e)));
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                moved(cell(terminal, e));
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                moved(cell(terminal, e));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                moved(OUTSIDE);
            }

            /** Only when it's in a new cell: hover needs no more. */
            private void moved(TerminalPosition at) {
                if (!at.equals(last)) {
                    last = at;
                    terminal.addInput(new MouseAction(MouseActionType.MOVE, 0, at));
                }
            }
        };
        terminal.addMouseListener(adapter);
        terminal.addMouseMotionListener(adapter);
    }

    /** The character cell under a pixel. */
    static TerminalPosition cell(int x, int y, int cellWidth, int cellHeight) {
        return new TerminalPosition(x / Math.max(1, cellWidth), y / Math.max(1, cellHeight));
    }

    private static TerminalPosition cell(SwingTerminal terminal, MouseEvent e) {
        return cell(e.getX(), e.getY(), terminal.getFontConfiguration().getFontWidth(),
                terminal.getFontConfiguration().getFontHeight());
    }

    private static SwingTerminal find(Component component) {
        if (component instanceof SwingTerminal terminal) {
            return terminal;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                SwingTerminal found = find(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
