package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.client.ui.InventoryPanel;
import net.shchoo.scafe.scafe.client.ui.Layout;
import net.shchoo.scafe.scafe.generation.WorldGenerator;
import net.shchoo.scafe.scafe.model.EquipSlot;
import net.shchoo.scafe.scafe.model.Product;
import net.shchoo.scafe.scafe.model.World;
import net.shchoo.scafe.scafe.protocol.Intent;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TrophyEquipTest {
    private GameServer server;
    private Player player;
    private GameCharacter alice;
    private Product drop;

    @BeforeEach
    void setUp() {
        World world = new World(1, "w", 1);
        world.content.apply(WorldGenerator.newMonsterType(world));
        world.content.apply(WorldGenerator.newDrop(world, world.content.monsters.get(0).id));
        drop = world.content.monsters.get(0).drops.get(0).product();
        server = new GameServer(world);
        server.setDiscovery(Discovery.off());
        player = new Player(new LocalConnection(server.connect()));
        player.send(new Intent.Join("alice"));
        tick();
        alice = server.online(player.id);
        alice.changeInventory(drop, 1);
        tick();
    }

    private void tick() {
        server.tick();
        player.receive();
    }

    @Test
    void theServerWontEquipADrop() {
        player.send(new Intent.Equip(EquipSlot.WEAPON, drop.id));
        player.send(new Intent.Equip(EquipSlot.ARMOR, drop.id));
        tick();
        assertNull(alice.weapon);
        assertNull(alice.armor);
    }

    @Test
    void aDropsPopupHasNoEquipOptions() {
        InventoryPanel inventory = new InventoryPanel(Layout.INVENTORY);
        inventory.setBounds(Layout.TABS);
        Board board = new Board(player.content);
        inventory.focus(player, board);
        inventory.handleKey(new KeyStroke(KeyType.Enter), player, board);
        inventory.handleKey(new KeyStroke(KeyType.Enter), player, board);
        assertEquals(player.content.product(drop.id), inventory.takeItemToUse(), "Use comes first");
        tick();
        assertNull(alice.weapon);
    }
}
