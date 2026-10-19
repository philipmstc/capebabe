package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.screen.Screen;

import java.util.List;

import static net.shchoo.scafe.scafe.client.ui.Layout.*;

/** The log box: the latest log lines, and the /command being typed on its top border. */
public class MessageLogView extends Widget {
    private final CommandLine commandLine;

    public MessageLogView(Rect bounds, CommandLine commandLine) {
        super(bounds);
        this.commandLine = commandLine;
    }

    @Override
    public void draw(Screen screen, Player player, Board board) {
        drawFrame(screen);
        TextGraphics graphics = screen.newTextGraphics();
        graphics.setForegroundColor(TextColor.ANSI.DEFAULT);
        graphics.setBackgroundColor(TextColor.ANSI.DEFAULT);
        List<String> lines = board.log.latest(rows());
        for (int i = 0; i < lines.size(); i++) {
            graphics.putString(bounds().x() + 1, firstRow() + i, fit(lines.get(i), bounds().width() - 2));
        }
        if (commandLine.typed() != null) {
            graphics.putString(textX(), bounds().y(), fit(" /" + commandLine.typed() + "_ ", textWidth()));
        }
    }
}
