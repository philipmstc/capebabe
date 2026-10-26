package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.model.Recipe;
import net.shchoo.scafe.scafe.model.Product;
import net.shchoo.scafe.scafe.client.SkillStyle;
import net.shchoo.scafe.scafe.client.Board;
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
import net.shchoo.scafe.scafe.model.Levels;
import net.shchoo.scafe.scafe.model.Skill;
import net.shchoo.scafe.scafe.model.World;
import net.shchoo.scafe.scafe.protocol.Intent;

import com.googlecode.lanterna.TextColor;
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

class SkillsMenuTest {
    private GameServer server;
    private Player player;
    private Board board;
    private SkillsMenu skills;
    private TabBar tabs;
    private Skill first;

    @BeforeEach
    void setUp() {
        World world = new World(1, "w", 2);
        world.content.apply(WorldGenerator.newSkills(world, 3));
        server = new GameServer(world);
        server.setDiscovery(Discovery.off());
        player = new Player(new LocalConnection(server.connect()));
        player.send(new Intent.Join("alice"));
        tick();
        board = new Board(player.content);
        skills = new SkillsMenu();
        tabs = new TabBar(Layout.TABS, new InventoryPanel(Layout.INVENTORY), new MapMenu(),
                List.of(skills, new GoalsMenu(), new StatsMenu()), new SettingsMenu());
        first = board.skills.get(0);
    }

    private void tick() {
        server.tick();
        player.receive();
    }

    private void xp(Skill skill, long amount) {
        player.send(new Intent.Admin("5", List.of(skill.name, String.valueOf(amount))));
        tick();
    }

    private void press(KeyStroke key) {
        tabs.onKey(key, player, board);
    }

    private Screen drawn() throws IOException {
        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(Layout.TERMINAL_SIZE));
        screen.startScreen();
        tabs.draw(screen, player, board);
        return screen;
    }

    private static String row(Screen screen, int y) {
        StringBuilder line = new StringBuilder();
        for (int x = Layout.TABS.x(); x <= Layout.TABS.right(); x++) {
            line.append(screen.getBackCharacter(x, y).getCharacterString());
        }
        return line.toString();
    }

    private static String box(Screen screen) {
        StringBuilder text = new StringBuilder();
        for (int y = Layout.TABS.y(); y <= Layout.TABS.bottom(); y++) {
            text.append(row(screen, y)).append('\n');
        }
        return text.toString();
    }

    @Test
    void aRowIsLevelTypeNameAndAGreenBarToTheNextLevel() throws IOException {
        xp(first, 64_600);
        Screen screen = drawn();
        int y = Layout.TABS.y() + 1;
        String line = row(screen, y);
        int level = player.getLevel(first);
        assertTrue(line.contains(String.format("[%3d / 100] \u2022 " + first.name, level)), line);
        assertTrue(line.contains("64600/" + player.getExperienceToNextLevel(first) + "]"), "xp right-aligned in the bar: " + line);
        int open = line.lastIndexOf('[') + Layout.TABS.x();
        assertEquals(TextColor.ANSI.GREEN, screen.getBackCharacter(open + 1, y).getBackgroundColor(), "filled some way");
        int close = line.lastIndexOf(']') + Layout.TABS.x();
        assertNotEquals(TextColor.ANSI.GREEN, screen.getBackCharacter(close - 1, y).getBackgroundColor(), "but not all the way");
    }

    @Test
    void enterOpensTheSkillsPageAndEscGoesBack() throws IOException {
        press(new KeyStroke('1', false, false));
        press(new KeyStroke(KeyType.Enter));
        assertTrue(skills.skillOpen());
        String shown = box(drawn());
        assertTrue(shown.contains(first.type.name()), shown);
        assertTrue(shown.contains("total xp"));
        assertTrue(shown.contains("Recipes"));
        assertTrue(shown.contains("[Esc] back to skills"));
        press(new KeyStroke(KeyType.ArrowDown));
        assertEquals(first.productLines.get(0).recipes.get(1), skills.selectedRecipe(first), "Down picks the next tier");
        press(new KeyStroke(KeyType.Escape));
        assertFalse(skills.skillOpen());
        assertTrue(skills.isFocused(), "back on the list");
        assertTrue(box(drawn()).contains("[Enter] open"));
    }

    @Test
    void atLevel100PPrestigesAfterAYes() throws IOException {
        long xp = Skill.XP_PER_LEVEL[Levels.MAX_LEVEL - 1];
        xp(first, xp);
        press(new KeyStroke('1', false, false));
        press(new KeyStroke(KeyType.Enter));
        assertTrue(box(drawn()).contains("[p] prestige"));
        press(new KeyStroke('p', false, false));
        assertTrue(box(drawn()).contains("Prestige " + first.name + "?"));
        press(new KeyStroke('n', false, false));
        tick();
        assertEquals(0, player.getPrestige(first), "no means no");
        press(new KeyStroke('p', false, false));
        press(new KeyStroke('y', false, false));
        tick();
        assertEquals(1, player.getPrestige(first));
        assertEquals(1, player.getLevel(first));
        String shown = box(drawn());
        assertTrue(shown.contains(first.name + " +1"), "the row shows the prestige:\n" + shown);
        assertTrue(shown.contains(String.format("%,d total xp", xp)), shown);
        assertTrue(shown.contains("virtual Lv. " + Levels.virtualLevelFor(xp)), "what the total xp would be with no cap: " + shown);
    }

    @Test
    void tabPicksAComponentAndEnterOpensTheSkillThatMakesIt() throws IOException {
        server.applyContent(WorldGenerator.newSkills(server.world, 4));
        tick();
        Skill crafted = board.skills.stream().filter(s -> !s.productLines.isEmpty()
                && !s.productLines.get(0).recipes.get(0).components.isEmpty()).findFirst().orElseThrow();
        Recipe recipe = crafted.productLines.get(0).recipes.get(0);
        Product component = recipe.components.keySet().iterator().next();
        Recipe makesIt = player.content.madeBy(component);

        press(new KeyStroke('1', false, false));
        for (int i = 0; i < board.skills.indexOf(crafted); i++) {
            press(new KeyStroke(KeyType.ArrowDown));
        }
        press(new KeyStroke(KeyType.Enter));
        Screen screen = drawn();
        String page = box(screen);
        int costsRow = page.substring(0, page.indexOf("- Each costs ")).split("\n", -1).length - 1;
        String costs = page.split("\n")[costsRow];
        int at = costs.indexOf(makesIt.skill.name, costs.indexOf("- Each costs "));
        assertTrue(at > 0, costs);
        assertTrue(costs.contains("of same tier"), costs);
        assertEquals(SkillStyle.getColorForSkill(makesIt.skill),
                screen.getBackCharacter(Layout.TABS.x() + at, Layout.TABS.y() + costsRow).getForegroundColor(),
                "a component named by the skill making it, in its colour");

        press(new KeyStroke(KeyType.Tab));
        assertTrue(box(drawn()).contains("[Enter] open its skill"));
        press(new KeyStroke(KeyType.Enter));
        assertTrue(skills.skillOpen());
        assertEquals(makesIt, skills.selectedRecipe(makesIt.skill), "on the page of the skill that makes it, on that recipe");
        assertTrue(box(drawn()).contains("[Esc] back"));

        press(new KeyStroke(KeyType.Escape));
        assertEquals(recipe, skills.selectedRecipe(crafted), "back where you were");
        assertTrue(skills.skillOpen());
        press(new KeyStroke(KeyType.Escape));
        assertFalse(skills.skillOpen(), "then the list");
    }
}
