package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.LogEntry;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.client.SkillStyle;
import net.shchoo.scafe.scafe.protocol.Event;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.screen.Screen;

import java.util.List;

import static net.shchoo.scafe.scafe.client.ui.Layout.*;

/**
 * The chat / log box: the latest lines (players' chat as "name: text" with the name in their colour; notices bright,
 * routine ones dim, and hidden with Compact chat), and its bottom row kept for typing ({@link CommandLine}).
 */
public class MessageLogView extends Widget {
    private final CommandLine commandLine;

    public MessageLogView(Rect bounds, CommandLine commandLine) {
        super(bounds);
        this.commandLine = commandLine;
    }

    @Override
    public void draw(Screen screen, Player player, Board board) {
        drawFrame(screen);
        TextGraphics g = screen.newTextGraphics();
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        int x = bounds().x() + 1;
        int width = bounds().width() - 2;
        List<LogEntry> lines = board.log.latest(rows() - 1, player.compactChat);
        for (int i = 0; i < lines.size(); i++) {
            line(g, x, firstRow() + i, width, lines.get(i));
        }

        int inputRow = hintRow();
        String typed = commandLine.typed();
        if (typed != null) {
            g.setForegroundColor(TextColor.ANSI.WHITE_BRIGHT);
            g.putString(x, inputRow, "> ");
            g.setForegroundColor(TextColor.ANSI.DEFAULT);
            String shown = typed + "_";
            g.putString(x + 2, inputRow, shown.length() > width - 2 ? shown.substring(shown.length() - (width - 2)) : shown);
        } else {
            g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
            g.putString(x, inputRow, "[/] chat  (/ again for a command, e.g. //1)");
        }
    }

    private static void line(TextGraphics g, int x, int y, int width, LogEntry entry) {
        if (entry.kind() == Event.MessageKind.CHAT) {
            String speaker = fit(entry.speaker(), width);
            g.setForegroundColor(SkillStyle.playerColor(entry.speakerColor()));
            g.putString(x, y, speaker);
            g.setForegroundColor(TextColor.ANSI.DEFAULT);
            int used = speaker.length();
            if (used < width) {
                g.putString(x + used, y, fit(": " + entry.text(), width - used));
            }
            return;
        }
        g.setForegroundColor(switch (entry.kind()) {
            case ROUTINE -> TextColor.ANSI.BLACK_BRIGHT;
            case NOTICE -> TextColor.ANSI.WHITE_BRIGHT;
            default -> TextColor.ANSI.DEFAULT;
        });
        g.putString(x, y, fit(entry.text(), width));
    }
}
