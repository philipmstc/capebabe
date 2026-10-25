package net.shchoo.scafe.scafe.client.ui;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.screen.Screen;

import java.util.ArrayList;
import java.util.List;

/**
 * A small menu with a title, drawn beside whatever it's about:
 * <pre>
 * monster3 (Lv 12)
 * > Fight
 *   Walk here
 *   Cancel
 * </pre>
 * Up/Down and Enter, or click an option; Esc, Cancel or a click anywhere else closes it. An option can carry a
 * grey line under it (what crafting costs), and one with no action is greyed out ("coming soon").
 */
public final class PopupMenu {
    /** {@code action} null = not available yet (grey). */
    public record Option(String label, String note, Runnable action) {
        public static Option of(String label, Runnable action) {
            return new Option(label, null, action);
        }
    }

    private final String title;
    private final TextColor titleColor;
    private final List<Option> options = new ArrayList<>();
    private int index;
    private boolean open = true;
    private Runnable onClose = () -> {};

    /** Run once it closes, however (an option, Cancel, Esc, a click off it). */
    public PopupMenu onClose(Runnable onClose) {
        this.onClose = onClose;
        return this;
    }

    public PopupMenu(String title, TextColor titleColor, List<Option> options) {
        this.title = title;
        this.titleColor = titleColor;
        this.options.addAll(options);
        this.options.add(Option.of("Cancel", () -> {}));
    }

    public boolean isOpen() {
        return open;
    }

    public String title() {
        return title;
    }

    public List<String> labels() {
        return options.stream().map(Option::label).toList();
    }

    public void close() {
        if (open) {
            open = false;
            onClose.run();
        }
    }

    /** Runs option {@code i} (if it's available) and closes. */
    public void choose(int i) {
        Option option = options.get(i);
        if (option.action() != null) {
            option.action().run();
        }
        close();
    }

    /** Arrows / Enter / Esc. Every key is the popup's while it's open. */
    public void key(KeyStroke key) {
        switch (key.getKeyType()) {
            case ArrowUp -> index = Math.max(0, index - 1);
            case ArrowDown -> index = Math.min(options.size() - 1, index + 1);
            case Escape -> close();
            case Enter -> choose(index);
            default -> {}
        }
    }

    /** Draws it with its top-left near {@code x}, {@code y} (kept on screen) and makes its options clickable. */
    public void draw(Screen screen, int x, int y, Clicks clicks) {
        if (!open) {
            return;
        }
        int rows = 1 + options.size() + (int) options.stream().filter(o -> o.note() != null).count();
        int width = Math.max(title.length(), options.stream().mapToInt(o -> Math.max(o.label().length() + 2,
                o.note() == null ? 0 : o.note().length() + 4)).max().orElse(0)) + 2;
        x = Math.max(0, Math.min(x, Layout.TERMINAL_SIZE.getColumns() - width - 2));
        y = Math.max(0, Math.min(y, Layout.TERMINAL_SIZE.getRows() - rows - 2));
        Rect box = new Rect(x, y, width + 2, rows + 2);
        TextGraphics g = screen.newTextGraphics();
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        g.fillRectangle(new TerminalPosition(box.x(), box.y()), new TerminalSize(box.width(), box.height()), ' ');
        Widget.drawFrame(screen, box);
        clicks.addClickOff(Clicks.POPUP, this::close);
        clicks.add(Clicks.POPUP, box, (button, cx, cy) -> {});
        int row = box.y() + 1;
        g.setForegroundColor(titleColor);
        g.enableModifiers(SGR.BOLD);
        g.putString(box.x() + 1, row++, title);
        g.disableModifiers(SGR.BOLD);
        for (int i = 0; i < options.size(); i++) {
            Option option = options.get(i);
            boolean selected = i == index;
            boolean available = option.action() != null && !option.label().equals("Cancel");
            g.setForegroundColor(!available ? TextColor.ANSI.BLACK_BRIGHT : selected ? TextColor.ANSI.WHITE_BRIGHT : TextColor.ANSI.DEFAULT);
            if (selected) {
                g.enableModifiers(SGR.BOLD);
            }
            g.putString(box.x() + 1, row, (selected ? "> " : "  ") + option.label());
            g.disableModifiers(SGR.BOLD);
            int chosen = i;
            int height = option.note() == null ? 1 : 2;
            clicks.option(Clicks.POPUP, box.x() + 1, row, box.width() - 2, height, (button, cx, cy) -> choose(chosen));
            row++;
            if (option.note() != null) {
                g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
                g.putString(box.x() + 5, row++, option.note());
            }
        }
    }
}
