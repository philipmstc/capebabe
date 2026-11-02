package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.model.WorldContent;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.KeyStroke;
import net.shchoo.scafe.scafe.client.ui.StationView;
import net.shchoo.scafe.scafe.model.Product;
import net.shchoo.scafe.scafe.model.View;
import net.shchoo.scafe.scafe.model.Stations;
import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.GameLog;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.client.ui.Layout;
import net.shchoo.scafe.scafe.client.ui.MapView;
import net.shchoo.scafe.scafe.generation.WorldGenerator;
import net.shchoo.scafe.scafe.model.Building;
import net.shchoo.scafe.scafe.model.Direction;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.model.World;
import net.shchoo.scafe.scafe.protocol.Intent;

import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class StationTest {
    @Test
    void stationsThatOnlyHaveEachOtherGetALineToTheRest() {
        World world = new World(1, "w", 7);
        GameServer server = new GameServer(world);
        for (Position at : List.of(new Position(100, 100), new Position(160, 100), new Position(220, 100),
                new Position(700, 100), new Position(760, 100), new Position(820, 100))) {
            server.applyContent(WorldGenerator.newBuildingAt(world, Building.Kind.STATION, at, 6, p -> false));
        }
        List<Building> line = Stations.all(world.content);
        assertEquals(6, line.size());
        assertTrue(Stations.connected(world.content, line.get(2), line.get(3)), "the shortest way across");
        assertFalse(Stations.connected(world.content, line.get(0), line.get(5)), "and only that one");
        java.util.Set<Building> reached = new java.util.HashSet<>(List.of(line.get(0)));
        for (int step = 0; step < line.size(); step++) {
            for (Building station : List.copyOf(reached)) {
                reached.addAll(Stations.destinations(world.content, station));
            }
        }
        assertEquals(6, reached.size(), "every station can get to every other");
    }

    private final List<String> log = new ArrayList<>();
    private final Consumer<String> listener = log::add;
    private GameServer server;
    private Player player;
    private Building station;

    @BeforeEach
    void setUp() {
        GameLog.subscribe(listener);
        server = new GameServer(new World(1, "w", 7));
        server.setDiscovery(Discovery.off());
        server.ensureBuildings();
        station = server.world.content.buildings().stream().filter(b -> b.kind() == Building.Kind.STATION).findFirst().orElseThrow();
        player = new Player(new LocalConnection(server.connect()));
        player.send(new Intent.Join("alice"));
        server.tick();
        player.receive();
    }

    @AfterEach
    void tearDown() {
        GameLog.unsubscribe(listener);
    }

    @Test
    void aStationWithItsTrackAwayFromTheOrigin() {
        assertEquals(Building.Kind.values().length, server.world.content.buildings().size(), "one of each kind");
        assertEquals(17, station.kind().width());
        assertEquals(16 + 3 * 13, station.tiles().size(), "the 4 x 4 station and 3 rows of track");
        for (Position tile : station.tiles()) {
            assertTrue(Math.max(Math.abs(tile.x()), Math.abs(tile.y())) > WorldGenerator.BUILDING_CLEAR_RADIUS, tile.toString());
            boolean track = tile.x() - station.corner().x() >= 4;
            assertEquals(track, server.passable(tile), (track ? "walk along the track: " : "not through the station: ") + tile);
            assertTrue(server.taken(tile), "nothing new goes on any of it, track included");
        }
        assertEquals(16, station.solidTiles().size(), "the station itself");
        assertTrue(server.passable(new Position(station.corner().x() + 4, station.corner().y())), "open above the track");
        for (Building other : server.world.content.buildings()) {
            if (other != station) {
                assertTrue(other.tiles().stream().noneMatch(t -> station.within(t, 1)), "not touching the " + other.kind().label);
            }
        }
    }

    @Test
    void itLooksLikeTheSketch() throws Exception {
        player.x = station.corner().x() + 8;
        player.y = station.corner().y() + 8;
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(Layout.TERMINAL_SIZE));
        screen.startScreen();
        new MapView(Layout.MAP).draw(screen, player, new Board(player.content));
        int x = Layout.tileScreenX(station.corner().x(), player.x), y = Layout.tileScreenY(station.corner().y(), player.y);
        String[] expected = {
                "\u250c\u2500\u2500\u2500\u2500\u2500\u2500\u2510\u2022 \u2022 \u2022",
                "\u2502TRAINS\u2502\u2550\u2550\u2550\u2550\u2550",
                "\u2502TATION\u2502\u2502\u2502\u2502\u2502\u2502",
                "\u2514\u2500\u2500[ ]\u2500\u2518\u2550\u2550\u2550\u2550\u2550"};
        for (int row = 0; row < expected.length; row++) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < expected[row].length(); i++) {
                line.append(screen.getBackCharacter(x + i, y + row).getCharacterString());
            }
            assertEquals(expected[row], line.toString(), "row " + row);
        }
    }

    /** Just below the door, facing it. */
    private void standAtTheDoorOf(Building building) {
        GameCharacter alice = server.online(player.id);
        alice.moveTo(building.platform());
        alice.face(Direction.UP);
        server.tick();
        player.receive();
        player.send(new Intent.Move(0, -1));
        server.tick();
        player.receive();
    }

    private void ticks(int n) {
        for (int i = 0; i < n; i++) {
            server.tick();
            player.receive();
        }
    }

    /** Alice, somewhere far off facing {@code facing}, comes across a station. */
    private Building discoverFrom(Position at, Direction facing) {
        GameCharacter alice = server.online(player.id);
        alice.moveTo(at);
        alice.face(facing);
        server.discoverStation(alice);
        ticks(1);
        List<Building> line = Stations.all(server.world.content);
        return line.get(line.size() - 1);
    }

    @Test
    void qOpensTheMenuStraightAway() {
        standAtTheDoorOf(station);
        player.perform();
        assertTrue(player.stationMenuOpen, "before the server's tick");
        assertFalse(player.bankOpen);
    }

    @Test
    void farFromEveryStationYouMightComeAcrossOne() {
        Position near = station.platform();
        assertFalse(Discovery.farFromStations(server.world, near));
        assertTrue(Discovery.farFromStations(server.world,
                new Position(near.x() + Discovery.STATION_LONELY_DISTANCE + 20, near.y())));
    }

    @Test
    void aFoundStationAppearsJustInsideYourViewAhead() {
        Position at = new Position(200, 40);
        Building found = discoverFrom(at, Direction.RIGHT);
        assertEquals(2, Stations.all(server.world.content).size());
        long visible = found.tiles().stream().filter(t -> Math.abs(t.x() - at.x()) <= View.HALF_WIDTH
                && Math.abs(t.y() - at.y()) <= View.HALF_HEIGHT).map(Position::x).distinct().count();
        assertTrue(visible >= 3 && visible <= 6, "just the near end on screen: " + visible + " columns");
        assertTrue(found.corner().x() > at.x(), "ahead of you");
        assertTrue(log.stream().anyMatch(m -> m.startsWith("You spot a train station ahead: tation1")), log.toString());

        Position up = new Position(-300, 90);
        Building above = discoverFrom(up, Direction.UP);
        assertTrue(above.tiles().stream().allMatch(t -> t.y() < up.y() && t.y() >= up.y() - View.HALF_HEIGHT), "in view, above");
        assertEquals(up.y() - View.HALF_HEIGHT, above.corner().y(), "at the top edge");
    }

    @Test
    void theMenuListsEveryStationAndTheFareInTheSkillsColour() throws Exception {
        player.send(new Intent.Admin("1", List.of("2")));
        ticks(1);
        discoverFrom(new Position(200, 40), Direction.RIGHT);
        standAtTheDoorOf(station);
        player.perform();
        StationView view = new StationView();
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(Layout.TERMINAL_SIZE));
        screen.startScreen();
        view.drawOnTop(screen, player, new Board(player.content));
        String shown = text(screen);
        assertTrue(shown.contains("Choose destination"));
        Position home = station.platform();
        assertTrue(shown.contains("tation0 (" + home.x() + ", " + home.y() + ")"));
        assertTrue(shown.contains("you're here"));
        assertTrue(shown.contains("> tation1 ("), "starts on the first one that isn't here:\n" + shown);
        assertEquals("skill1", Stations.fareSkill(server.world.content, 1).name, "station N: any of skill N's items");
        assertTrue(shown.contains("1 x any skill1"), shown);
    }

    @Test
    void anyTierOfTheFareSkillPays_theCheapestHeldGoes() {
        player.send(new Intent.Admin("1", List.of("2")));
        ticks(1);
        Building far = discoverFrom(new Position(200, 40), Direction.RIGHT);
        standAtTheDoorOf(station);
        var line = Stations.fareSkill(server.world.content, 1).productLines.get(0).recipes;
        Product tier1 = line.get(1).product, tier3 = line.get(3).product;
        server.online(player.id).changeInventory(tier3, 1);
        server.online(player.id).changeInventory(tier1, 1);
        ticks(1);

        player.rideTo(far.id());
        ticks(1);
        assertEquals("riding the train", player.currentAction.label(), "a tier 1 pays as well as a tier 0");
        assertEquals(0, server.online(player.id).inventory.getOrDefault(tier1, 0), "the cheaper one went");
        assertEquals(1, server.online(player.id).inventory.get(tier3));

        // a prestige version pays too, but a plain one of the same tier goes first
        Product prestige = new Product(9999, tier1.name + "+1", tier1.tier, tier1, 1);
        assertEquals(tier1, Stations.fareFrom(server.world.content, 1, java.util.Map.of(prestige, 1, tier1, 1)));
        assertEquals(prestige, Stations.fareFrom(server.world.content, 1, java.util.Map.of(prestige, 1, tier3, 1)));
        assertNull(Stations.fareFrom(server.world.content, 1, java.util.Map.of(tier1, 0)), "none held");
    }

    @Test
    void ridingCostsTheFareAndTakesYouThere() {
        player.send(new Intent.Admin("1", List.of("2")));
        ticks(1);
        Building far = discoverFrom(new Position(200, 40), Direction.RIGHT);
        standAtTheDoorOf(station);
        Product fare = Stations.fareSkill(server.world.content, 1).productLines.get(0).recipes.get(0).product;

        player.rideTo(far.id());
        ticks(1);
        assertTrue(log.contains("The fare to tation1 is 1 of any skill1 item."), log.toString());
        assertNull(player.currentAction, "no fare, no ride");

        server.online(player.id).changeInventory(fare, 2);
        player.perform();
        StationView view = new StationView();
        Board board = new Board(player.content);
        assertTrue(view.onKey(new KeyStroke('k', false, false), player, board), "the menu takes every key but wasd");
        view.onKey(new KeyStroke(KeyType.Enter), player, board);
        assertFalse(player.stationMenuOpen);
        ticks(1);
        assertEquals("riding the train", player.currentAction.label());
        assertEquals(1, player.inventory.get(player.content.product(fare.id)), "one taken");
        assertTrue(log.contains("All aboard for tation1! The train pulls out..."));
        Position before = player.position();
        player.moveBy(1, 0);
        ticks(1);
        assertEquals(before, player.position(), "no getting off a moving train");
        ticks(GameServer.TRAIN_TICKS);
        assertEquals(far.platform(), player.position());
        assertTrue(log.contains("The train pulls in at tation1."));
    }

    @Test
    void notToWhereYouAreAndOnlyAtAStation() {
        player.rideTo(station.id());
        ticks(1);
        assertTrue(log.contains("You need to be at a station."));
        standAtTheDoorOf(station);
        player.rideTo(station.id());
        ticks(1);
        assertTrue(log.contains("You're already here."));
    }

    @Test
    void loggingOutOnTheTrainLeavesYouOnThePlatform() {
        Building far = discoverFrom(new Position(200, 40), Direction.RIGHT);
        standAtTheDoorOf(station);
        GameCharacter alice = server.online(player.id);
        player.rideTo(far.id());
        server.tick();
        assertTrue(alice.isRiding(), "free while there are no skills");
        player.send(new Intent.Leave());
        ticks(GameServer.TRAIN_TICKS + 1);
        assertFalse(alice.isRiding(), "the ride's off");
        assertEquals(station.platform(), alice.position(), "you never left the platform");
    }

    @Test
    void theTrackIsBrownRailsAndGreyTies() throws Exception {
        player.x = station.corner().x() + 8;
        player.y = station.corner().y() + 8;
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(Layout.TERMINAL_SIZE));
        screen.startScreen();
        new MapView(Layout.MAP).draw(screen, player, new Board(player.content));
        int x = Layout.tileScreenX(station.corner().x() + 6, player.x), y = Layout.tileScreenY(station.corner().y(), player.y);
        for (int dx = 0; dx < 2; dx++) {
            assertEquals(TextColor.ANSI.YELLOW, screen.getBackCharacter(x + dx, y + 1).getForegroundColor(), "top rail, tile and gap");
            assertEquals(TextColor.ANSI.WHITE, screen.getBackCharacter(x + dx, y + 2).getForegroundColor(), "ties");
            assertEquals(TextColor.ANSI.YELLOW, screen.getBackCharacter(x + dx, y + 3).getForegroundColor(), "bottom rail");
        }
        int box = Layout.tileScreenX(station.corner().x(), player.x);
        assertEquals(TextColor.ANSI.YELLOW_BRIGHT, screen.getBackCharacter(box + 1, y + 1).getForegroundColor(), "the station stays yellow");
    }

    @Test
    void standingOnTheTrackShowsYouAndTheRailsEitherSide() throws Exception {
        Position onTrack = new Position(station.corner().x() + 8, station.corner().y() + 2);
        player.x = onTrack.x();
        player.y = onTrack.y();
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(Layout.TERMINAL_SIZE));
        screen.startScreen();
        new MapView(Layout.MAP).draw(screen, player, new Board(player.content));
        int x = Layout.tileScreenX(onTrack.x(), player.x), y = Layout.tileScreenY(onTrack.y(), player.y);
        assertEquals(String.valueOf(player.facing.arrow), screen.getBackCharacter(x, y).getCharacterString());
        assertEquals("\u2502", screen.getBackCharacter(x - 1, y).getCharacterString());
        assertEquals("\u2502", screen.getBackCharacter(x + 1, y).getCharacterString());
    }

    @Test
    void qOnTheTrackIsntTheStation() {
        GameCharacter alice = server.online(player.id);
        alice.moveTo(new Position(station.corner().x() + 8, station.corner().y() + 4));
        alice.face(Direction.UP);
        ticks(1);
        player.send(new Intent.Move(0, -1));
        ticks(1);
        assertEquals(new Position(station.corner().x() + 8, station.corner().y() + 3), player.position(), "stepped onto the rail");
        player.perform();
        assertFalse(player.stationMenuOpen, "the track isn't the station");
    }

    @Test
    void newNodesKeepOffTheTrack() {
        player.send(new Intent.Admin("1", List.of("1")));
        ticks(1);
        Position middle = new Position(station.corner().x() + 10, station.corner().y() + 2);
        java.util.Random random = new java.util.Random(3);
        for (int i = 0; i < 300; i++) {
            Position spot = Discovery.nodeSpot(server, middle, 1, 4, random);
            if (spot != null) {
                assertFalse(station.within(spot, 1), "on or beside the track: " + spot);
            }
        }
    }

    /** Four more stations round about, so the line has a shape. */
    private List<Building> aLine() {
        for (Position at : List.of(new Position(200, 40), new Position(-150, 300), new Position(90, -400), new Position(260, 120))) {
            discoverFrom(at, Direction.RIGHT);
        }
        return Stations.all(server.world.content);
    }

    @Test
    void linesGoToEachStationsTwoNearestBothWays() {
        List<Building> line = aLine();
        WorldContent content = server.world.content;
        assertEquals(List.of(line.get(1), line.get(4)), Stations.neighbours(content, station), "the origin's two nearest");
        assertEquals(List.of(line.get(0), line.get(1)), Stations.neighbours(content, line.get(3)), "far-off 3's two nearest");
        assertTrue(Stations.connected(content, station, line.get(3)), "3 has a line to 0, so 0 has one back");
        for (Building a : line) {
            assertTrue(Stations.destinations(content, a).size() >= Stations.NEIGHBOURS, "nobody's stranded");
            for (Building b : line) {
                assertEquals(Stations.connected(content, a, b), Stations.connected(content, b, a), "both ways");
            }
        }
        assertEquals(List.of(line.get(0), line.get(1)), Stations.destinations(content, line.get(2)),
                "2's lines: its two nearest, and nobody else has it as theirs");
    }

    @Test
    void youCanOnlyRideWhereThereIsALine() {
        List<Building> line = aLine();
        Building two = line.get(2);
        standAtTheDoorOf(two);
        player.rideTo(line.get(3).id());
        ticks(1);
        assertTrue(log.contains("There's no direct line from tation2 to tation3."), log.toString());
        assertNull(player.currentAction);

        player.perform();
        StationView view = new StationView();
        Board board = new Board(player.content);
        view.onKey(new KeyStroke('x', false, false), player, board);
        assertEquals(0, view.selectedIndex(), "starts on the first one with a line from here");
        view.onKey(new KeyStroke(KeyType.ArrowDown), player, board);
        assertEquals(1, view.selectedIndex());
        view.onKey(new KeyStroke(KeyType.ArrowDown), player, board);
        assertEquals(1, view.selectedIndex(), "2 is here, 3 and 4 have no line from here");
    }

    @Test
    void theLineMapShowsTheStationsAndTheirLinesOnTheRight() throws Exception {
        aLine();
        standAtTheDoorOf(station);
        player.perform();
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(Layout.TERMINAL_SIZE));
        screen.startScreen();
        new StationView().drawOnTop(screen, player, new Board(player.content));
        StringBuilder panel = new StringBuilder();
        for (int y = Layout.TABS.y(); y <= Layout.TABS.bottom(); y++) {
            for (int x = Layout.TABS.x(); x <= Layout.TABS.right(); x++) {
                panel.append(screen.getBackCharacter(x, y).getCharacterString());
            }
            panel.append('\n');
        }
        String shown = panel.toString();
        assertTrue(shown.contains("Line map"), shown);
        for (char n : "01234".toCharArray()) {
            assertTrue(shown.indexOf(n) >= 0, "station " + n + " on the map:\n" + shown);
        }
        assertTrue(shown.chars().anyMatch(c -> c == '\u2500') && shown.chars().anyMatch(c -> c == '\u2502'), "with lines");
        assertTrue(shown.chars().noneMatch(c -> c == '/' || c == '\\'), "only straight lines and corners, no diagonals");
        assertTrue(shown.contains("you're at 0"));
    }

    private static String text(Screen screen) {
        StringBuilder text = new StringBuilder();
        for (int y = 0; y < Layout.TERMINAL_SIZE.getRows(); y++) {
            for (int x = 0; x < Layout.TERMINAL_SIZE.getColumns(); x++) {
                text.append(screen.getBackCharacter(x, y).getCharacterString());
            }
            text.append('\n');
        }
        return text.toString();
    }
}
