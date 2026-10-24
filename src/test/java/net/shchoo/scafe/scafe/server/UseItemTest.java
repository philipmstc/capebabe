package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.GameLog;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.client.ui.GoalsMenu;
import net.shchoo.scafe.scafe.client.ui.InventoryPanel;
import net.shchoo.scafe.scafe.client.ui.Layout;
import net.shchoo.scafe.scafe.client.ui.MapMenu;
import net.shchoo.scafe.scafe.client.ui.SettingsMenu;
import net.shchoo.scafe.scafe.client.ui.SkillsMenu;
import net.shchoo.scafe.scafe.client.ui.StatsMenu;
import net.shchoo.scafe.scafe.client.ui.TabBar;
import net.shchoo.scafe.scafe.generation.WorldGenerator;
import net.shchoo.scafe.scafe.model.Node;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.model.Product;
import net.shchoo.scafe.scafe.model.Recipe;
import net.shchoo.scafe.scafe.model.SkillType;
import net.shchoo.scafe.scafe.model.World;
import net.shchoo.scafe.scafe.protocol.Intent;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class UseItemTest {
    private final List<String> log = new ArrayList<>();
    private final Consumer<String> listener = log::add;
    private GameServer server;
    private Player player;
    private Board board;
    private Recipe crafted;
    private Product unrelated;

    @BeforeEach
    void setUp() {
        GameLog.subscribe(listener);
        World world = new World(1, "w", 2);
        world.content.apply(WorldGenerator.newSkills(world, 6));
        crafted = world.content.skills.stream().filter(s -> s.type == SkillType.SECONDARY).findFirst().orElseThrow()
                .productLines.get(0).recipes.get(0);
        Recipe raw = world.content.skills.stream().filter(s -> s.type == SkillType.PRIMARY).findFirst().orElseThrow()
                .productLines.get(0).recipes.get(1);
        unrelated = raw.product;
        world.content.apply(WorldGenerator.newNode(world, new Position(3, 0), Node.UNLIMITED, Node.UNLIMITED, 0,
                r -> r.equals(crafted) ? 1 : 0));
        server = new GameServer(world);
        server.setDiscovery(Discovery.off());
        player = new Player(new LocalConnection(server.connect()));
        board = new Board(player.content);
        player.send(new Intent.Join("alice"));
        tick();
        GameCharacter alice = server.online(player.id);
        crafted.components.forEach((product, count) -> alice.changeInventory(product, count * 3));
        alice.changeInventory(unrelated, 1);
        tick();
    }

    @AfterEach
    void tearDown() {
        GameLog.unsubscribe(listener);
    }

    private void tick() {
        server.tick();
        player.receive();
    }

    private Product inventoryProduct(Product serverSide) {
        return player.content.product(serverSide.id);
    }

    @Test
    void pickAnItemAimAtANodeAndItGetsUsed() {
        TabBar tabs = new TabBar(Layout.TABS, new InventoryPanel(Layout.INVENTORY), new MapMenu(),
                List.of(new SkillsMenu(), new GoalsMenu(), new StatsMenu()), new SettingsMenu());
        tabs.onKey(new KeyStroke('i', false, false), player, board);
        tabs.onKey(new KeyStroke(KeyType.Enter), player, board);
        tabs.onKey(new KeyStroke(KeyType.ArrowDown), player, board);
        tabs.onKey(new KeyStroke(KeyType.ArrowDown), player, board);
        tabs.onKey(new KeyStroke(KeyType.ArrowDown), player, board);
        tabs.onKey(new KeyStroke(KeyType.Enter), player, board);

        MapMenu map = tabs.map();
        assertTrue(map.isFocused(), "over to the map");
        Product first = player.inventory.keySet().iterator().next();
        assertEquals(first, map.using());
        assertEquals(new Position(3, 0), map.cursor(player), "aimed at the nearest node that takes it");

        tabs.onKey(new KeyStroke(KeyType.Enter), player, board);
        assertFalse(map.isFocused(), "used: back out of the map");
        for (int i = 0; i < 4; i++) {
            tick();
        }
        assertEquals(new Position(2, 0), new Position(player.x, player.y), "walked over, next to it");
        assertEquals(new Position(3, 0), player.ahead(), "facing it");
        assertNotNull(player.currentAction, "and started on it");
        assertEquals(crafted.product.name, player.currentAction.label());
    }

    @Test
    void itHasToBeAComponentOfThatNode() {
        player.useItem(inventoryProduct(unrelated), new Position(3, 0));
        tick();
        assertTrue(log.contains("Nothing interesting happens."));
        assertNull(player.currentAction);
    }

    @Test
    void youHaveToHaveItAndThereHasToBeANode() {
        Product notHeld = player.content.product(crafted.product.id);
        player.useItem(notHeld, new Position(3, 0));
        tick();
        assertTrue(log.contains("You don't have that."));

        Product held = player.inventory.keySet().iterator().next();
        player.useItem(held, new Position(-4, -4));
        tick();
        assertTrue(log.contains("There's nothing there to use it on."));
    }

    @Test
    void steppingAwayCancelsAUseOnTheWay() {
        Product held = player.inventory.keySet().iterator().next();
        player.useItem(held, new Position(3, 0));
        tick();
        player.moveBy(0, 1);
        for (int i = 0; i < 5; i++) {
            tick();
        }
        assertNull(player.currentAction, "a step of your own cancels it");
    }
}
