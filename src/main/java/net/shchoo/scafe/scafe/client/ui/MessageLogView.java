package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.screen.Screen;

import java.util.List;

import static net.shchoo.scafe.scafe.client.ui.Layout.*;

/** The last few log lines under the command line. */
public class MessageLogView extends Widget {

    @Override
    public void draw(Screen screen, Player player, Board board) {
        TerminalSize size = screen.getTerminalSize();
        int width = size.getColumns() - LOG_POS_X;
        int visibleLines = Math.min(LOG_LINES, size.getRows() - LOG_POS_Y);
        if (width <= 0 || visibleLines <= 0) {
            return;
        }
        TextGraphics graphics = screen.newTextGraphics();
        graphics.setForegroundColor(TextColor.ANSI.DEFAULT);
        graphics.setBackgroundColor(TextColor.ANSI.DEFAULT);
        List<String> lines = board.log.latest(visibleLines);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.length() > width) {
                line = line.substring(0, width - 1) + "~";
            }
            graphics.putString(LOG_POS_X, LOG_POS_Y + i, line);
        }
    }
}
