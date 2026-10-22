package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.client.SkillStyle;
import net.shchoo.scafe.scafe.generation.WorldGenerator;
import net.shchoo.scafe.scafe.model.Node;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.model.Recipe;
import net.shchoo.scafe.scafe.model.World;
import net.shchoo.scafe.scafe.protocol.Intent;
import net.shchoo.scafe.scafe.server.Discovery;
import net.shchoo.scafe.scafe.server.GameServer;
import net.shchoo.scafe.scafe.server.LocalConnection;

import com.googlecode.lanterna.Symbols;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MapViewTest {
    /** The view's outermost columns (the player starts at 0,0). */
    private static final int EDGE = Layout.BOARD_WIDTH - 1 - Layout.VIEW_CENTER_COLUMN;
    /** Boxed in by four nodes: nobody can walk there. */
    private static final Position BOXED_IN = new Position(5, 5);

    private GameServer server;
    private Player player;
    private Board board;
    private Recipe tier0;
    private Recipe tier3;

    @BeforeEach
    void setUp() {
        World world = new World(1, "w", 1);
        world.content.apply(WorldGenerator.newSkills(world, 1));
        List<Recipe> line = world.content.skills.get(0).productLines.get(0).recipes;
        tier0 = line.get(0);
        tier3 = line.get(3);
        world.content.apply(WorldGenerator.newNode(world, new Position(2, 0), Node.UNLIMITED, Node.UNLIMITED, 0,
                r -> r.equals(tier0) ? 1 : 0));
        world.content.apply(WorldGenerator.newNode(world, new Position(-2, 0), Node.UNLIMITED, Node.UNLIMITED, 0,
                r -> r.equals(tier3) ? 1 : 0));
        for (int x : new int[]{EDGE, -EDGE}) {
            world.content.apply(WorldGenerator.newNode(world, new Position(x, 0), Node.UNLIMITED, Node.UNLIMITED, 0,
                    r -> r.equals(tier3) ? 1 : 0));
        }
        for (Position side : List.of(new Position(4, 5), new Position(6, 5), new Position(5, 4), new Position(5, 6))) {
            world.content.apply(WorldGenerator.newNode(world, side, Node.UNLIMITED, Node.UNLIMITED, 0, r -> 1));
        }
        server = new GameServer(world);
        server.setDiscovery(Discovery.off());
        player = new Player(new LocalConnection(server.connect()));
        board = new Board(player.content);
        player.send(new Intent.Join("alice"));
        server.tick();
        player.receive();
    }

    private TextCharacter[] cellsAround(Screen screen, int worldX) {
        int x = Layout.tileScreenX(worldX, player.x), y = Layout.tileScreenY(0, player.y);
        return new TextCharacter[]{screen.getBackCharacter(x - 1, y), screen.getBackCharacter(x, y), screen.getBackCharacter(x + 1, y)};
    }

    private Screen draw() throws IOException {
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(Layout.TERMINAL_SIZE));
        screen.startScreen();
        new MapView(Layout.MAP).draw(screen, player, board);
        return screen;
    }

    @Test
    void aMarkedNodeColoursAllThreeOfItsCells() throws IOException {
        player.toggleHighlighting(player.content.recipe(tier0.id));
        TextColor dark = SkillStyle.darkOf(player.content.skills.get(0));
        for (TextCharacter c : cellsAround(draw(), 2)) {
            assertEquals(dark, c.getBackgroundColor(), "the tile drawn next (an empty one) mustn't wipe the right-hand cell");
            assertEquals(TextColor.ANSI.BLACK, c.getForegroundColor());
        }
        player.toggleHighlighting(player.content.recipe(tier0.id));
        assertEquals(TextColor.ANSI.DEFAULT, cellsAround(draw(), 2)[2].getBackgroundColor(), "unmarked again");
    }

    @Test
    void tierDotsSurviveTheTilesDrawnAfterThem() throws IOException {
        player.showProductTiers = true;
        TextCharacter[] cells = cellsAround(draw(), -2);
        assertEquals(":", cells[0].getCharacterString());
        assertEquals(".", cells[2].getCharacterString(), "tier 3: ':' on the left, '.' on the right");
    }

    private void tick() {
        server.tick();
        player.receive();
        player.frame();
    }

    @Test
    void tierDotsOnTheOutermostColumnsLeaveTheFrameAlone() throws IOException {
        player.showProductTiers = true;
        Screen screen = draw();
        int y = Layout.tileScreenY(0, player.y);
        assertEquals(String.valueOf(Symbols.SINGLE_LINE_VERTICAL), screen.getBackCharacter(Layout.MAP.x(), y).getCharacterString());
        assertEquals(String.valueOf(Symbols.SINGLE_LINE_VERTICAL), screen.getBackCharacter(Layout.MAP.right(), y).getCharacterString());
        assertEquals(":", cellsAround(screen, EDGE)[0].getCharacterString(), "the inner dot still shows");
    }

    @Test
    void whereYoureWalkingToIsRedUntilYouGetThere() throws IOException {
        Position target = new Position(0, 3);
        player.walkTo(target);
        int x = Layout.tileScreenX(target.x(), player.x), y = Layout.tileScreenY(target.y(), player.y);
        assertEquals(TextColor.ANSI.RED_BRIGHT, draw().getBackCharacter(x, y).getForegroundColor());
        for (int i = 0; i < 4; i++) {
            tick();
        }
        assertEquals(target, new Position(player.x, player.y));
        assertNull(player.walkTarget, "arrived");
    }

    @Test
    void aStepOfYourOwnOrAWalkGoingNowhereClearsIt() {
        player.walkTo(new Position(0, 3));
        player.moveBy(1, 0);
        assertNull(player.walkTarget, "stepped off on your own");

        player.walkTo(BOXED_IN);
        for (int i = 0; i < 6; i++) {
            tick();
        }
        assertNull(player.walkTarget, "no way there: forgotten once you've stood still a few ticks");
    }
}
