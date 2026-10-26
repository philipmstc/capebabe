package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.GameLog;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.client.ui.MerchantView;
import net.shchoo.scafe.scafe.generation.WorldGenerator;
import net.shchoo.scafe.scafe.model.Building;
import net.shchoo.scafe.scafe.model.Direction;
import net.shchoo.scafe.scafe.model.Levels;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.model.Product;
import net.shchoo.scafe.scafe.model.Skill;
import net.shchoo.scafe.scafe.model.World;
import net.shchoo.scafe.scafe.persistence.AccountStore;
import net.shchoo.scafe.scafe.persistence.WorldStore;
import net.shchoo.scafe.scafe.protocol.Intent;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class MerchantTest {
    private final List<String> log = new ArrayList<>();
    private final Consumer<String> listener = log::add;
    private GameServer server;
    private Player player;
    private GameCharacter alice;
    private Building merchant;
    private Skill skill;

    @BeforeEach
    void setUp() {
        GameLog.subscribe(listener);
        World world = new World(1, "w", 7);
        server = new GameServer(world);
        server.setDiscovery(Discovery.off());
        server.ensureBuildings();
        server.applyContent(WorldGenerator.newSkills(world, 1));
        merchant = world.content.buildings().stream().filter(b -> b.kind() == Building.Kind.MERCHANT).findFirst().orElseThrow();
        skill = world.content.craftingSkills().get(0);
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

    private void prestige() {
        player.send(new Intent.Admin("5", List.of(skill.name, String.valueOf(Skill.XP_PER_LEVEL[Levels.MAX_LEVEL - 1]))));
        tick();
        player.prestige(player.content.skill(skill.id));
        tick();
    }

    /** Beside the stall, facing it. */
    private void atTheMerchant() {
        for (Position tile : merchant.tiles()) {
            Position below = new Position(tile.x(), tile.y() + 1);
            if (server.world.content.solidBuildingAt(tile) != null && server.passable(below)
                    && server.world.content.solidBuildingAt(below) == null) {
                alice.moveTo(below);
                alice.face(Direction.UP);
                tick();
                player.send(new Intent.Move(0, -1));
                tick();
                return;
            }
        }
        fail("nowhere to stand");
    }

    private Product base() {
        return skill.productLines.get(0).recipes.get(1).product;
    }

    @Test
    void theFirstPrestigeCallsYouOverOnce() {
        assertFalse(player.metMerchant);
        prestige();
        assertTrue(alice.metMerchant);
        assertTrue(player.metMerchant);
        assertTrue(player.merchantCalling, "the popup");
        player.merchantCalling = false;
        prestige();
        assertEquals(2, alice.prestige.get(skill));
        assertFalse(player.merchantCalling, "only the first time");
    }

    @Test
    void thePopupOffersAWalkThere() {
        prestige();
        MerchantView view = new MerchantView();
        Board board = new Board(player.content);
        assertTrue(view.onKey(new KeyStroke(KeyType.Enter), player, board), "the popup has the keys");
        assertFalse(player.merchantCalling);
        assertNotNull(player.walkTarget, "off to the ant");
    }

    @Test
    void itTradesAPrestigeItemFor1PlusItsPrestigeOfThePlainOne() {
        Product plus = server.prestigeProduct(base(), 3);
        alice.metMerchant = true;
        alice.changeInventory(plus, 5);
        atTheMerchant();
        tick();
        player.trade(player.content.product(plus.id), 2);
        tick();
        assertEquals(3, alice.inventory.get(plus));
        assertEquals(8, alice.inventory.get(base()), "4 each");
        assertTrue(log.contains("The Merch Ant takes 2 x " + plus.name + " and gives you 8 x " + base().name + "."));
    }

    @Test
    void throughItsWindow() {
        Product plus = server.prestigeProduct(base(), 1);
        alice.metMerchant = true;
        alice.changeInventory(plus, 20);
        player.metMerchant = true;
        atTheMerchant();
        tick();
        player.perform();
        tick();
        assertTrue(player.merchantMenuOpen);
        MerchantView view = new MerchantView();
        Board board = new Board(player.content);
        view.onKey(new KeyStroke(KeyType.Enter), player, board);
        assertEquals(MerchantView.Step.AMOUNT, view.step());
        view.onKey(new KeyStroke(KeyType.ArrowDown), player, board);
        view.onKey(new KeyStroke(KeyType.Enter), player, board);
        tick();
        assertEquals(10, alice.inventory.get(plus), "10 of them");
        assertEquals(20, alice.inventory.get(base()), "2 each");
        assertEquals(MerchantView.Step.ITEM, view.step(), "back to the list");
    }

    @Test
    void notBeforeItKnowsYouNorForPlainItems() {
        alice.changeInventory(base(), 5);
        atTheMerchant();
        player.perform();
        tick();
        assertFalse(player.merchantMenuOpen);
        assertTrue(log.contains("The ant waves its antennae: not open for business yet."));
        alice.metMerchant = true;
        player.trade(player.content.product(base().id), 5);
        tick();
        assertTrue(log.contains("The Merch Ant only deals in prestige items."));
        assertEquals(5, alice.inventory.get(base()));
    }

    @Test
    void prestigedBeforeThisUpdate_itCallsWhenYouLogIn(@TempDir Path dir) throws Exception {
        try (AccountStore accounts = new AccountStore(dir.resolve("accounts.db"));
             WorldStore store = new WorldStore(dir.resolve("world.db"), accounts)) {
            GameServer saved = new GameServer(store.loadOrCreateWorld(1, "w", 3), store);
            saved.applyContent(WorldGenerator.newSkills(saved.world, 1));
            Skill s = saved.world.content.craftingSkills().get(0);
            GameCharacter bob = store.loadOrCreateCharacter("bob");
            bob.prestige.put(s, 1);
            store.saveCharacter(bob);

            Player again = new Player(new LocalConnection(saved.connect()));
            again.send(new Intent.Join("bob"));
            saved.tick();
            again.receive();
            assertTrue(again.merchantCalling, "an old prestige: the ant asks for you now");
            again.send(new Intent.Leave());
            saved.tick();

            Player third = new Player(new LocalConnection(saved.connect()));
            third.send(new Intent.Join("bob"));
            saved.tick();
            third.receive();
            assertTrue(third.metMerchant, "saved");
            assertFalse(third.merchantCalling, "and not again");
        }
    }
}
