package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;

import java.util.ArrayList;
import java.util.List;

import static net.shchoo.scafe.scafe.client.ui.Layout.*;

/**
 * The tutorial's box over the top of the map: the {@link Tutorial}'s title (yellow) and what to do, word-wrapped;
 * an explanation has "[ Got it ]" under it (Enter, or a click). Clicks on the box don't go through to the map.
 */
public class TutorialView extends Widget {
    static final String GOT_IT = "[ Got it ]";

    private final Tutorial tutorial;
    private final SkillsMenu skills;

    public TutorialView(Tutorial tutorial, SkillsMenu skills) {
        super(MAP);
        this.tutorial = tutorial;
        this.skills = skills;
    }

    public Tutorial tutorial() {
        return tutorial;
    }

    private Tutorial.Screen screen() {
        return new Tutorial.Screen() {
            @Override
            public boolean skillPageOpen() {
                return skills.isFocused() && skills.skillOpen();
            }

            @Override
            public boolean followedLink() {
                return skills.isFocused() && skills.followedLink();
            }
        };
    }

    @Override
    public void draw(Screen screen, Player player, Board board) {
        tutorial.update(player, screen());
    }

    @Override
    public void drawOnTop(Screen screen, Player player, Board board) {
        List<String> text = tutorial.text(player);
        if (text.isEmpty()) {
            return;
        }
        int left = MAP.x() + 2, right = MAP.right() - 2, width = right - left - 3;
        List<String> lines = new ArrayList<>();
        for (int i = 1; i < text.size(); i++) {
            lines.addAll(wrap(text.get(i), width));
        }
        boolean gotIt = tutorial.dismissable();
        int top = MAP.y() + 1, bottom = top + 2 + lines.size() + (gotIt ? 2 : 0);
        TextGraphics g = screen.newTextGraphics();
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        for (int y = top; y <= bottom; y++) {
            g.putString(left, y, " ".repeat(right - left + 1));
        }
        drawFrame(screen, left, top, right, bottom);
        g.setForegroundColor(TextColor.ANSI.YELLOW_BRIGHT);
        g.enableModifiers(SGR.BOLD);
        g.putString(left + 2, top + 1, fit(text.get(0), width));
        g.disableModifiers(SGR.BOLD);
        g.setForegroundColor(TextColor.ANSI.DEFAULT);
        for (int i = 0; i < lines.size(); i++) {
            g.putString(left + 2, top + 2 + i, lines.get(i));
        }
        // the box takes its clicks, so they don't walk you somewhere under it
        board.clicks.add(Clicks.POPUP, left, top, right - left + 1, bottom - top + 1, (button, cx, cy) -> {});
        if (gotIt) {
            int x = right - 1 - GOT_IT.length(), y = bottom - 1;
            g.setForegroundColor(TextColor.ANSI.WHITE_BRIGHT);
            g.putString(x, y, GOT_IT);
            board.clicks.option(Clicks.POPUP, x, y, GOT_IT.length(), 1, (button, cx, cy) -> tutorial.dismiss());
        }
    }

    /** Enter dismisses an explanation. */
    @Override
    public boolean onKey(KeyStroke key, Player player, Board board) {
        if (key.getKeyType() == KeyType.Enter && tutorial.dismissable() && !tutorial.text(player).isEmpty()) {
            tutorial.dismiss();
            return true;
        }
        return false;
    }

    /** Wrapped to {@code width}; a "marker  <-- what it is" line keeps its marker column, the rest hanging under it. */
    static List<String> wrap(String line, int width) {
        int arrow = line.indexOf("<-- ");
        if (arrow < 0) {
            return ChurchView.wrap(line, width);
        }
        String head = line.substring(0, arrow + 4);
        List<String> rest = ChurchView.wrap(line.substring(arrow + 4), Math.max(10, width - head.length()));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < rest.size(); i++) {
            out.add((i == 0 ? head : " ".repeat(head.length())) + rest.get(i));
        }
        return out;
    }
}
