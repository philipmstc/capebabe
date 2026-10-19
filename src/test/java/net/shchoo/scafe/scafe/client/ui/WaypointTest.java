package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.generation.WorldGenerator;
import net.shchoo.scafe.scafe.model.Node;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.model.ProductLine;
import net.shchoo.scafe.scafe.model.Recipe;
import net.shchoo.scafe.scafe.model.Skill;
import net.shchoo.scafe.scafe.model.World;
import net.shchoo.scafe.scafe.protocol.Intent;
import net.shchoo.scafe.scafe.server.Discovery;
import net.shchoo.scafe.scafe.server.GameServer;
import net.shchoo.scafe.scafe.server.LocalConnection;

import com.googlecode.lanterna.Symbols;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WaypointTest {
    private GameServer server;
    private Player player;
    private Board board;
    private Recipe target;
    private Recipe other;
    private Recipe east;
    private Recipe northEast;
    private final SkillsMenu skills = new SkillsMenu();

    @BeforeEach
    void setUp() {
        World world = new World(1, "w", 1);
        world.content.apply(WorldGenerator.newSkills(world, 2));
        world.content.apply(WorldGenerator.newProductLines(world, 2));
        List<Recipe> recipes = world.content.skills.stream().flatMap(s -> s.productLines.stream())
                .flatMap(l -> l.recipes.stream()).toList();
        target = recipes.get(1);
        other = recipes.get(0);
        node(world, new Position(0, -4), target);
        node(world, new Position(0, 3), target);
        node(world, new Position(5, 0), other);
        east = recipes.get(2);
        northEast = recipes.get(3);
        node(world, new Position(40, 0), east);
        // exactly towards the map's top-right corner (tiles are 2 columns wide: 2 * 29 / 24 = 29 / 12)
        node(world, new Position(29, -24), northEast);
        server = new GameServer(world);
        server.setDiscovery(Discovery.off());
        player = new Player(new LocalConnection(server.connect()));
        board = new Board(player.content);
        player.send(new Intent.Join("alice"));
        tick();
    }

    private static void node(World world, Position at, Recipe recipe) {
        world.content.apply(WorldGenerator.newNode(world, at, Node.UNLIMITED, Node.UNLIMITED, 0, r -> r.equals(recipe) ? 1 : 0));
    }

    private void tick() {
        server.tick();
        player.receive();
        player.frame();
    }

    private void press(KeyType type) {
        skills.handleKey(new KeyStroke(type), player, board);
    }

    /** Opens the Skills tab's product lines on {@code recipe} (client's copy of it). */
    private void select(Recipe recipe) {
        skills.focus(player, board);
        Skill skill = player.content.recipe(recipe.id).skill;
        for (int i = board.skills.indexOf(skill); i > 0; i--) {
            press(KeyType.ArrowDown);
        }
        press(KeyType.ArrowRight);
        for (ProductLine line : skill.productLines) {
            int tier = line.recipes.indexOf(recipe);
            if (tier >= 0) {
                for (int i = 0; i < tier; i++) {
                    press(KeyType.ArrowRight);
                }
                break;
            }
            press(KeyType.ArrowDown);
        }
        assertEquals(recipe, skills.selectedRecipe(skill));
    }

    private Position waypointFor(Recipe recipe) {
        return player.waypoints.nodes().stream().filter(node -> node.recipe.equals(recipe)).findFirst()
                .map(node -> node.position).orElse(null);
    }

    @Test
    void enterOnARecipeSetsAWaypointToTheNearestNodeForIt() {
        select(target);
        press(KeyType.Enter);
        assertEquals(new Position(0, 3), waypointFor(target), "the closer of the two");

        press(KeyType.Enter);
        assertTrue(player.waypoints.isEmpty(), "Enter again clears it");
    }

    @Test
    void facingTheNodeClearsItsWaypointOnly() {
        select(other);
        press(KeyType.Enter);
        select(target);
        press(KeyType.Enter);
        assertEquals(new Position(5, 0), waypointFor(other));
        assertEquals(new Position(0, 3), waypointFor(target));

        for (int i = 0; i < 5; i++) {
            player.moveBy(1, 0);
            tick();
        }
        assertEquals(new Position(4, 0), new Position(player.x, player.y), "blocked by the node, facing it");
        assertNull(waypointFor(other), "reached");
        assertEquals(new Position(0, 3), waypointFor(target), "the other waypoint stays");
    }

    @Test
    void noKnownNodeMeansNoWaypoint() {
        Recipe unbuilt = player.content.skills.stream().flatMap(s -> s.productLines.stream())
                .flatMap(l -> l.recipes.stream()).filter(r -> player.nearestNode(r) == null).findFirst().orElseThrow();
        select(unbuilt);
        press(KeyType.Enter);
        assertTrue(player.waypoints.isEmpty());
    }

    @Test
    void offScreenWaypointsMarkThreeBorderCells() throws IOException {
        select(east);
        press(KeyType.Enter);
        select(northEast);
        press(KeyType.Enter);
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(Layout.TERMINAL_SIZE));
        screen.startScreen();
        new MapView(Layout.MAP).draw(screen, player, board);
        int right = Layout.MAP.right(), middle = Layout.tileScreenY(0, 0), top = Layout.MAP.y();

        assertEquals(List.of(Symbols.DOUBLE_LINE_VERTICAL, Symbols.ARROW_RIGHT, Symbols.DOUBLE_LINE_VERTICAL),
                column(screen, right, middle - 1, middle + 1), "east: an arrow between two double lines");
        assertEquals(List.of(Symbols.DOUBLE_LINE_TOP_RIGHT_CORNER, Symbols.DOUBLE_LINE_VERTICAL),
                column(screen, right, top, top + 1), "north-east: wraps round the corner, no arrow...");
        assertEquals(Symbols.DOUBLE_LINE_HORIZONTAL, screen.getBackCharacter(right - 1, top).getCharacterString().charAt(0),
                "...one cell along each edge");
        assertEquals(Symbols.SINGLE_LINE_VERTICAL, screen.getBackCharacter(right, top + 2).getCharacterString().charAt(0),
                "and no further");
        assertEquals(MapView.WAYPOINT_COLOR, screen.getBackCharacter(right, top).getForegroundColor());
        screen.stopScreen();
    }

    private static List<Character> column(Screen screen, int x, int fromY, int toY) {
        List<Character> cells = new ArrayList<>();
        for (int y = fromY; y <= toY; y++) {
            TextCharacter c = screen.getBackCharacter(x, y);
            cells.add(c.getCharacterString().charAt(0));
        }
        return cells;
    }
}
