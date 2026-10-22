package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.model.Skill;
import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.model.MonsterType;
import net.shchoo.scafe.scafe.model.Product;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InventoryPanelTest {
    private Player player;
    private Board board;
    private final InventoryPanel inventory = new InventoryPanel(Layout.INVENTORY);
    private TabBar tabs;

    private GameServer server;

    @BeforeEach
    void setUp() {
        server = new GameServer(new World(1, "w", 1));
        server.setDiscovery(Discovery.off());
        player = new Player(new LocalConnection(server.connect()));
        board = new Board(player.content);
        player.send(new Intent.Join("alice"));
        server.tick();
        player.receive();
        tabs = new TabBar(Layout.TABS, inventory, new MapMenu(), List.of(new SkillsMenu(), new GoalsMenu(), new StatsMenu()),
                new SettingsMenu());
    }

    private void press(KeyType type) {
        tabs.onKey(new KeyStroke(type), player, board);
    }

    private String gridText() throws IOException {
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(Layout.TERMINAL_SIZE));
        screen.startScreen();
        tabs.draw(screen, player, board);
        StringBuilder text = new StringBuilder();
        for (int y = Layout.INVENTORY.y(); y <= Layout.INVENTORY.bottom(); y++) {
            for (int x = Layout.INVENTORY.x(); x <= Layout.INVENTORY.right(); x++) {
                TextCharacter c = screen.getBackCharacter(x, y);
                text.append(c == null ? ' ' : c.getCharacterString());
            }
            text.append('\n');
        }
        screen.stopScreen();
        return text.toString();
    }

    @Test
    void cellsAreLabelledShortly() {
        assertArrayEquals(new String[]{"S3-2", "T7"}, InventoryPanel.label(new Product(1, "skill3{2}-7", 7)));
        assertArrayEquals(new String[]{"skill3", "{2}-7"}, InventoryPanel.label(new Product(4, "skill3{2}-7")),
                "the tier comes from the data, not the name");
        assertArrayEquals(new String[]{"M1-D4", ""}, InventoryPanel.label(new Product(2, "monster1-drop4")));
        assertArrayEquals(new String[]{"someth", "ing el"}, InventoryPanel.label(new Product(3, "something else")));
        assertEquals("T2   7", InventoryPanel.countLine("T2", 7), "the stack's count on the right");
        assertEquals("T2 100", InventoryPanel.countLine("T2", 100));
    }

    @Test
    void anItemNoSkillMakesIsOnePerSlotWithoutAFightingLevel() {
        player.inventory.put(new Product(1, "skill0{0}-0", 0), 3);
        player.inventory.put(new Product(2, "skill1{0}-1", 1), 1);
        assertEquals(4, InventoryPanel.slots(player).size());
    }

    @Test
    void iJumpsIntoTheInventoryAndArrowsMoveLikeTheHoverCursor() throws IOException {
        int columns = Layout.INVENTORY_COLUMNS, fits = columns * Layout.INVENTORY_ROWS;
        player.inventory.put(new Product(1, "skill0{0}-0", 0), fits);
        player.inventory.put(new Product(2, "skill1{2}-3", 3), 1);
        tabs.onKey(new KeyStroke('i', false, false), player, board);
        assertSame(inventory, tabs.selected());
        assertTrue(inventory.isFocused());

        press(KeyType.ArrowRight);
        press(KeyType.ArrowDown);
        assertEquals(1 + columns, inventory.selectedIndex());
        assertFalse(gridText().contains("S1-2"), "the last item is below the fold");

        for (int i = 0; i < Layout.INVENTORY_ROWS + 1; i++) {
            press(KeyType.ArrowDown);
        }
        assertEquals(fits, inventory.selectedIndex(), "stops at the last item");
        String grid = gridText();
        assertTrue(grid.contains("S1-2"), "scrolled down to it:\n" + grid);
        assertTrue(grid.contains(String.valueOf(fits + 1)), "item count on the border");

        press(KeyType.Escape);
        assertFalse(inventory.isFocused());
    }

    @Test
    void theScrollbarShowsHowMuchIsVisibleAndWhere() throws IOException {
        int columns = Layout.INVENTORY_COLUMNS, rows = Layout.INVENTORY_ROWS;
        int track = Layout.INVENTORY.height() - 2;
        player.inventory.put(new Product(1, "skill0{0}-0", 0), columns * rows * 2);
        int thumb = (int) Math.round(track / 2.0);
        assertEquals(List.of(1, thumb), thumbRows(), "half the rows visible = half the track, at the top");

        tabs.onKey(new KeyStroke('i', false, false), player, board);
        for (int i = 0; i < rows * 2; i++) {
            press(KeyType.ArrowDown);
        }
        assertEquals(List.of(track - thumb + 1, track), thumbRows(), "scrolled to the bottom");
    }

    /** First and last row (counted inside the frame) of the scrollbar thumb on the inventory's right border. */
    private List<Integer> thumbRows() throws IOException {
        String[] lines = gridText().split("\n");
        int right = Layout.INVENTORY.width() - 1;
        int first = -1, last = -1;
        for (int y = 1; y < lines.length - 1; y++) {
            if (lines[y].charAt(right) == Symbols.DOUBLE_LINE_VERTICAL) {
                first = first < 0 ? y : first;
                last = y;
            }
        }
        return List.of(first, last);
    }

    @Test
    void upFromTheTopRowLeavesTheInventory() {
        player.inventory.put(new Product(1, "skill0{0}-0", 0), 4);
        tabs.onKey(new KeyStroke('i', false, false), player, board);
        press(KeyType.ArrowDown);
        press(KeyType.ArrowUp);
        assertTrue(inventory.isFocused());
        press(KeyType.ArrowUp);
        assertFalse(inventory.isFocused());
    }

    @Test
    void anEmptyInventoryCantBeFocused() {
        player.inventory.put(new Product(1, "skill0{0}-0", 0), 0);
        tabs.onKey(new KeyStroke('i', false, false), player, board);
        assertFalse(inventory.isFocused(), "an item at x 0 takes no slot");
    }

    @Test
    void dropsSavedBeforeFinderNamesExistedStillShow() throws IOException {
        Product drop = new Product(9, "monster0-drop0");
        MonsterType monster = new MonsterType(8, "monster0", 1, 5, 1, 0, 'M', 3, 2);
        monster.drops.add(new MonsterType.Drop(drop, 2, null));
        player.content.monsters.add(monster);
        player.inventory.put(drop, 1);
        tabs.onKey(new KeyStroke('i', false, false), player, board);
        assertTrue(gridText().contains("M0-D0"));
    }

    private String screenText() throws IOException {
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(Layout.TERMINAL_SIZE));
        screen.startScreen();
        tabs.draw(screen, player, board);
        tabs.drawOnTop(screen, player, board);
        StringBuilder text = new StringBuilder();
        for (int y = 0; y < Layout.TERMINAL_SIZE.getRows(); y++) {
            for (int x = 0; x < Layout.TERMINAL_SIZE.getColumns(); x++) {
                text.append(screen.getBackCharacter(x, y).getCharacterString());
            }
            text.append('\n');
        }
        return text.toString();
    }

    /** A crafting skill at {@code level}, and {@code count} of its first line's tier-1 item (client side only). */
    private Product stackOf(int level, int count) {
        player.send(new Intent.Admin("1", List.of("1")));
        server.tick();
        player.receive();
        Skill skill = player.content.craftingSkills().get(0);
        player.send(new Intent.Admin("5", List.of(skill.name, String.valueOf(Skill.XP_PER_LEVEL[level - 1]))));
        server.tick();
        player.receive();
        Product item = skill.productLines.get(0).recipes.get(1).product;
        player.inventory.put(item, count);
        return item;
    }

    @Test
    void itemsStackUpToYourLevelInTheSkillMakingThem() throws IOException {
        Product item = stackOf(4, 10);
        assertEquals(List.of(new InventoryPanel.Slot(item, 4), new InventoryPanel.Slot(item, 4), new InventoryPanel.Slot(item, 2)),
                InventoryPanel.slots(player));
        String grid = gridText();
        assertTrue(grid.contains("T1    4") || grid.contains("T1   4"), "counts on the cells:\n" + grid);
        assertTrue(grid.contains("3"), "3 slots on the border");
    }

    @Test
    void enterOpensTheEquipUseCancelPopupNextToTheItem() throws IOException {
        stackOf(1, 2);
        tabs.onKey(new KeyStroke('i', false, false), player, board);
        press(KeyType.Enter);
        assertTrue(inventory.popupOpen());
        String[] lines = screenText().split("\n");
        int row = Layout.INVENTORY.y() + 2;
        assertTrue(lines[row].contains("> Equip weapon"), lines[row]);
        assertTrue(lines[row + 1].contains("Equip armor"));
        assertTrue(lines[row + 2].contains("Use"));
        assertTrue(lines[row + 3].contains("Cancel"));
        assertFalse(screenText().contains("Unequip"), "it isn't equipped");
        int at = lines[row].indexOf("> Equip");
        assertTrue(at > Layout.INVENTORY.x() + 2 + Layout.CELL_WIDTH && at < Layout.INVENTORY.x() + 2 + 2 * Layout.CELL_WIDTH,
                "just right of the first cell");

        type('w');
        assertTrue(inventory.popupOpen(), "keys stay in the popup");
        for (int i = 0; i < 3; i++) {
            press(KeyType.ArrowDown);
        }
        press(KeyType.Enter);
        assertFalse(inventory.popupOpen(), "Cancel");
        assertTrue(inventory.isFocused(), "still in the inventory");
        press(KeyType.Enter);
        press(KeyType.Escape);
        assertFalse(inventory.popupOpen());
        assertTrue(inventory.isFocused(), "Esc only closes the popup");
    }

    @Test
    void anEquippedItemOffersUnequip() throws IOException {
        Product item = stackOf(3, 5);
        player.armor = item;
        tabs.onKey(new KeyStroke('i', false, false), player, board);
        press(KeyType.Enter);
        assertTrue(screenText().contains("Unequip"));
    }

    private void type(char c) {
        tabs.onKey(new KeyStroke(c, false, false), player, board);
    }
}
