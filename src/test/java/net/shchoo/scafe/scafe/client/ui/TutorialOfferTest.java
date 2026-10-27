package net.shchoo.scafe.scafe.client.ui;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TutorialOfferTest {
    @Test
    void yesIsPickedFirst_arrowsSwap_enterAnswers() {
        TutorialOffer offer = new TutorialOffer();
        assertTrue(offer.yesPicked());
        assertNull(offer.onKey(new KeyStroke(KeyType.ArrowRight)));
        assertFalse(offer.yesPicked());
        assertEquals(false, offer.onKey(new KeyStroke(KeyType.Enter)));
        assertEquals(true, new TutorialOffer().onKey(new KeyStroke(KeyType.Enter)));
        assertEquals(true, new TutorialOffer().onKey(new KeyStroke('y', false, false)));
        assertEquals(false, new TutorialOffer().onKey(new KeyStroke('n', false, false)));
        assertEquals(false, new TutorialOffer().onKey(new KeyStroke(KeyType.Escape)), "Esc: no");
    }

    @Test
    void itAsksAndTheButtonsAreClickable() throws Exception {
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(Layout.TERMINAL_SIZE));
        screen.startScreen();
        TutorialOffer offer = new TutorialOffer();
        offer.draw(screen);
        int row = -1, yes = -1, no = -1;
        for (int y = 0; y < Layout.TERMINAL_SIZE.getRows(); y++) {
            StringBuilder line = new StringBuilder();
            for (int x = 0; x < Layout.TERMINAL_SIZE.getColumns(); x++) {
                line.append(screen.getBackCharacter(x, y).getCharacterString());
            }
            if (line.indexOf("<Yes>") >= 0) {
                row = y;
                yes = line.indexOf("<Yes>");
                no = line.indexOf("<No>");
            }
            if (line.indexOf(TutorialOffer.QUESTION) >= 0) {
                assertTrue(y < Layout.TERMINAL_SIZE.getRows() / 2 + 1);
            }
        }
        assertTrue(row >= 0 && no > yes);
        assertEquals(true, offer.click(yes + 1, row));
        assertEquals(false, offer.click(no + 1, row));
        assertNull(offer.click(0, row));
    }
}
