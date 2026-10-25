package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.GameLog;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.client.ui.ChurchView;
import net.shchoo.scafe.scafe.client.ui.Layout;
import net.shchoo.scafe.scafe.client.ui.MapView;
import net.shchoo.scafe.scafe.generation.WorldGenerator;
import net.shchoo.scafe.scafe.model.Building;
import net.shchoo.scafe.scafe.model.Direction;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.model.Node;
import net.shchoo.scafe.scafe.model.Prayer;
import net.shchoo.scafe.scafe.model.Product;
import net.shchoo.scafe.scafe.model.Recipe;
import net.shchoo.scafe.scafe.model.World;
import net.shchoo.scafe.scafe.persistence.AccountStore;
import net.shchoo.scafe.scafe.persistence.WorldStore;
import net.shchoo.scafe.scafe.protocol.Intent;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ChurchTest {
    private final List<String> log = new ArrayList<>();
    private final Consumer<String> listener = log::add;
    private GameServer server;
    private Player player;
    private GameCharacter alice;
    private Building church;

    @BeforeEach
    void setUp() {
        GameLog.subscribe(listener);
        World world = new World(1, "w", 7);
        server = new GameServer(world);
        server.setDiscovery(Discovery.off());
        server.ensureBuildings();
        church = world.content.buildings().stream().filter(b -> b.kind() == Building.Kind.CHURCH).findFirst().orElseThrow();
        player = new Player(new LocalConnection(server.connect()));
        player.send(new Intent.Join("alice"));
        tick();
        alice = server.online(player.id);
    }

    @AfterEach
    void tearDown() {
        GameLog.unsubscribe(listener);
    }

    private void tick() {
        server.tick();
        player.receive();
    }

    @Test
    void aChurchNearTheOriginWithTheSameRulesAsTheBank() {
        Building bank = server.world.content.buildings().stream().filter(b -> b.kind() == Building.Kind.BANK).findFirst().orElseThrow();
        assertEquals(10, church.tiles().size(), "3 x 3, and the cross on top");
        for (Position tile : church.tiles()) {
            int away = Math.max(Math.abs(tile.x()), Math.abs(tile.y()));
            assertTrue(away > WorldGenerator.BUILDING_CLEAR_RADIUS && away <= WorldGenerator.BUILDING_MAX_RADIUS, tile.toString());
            assertFalse(server.passable(tile));
            assertFalse(bank.within(tile, 1), "never touching the bank");
        }
        Position besideTheCross = new Position(church.corner().x(), church.corner().y());
        assertTrue(server.passable(besideTheCross), "the cross stands on its own");
        server.ensureBuildings();
        assertEquals(Building.Kind.values().length, server.world.content.buildings().size(), "one of each");
    }

    @Test
    void itLooksLikeAChurch() throws IOException {
        player.x = church.corner().x() + 1;
        player.y = church.corner().y() + 6;
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(Layout.TERMINAL_SIZE));
        screen.startScreen();
        new MapView(Layout.MAP).draw(screen, player, new Board(player.content));
        int x = Layout.tileScreenX(church.corner().x(), player.x), y = Layout.tileScreenY(church.corner().y(), player.y);
        String[] expected = {"\u2022 \u253c \u2022", "\u2554\u2500\u2567\u2500\u2557", "\u2502CHU\u2502", "\u2502RCH\u2502"};
        for (int row = 0; row < expected.length; row++) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < 5; i++) {
                line.append(screen.getBackCharacter(x + i, y + row).getCharacterString());
            }
            assertEquals(expected[row], line.toString(), "row " + row);
        }
        assertEquals(TextColor.ANSI.YELLOW_BRIGHT, screen.getBackCharacter(x + 1, y + 2).getForegroundColor(), "the C in a gap");
    }

    @Test
    void hpDoesntComeBackByItself() {
        alice.hp = 3;
        for (int i = 0; i < 50; i++) {
            tick();
        }
        assertEquals(3, alice.hp);
    }

    /** Beside the church, facing it. */
    private void atTheChurch() {
        alice.moveTo(new Position(church.corner().x() + 1, church.corner().y() + church.kind().height()));
        alice.face(Direction.UP);
        tick();
        player.send(new Intent.Move(0, -1));
        tick();
    }

    private final ChurchView view = new ChurchView();

    private void press(KeyType type) {
        assertTrue(view.onKey(new KeyStroke(type), player, new Board(player.content)), "the window takes it");
    }

    private Recipe recipe(int tier) {
        return server.world.content.craftingSkills().get(0).productLines.get(0).recipes.get(tier);
    }

    private void withSkills() {
        server.applyContent(WorldGenerator.newSkills(server.world, 2));
        tick();
    }

    @Test
    void qOpensTheChurchWindowAndHealHealsYou() {
        alice.hp = 3;
        atTheChurch();
        player.perform();
        tick();
        assertTrue(player.churchMenuOpen);
        assertEquals(3, alice.hp, "q alone doesn't heal any more: it's in the window");
        press(KeyType.Enter);
        tick();
        assertFalse(player.churchMenuOpen);
        assertEquals(alice.maxHp(), alice.hp);
        assertEquals(alice.maxHp(), player.hp);
        assertTrue(log.stream().anyMatch(m -> m.startsWith("You pray, and feel restored")));

        player.heal();
        tick();
        assertTrue(log.contains("You're already at full health."));
    }

    @Test
    void prayingForATierCostsAHundredATierOfTheTierBelow() {
        withSkills();
        Recipe tier2 = recipe(2);
        Product tier1 = recipe(1).product;
        alice.changeInventory(tier1, 250);
        atTheChurch();
        player.perform();
        tick();
        press(KeyType.ArrowDown);
        press(KeyType.Enter);
        press(KeyType.Enter);
        assertEquals(ChurchView.Step.RECIPE, view.step());
        press(KeyType.ArrowDown);
        press(KeyType.ArrowDown);
        press(KeyType.Enter);
        assertEquals(ChurchView.Step.PAY, view.step());
        press(KeyType.Enter);
        tick();
        assertEquals(50, alice.inventory.get(tier1), "200 of tier 1 for tier 2");
        assertEquals(tier2, alice.blessing);
        assertEquals(Prayer.STEPS, alice.blessingLeft);
        assertEquals(tier2.id, player.blessing.id, "the client knows");
        assertFalse(player.churchMenuOpen);
    }

    @Test
    void anyOneItemAThousandOfIt_theOnlyWayForTierZero() {
        withSkills();
        Product any = recipe(3).product;
        alice.changeInventory(any, 999);
        atTheChurch();
        player.pray(player.content.recipe(recipe(0).id), player.content.product(any.id));
        tick();
        assertNull(alice.blessing, "999 isn't enough");
        assertTrue(log.contains("Praying for that takes 1000 x " + any.name + "."));
        alice.changeInventory(any, 1);
        player.pray(player.content.recipe(recipe(0).id), player.content.product(any.id));
        tick();
        assertEquals(recipe(0), alice.blessing);
        assertEquals(0, alice.inventory.get(any));
    }

    @Test
    void onlyAtAChurch() {
        withSkills();
        alice.changeInventory(recipe(0).product, 1000);
        player.pray(player.content.recipe(recipe(1).id), player.content.product(recipe(0).product.id));
        player.heal();
        tick();
        assertNull(alice.blessing);
        assertEquals(2, log.stream().filter("You need to be at a church."::equals).count());
    }

    @Test
    void theBlessingRunsOutAfterItsSteps() {
        withSkills();
        alice.setBlessing(recipe(1), 3);
        tick();
        for (int i = 0; i < 3; i++) {
            player.send(new Intent.Move(0, 1));
            tick();
        }
        assertNull(alice.blessing);
        assertNull(player.blessing);
        assertTrue(log.contains("Your blessing fades."));
    }

    @Test
    void blessedANodeIsTwiceAsLikelyAndTheNextIsWhatYouPrayedFor() {
        withSkills();
        // every roll comes up 0.15: past a step's 0.10 node chance, inside a blessed 0.20
        server.setDiscovery(new Discovery(new Random(1) {
            @Override
            public double nextDouble() {
                return 0.15;
            }
        }));
        alice.moveTo(new Position(40, 40));
        tick();
        int nodes = server.world.content.nodes().size();
        player.send(new Intent.Move(0, 1));
        tick();
        assertEquals(nodes, server.world.content.nodes().size(), "unblessed: nothing");

        alice.setBlessing(recipe(3), Prayer.STEPS);
        player.send(new Intent.Move(0, 1));
        tick();
        assertEquals(nodes + 1, server.world.content.nodes().size(), "blessed: a node");
        Node found = server.world.content.nodes().stream().max(java.util.Comparator.comparingInt(n -> n.id)).orElseThrow();
        assertEquals(recipe(3).id, found.recipe.id, "the one prayed for");
        assertNull(alice.blessing, "and that's the prayer answered");
        assertTrue(log.stream().anyMatch(m -> m.endsWith("Your prayer is answered.")));
    }

    @Test
    void theBlessingIsSaved(@TempDir Path dir) throws Exception {
        try (AccountStore accounts = new AccountStore(dir.resolve("accounts.db"));
             WorldStore store = new WorldStore(dir.resolve("world.db"), accounts)) {
            GameServer saved = new GameServer(store.loadOrCreateWorld(1, "w", 3), store);
            saved.applyContent(WorldGenerator.newSkills(saved.world, 1));
            Player bob = new Player(new LocalConnection(saved.connect()));
            bob.send(new Intent.Join("bob"));
            saved.tick();
            bob.receive();
            Recipe prayed = saved.world.content.craftingSkills().get(0).productLines.get(0).recipes.get(2);
            saved.online(bob.id).setBlessing(prayed, 42);
            bob.send(new Intent.Leave());
            saved.tick();
            Player again = new Player(new LocalConnection(saved.connect()));
            again.send(new Intent.Join("bob"));
            saved.tick();
            again.receive();
            assertEquals(prayed.id, again.blessing.id);
            assertEquals(42, again.blessingLeft);
        }
    }

    @Test
    void loggingOutAndBackInDoesntHeal(@TempDir Path dir) throws Exception {
        try (AccountStore accounts = new AccountStore(dir.resolve("accounts.db"));
             WorldStore store = new WorldStore(dir.resolve("world.db"), accounts)) {
            GameServer saved = new GameServer(store.loadOrCreateWorld(1, "w", 3), store);
            Player bob = new Player(new LocalConnection(saved.connect()));
            bob.send(new Intent.Join("bob"));
            saved.tick();
            bob.receive();
            saved.online(bob.id).hp = 5;
            bob.send(new Intent.Leave());
            saved.tick();
            assertEquals(5, store.loadOrCreateCharacter("bob").savedHp, "saved");
            Player again = new Player(new LocalConnection(saved.connect()));
            again.send(new Intent.Join("bob"));
            saved.tick();
            again.receive();
            assertEquals(5, again.hp, "and still 5 when you're back");
        }
    }
}
