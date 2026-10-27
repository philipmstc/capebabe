package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.client.ui.InventoryPanel;
import net.shchoo.scafe.scafe.client.ui.Layout;
import net.shchoo.scafe.scafe.generation.WorldGenerator;
import net.shchoo.scafe.scafe.model.Product;
import net.shchoo.scafe.scafe.model.Skill;
import net.shchoo.scafe.scafe.model.World;
import net.shchoo.scafe.scafe.protocol.Intent;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DropStackTest {
    private GameServer server;
    private Player player;
    private Board board;
    private GameCharacter alice;
    private Product item;
    private final InventoryPanel inventory = new InventoryPanel(Layout.INVENTORY);

    @BeforeEach
    void setUp() {
        World world = new World(1, "w", 1);
        world.content.apply(WorldGenerator.newSkills(world, 1));
        Skill skill = world.content.skills.get(0);
        item = skill.productLines.get(0).recipes.get(0).product;
        server = new GameServer(world);
        server.setDiscovery(Discovery.off());
        player = new Player(new LocalConnection(server.connect()));
        board = new Board(player.content);
        player.send(new Intent.Join("alice"));
        tick();
        alice = server.online(player.id);
        player.send(new Intent.Admin("5", java.util.List.of(skill.name, String.valueOf(Skill.XP_PER_LEVEL[3]))));
        tick();
        alice.changeInventory(item, 10);
        tick();
        inventory.setBounds(Layout.TABS);
        inventory.focus(player, board);
    }

    private void tick() {
        server.tick();
        player.receive();
    }

    private void press(KeyType type) {
        inventory.handleKey(new KeyStroke(type), player, board);
    }

    /** Enter on the selected stack, then down to Drop stack and Enter. */
    private void pickDrop() {
        press(KeyType.Enter);
        for (int i = 0; i < 4; i++) {
            press(KeyType.ArrowDown);
        }
        press(KeyType.Enter);
    }

    @Test
    void dropStackAsksFirst_thenTheStackIsGoneForGood() {
        assertEquals(4, inventory.selectedSlot(player).count(), "stacks of 4 at Lv. 4");
        pickDrop();
        assertTrue(inventory.popupOpen(), "asks");
        press(KeyType.Enter);
        assertFalse(inventory.popupOpen());
        tick();
        assertEquals(10, alice.inventory.get(item), "Cancel is picked to start with: nothing dropped");

        pickDrop();
        press(KeyType.ArrowUp);
        press(KeyType.Enter);
        tick();
        assertEquals(6, alice.inventory.get(item), "the selected stack of 4, gone");
        assertEquals(6, player.inventory.get(item));
        assertEquals(0, alice.bank.getOrDefault(item, 0), "not banked either");
    }

    @Test
    void theServerOnlyDropsWhatYouHave() {
        player.send(new Intent.Discard(item.id, 50));
        tick();
        assertEquals(0, alice.inventory.getOrDefault(item, 0));
        player.send(new Intent.Discard(item.id, 1));
        tick();
        assertEquals(0, alice.inventory.getOrDefault(item, 0));
    }
}
