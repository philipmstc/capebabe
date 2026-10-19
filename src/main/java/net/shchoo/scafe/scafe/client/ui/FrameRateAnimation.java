package net.shchoo.scafe.scafe.client.ui;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.screen.Screen;
import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;

import static com.googlecode.lanterna.TextColor.ANSI.*;
import static net.shchoo.scafe.scafe.client.ui.Layout.MAP;

public class FrameRateAnimation extends Widget {

    private static final char[] sprite = {'_', '.', 'o', 'O', 'o', '.'};
    private static final TextColor.ANSI[] rainbow = {
            RED,
            YELLOW,
            GREEN,
            CYAN,
            BLUE,
            MAGENTA
    };

    private static int frame = 0;
    public FrameRateAnimation() {
        super(new Rect(MAP.right(), 1, 1, 1));
    }

    @Override
    public void draw(Screen screen, Player player, Board board) {
        TextGraphics g = screen.newTextGraphics();
        g.setForegroundColor(rainbow[(frame / sprite.length) % rainbow.length]);
        g.setCharacter(bounds().x(), bounds().y(), sprite[frame++ % sprite.length]);
    }
}
