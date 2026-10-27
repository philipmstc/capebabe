package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.generation.WorldGenerator;
import net.shchoo.scafe.scafe.model.Building;
import net.shchoo.scafe.scafe.model.Node;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.model.Recipe;
import net.shchoo.scafe.scafe.model.World;
import net.shchoo.scafe.scafe.protocol.Intent;
import net.shchoo.scafe.scafe.server.Discovery;
import net.shchoo.scafe.scafe.server.GameServer;
import net.shchoo.scafe.scafe.server.LocalConnection;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class PointsOfInterestTest {
    private GameServer server;
    private Player player;
    private Board board;
    private Screen screen;
    private Recipe recipe;
    private final MapMenu map = new MapMenu();

    @BeforeEach
    void setUp() throws IOException {
        World world = new World(1, "w", 1);
        world.content.apply(WorldGenerator.newSkills(world, 1));
        recipe = world.content.skills.get(0).productLines.get(0).recipes.get(0);
        world.content.apply(WorldGenerator.newNode(world, new Position(3, 0), Node.UNLIMITED, Node.UNLIMITED, 0,
                r -> r.equals(recipe) ? 1 : 0));
        world.content.apply(WorldGenerator.newBuildingAt(world, Building.Kind.BANK, new Position(60, 0), 10, p -> false));
        world.content.apply(WorldGenerator.newBuildingAt(world, Building.Kind.BANK, new Position(-90, 0), 10, p -> false));
        world.content.apply(WorldGenerator.newBuildingAt(world, Building.Kind.CHURCH, new Position(0, 40), 10, p -> false));
        server = new GameServer(world);
        server.setDiscovery(Discovery.off());
        player = new Player(new LocalConnection(server.connect()));
        board = new Board(player.content);
        player.send(new Intent.Join("alice"));
        server.tick();
        player.receive();
        map.setBounds(Layout.TABS);
        screen = new TerminalScreen(new DefaultVirtualTerminal(Layout.TERMINAL_SIZE));
        screen.startScreen();
    }

    private void draw() {
        board.clicks.clear();
        screen.clear();
        new MapView(Layout.MAP).draw(screen, player, board);
        map.draw(screen, player, board);
    }

    private int[] find(String text) {
        draw();
        for (int y = 0; y < Layout.TERMINAL_SIZE.getRows(); y++) {
            StringBuilder row = new StringBuilder();
            for (int x = 0; x < Layout.TERMINAL_SIZE.getColumns(); x++) {
                row.append(screen.getBackCharacter(x, y).getCharacterString());
            }
            int at = row.indexOf(text);
            if (at >= 0) {
                return new int[]{at, y};
            }
        }
        return null;
    }

    @Test
    void theNearestOfEachKindIsListed_andClickingOneIsAWhiteWaypoint() {
        Building bank = player.nearestBuilding(Building.Kind.BANK);
        assertTrue(bank.corner().x() > 0, "the nearer bank");
        assertNotNull(find("Points of interest"));
        assertNotNull(find("Church ("));
        assertNull(find("Gym ("), "no gym in this world");
        Position at = player.nearestTile(bank);
        int[] row = find("Bank (" + at.x() + "," + at.y() + "), " + player.stepsTo(at) + " steps");
        assertNotNull(row);

        assertTrue(board.clicks.click(Clicks.Button.LEFT, row[0], row[1]));
        assertEquals(bank, player.waypoints.building());
        row = find("Bank (");
        assertEquals(MapView.BUILDING_WAYPOINT_COLOR, screen.getBackCharacter(row[0], row[1]).getForegroundColor());
        assertNotNull(find("Bank at ("), "with your waypoints too");
        boolean white = false;
        for (int y = Layout.MAP.y(); y <= Layout.MAP.bottom(); y++) {
            white |= screen.getBackCharacter(Layout.MAP.right(), y).getForegroundColor() == MapView.BUILDING_WAYPOINT_COLOR;
        }
        assertTrue(white, "a white marker on the map's right edge");

        assertTrue(board.clicks.click(Clicks.Button.LEFT, row[0], row[1]));
        assertNull(player.waypoints.building(), "clicked again: cleared");
    }

    @Test
    void aBuildingWaypointIsSeparateFromNodeWaypoints_andClearOthersClearsIt() {
        player.waypoints.toggle(player.content.recipe(recipe.id), player);
        player.waypoints.toggle(player.nearestBuilding(Building.Kind.CHURCH), player);
        assertEquals(1, player.waypoints.nodes().size(), "both at once");
        assertNotNull(player.waypoints.building());
        assertTrue(player.waypoints.hasOthers(player.content.recipe(recipe.id)));
        player.waypoints.clearOthers(player.content.recipe(recipe.id));
        assertNull(player.waypoints.building());
        assertTrue(player.waypoints.has(player.content.recipe(recipe.id)), "its own stays");
        assertFalse(player.waypoints.hasOthers(player.content.recipe(recipe.id)));
    }
}
