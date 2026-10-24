package net.shchoo.scafe.scafe.client.ui;

import net.shchoo.scafe.scafe.client.Board;
import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.client.SkillStyle;
import net.shchoo.scafe.scafe.model.Product;
import net.shchoo.scafe.scafe.model.Skill;
import net.shchoo.scafe.scafe.model.World;
import net.shchoo.scafe.scafe.protocol.Intent;
import net.shchoo.scafe.scafe.server.Discovery;
import net.shchoo.scafe.scafe.server.GameServer;
import net.shchoo.scafe.scafe.server.LocalConnection;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EquipmentViewTest {

    private static String row(Screen screen, int y) {
        StringBuilder line = new StringBuilder();
        for (int x = Layout.EQUIPMENT.x(); x <= Layout.EQUIPMENT.right(); x++) {
            line.append(screen.getBackCharacter(x, y).getCharacterString());
        }
        return line.toString();
    }

    @Test
    void tiersShowAsDots() {
        String[] marks = {"  ", " .", "..", ".:", "::"};
        for (int tier = 0; tier < marks.length; tier++) {
            assertEquals(marks[tier], EquipmentView.tierMark(new Product(1, "p", tier)));
        }
        assertEquals("  ", EquipmentView.tierMark(new Product(1, "monster0-drop0")), "drops have no tier");
    }

    @Test
    void weaponAndArmourWithWhatsLeftInTheirColours() throws IOException {
        GameServer server = new GameServer(new World(1, "w", 1));
        server.setDiscovery(Discovery.off());
        Player player = new Player(new LocalConnection(server.connect()));
        player.send(new Intent.Join("alice"));
        player.send(new Intent.Admin("1", List.of("1")));
        server.tick();
        player.receive();
        Skill skill = player.content.craftingSkills().get(0);
        Product item = skill.productLines.get(0).recipes.get(3).product;
        player.inventory.put(item, 123);
        player.weapon = item;

        Screen screen = new TerminalScreen(new DefaultVirtualTerminal(Layout.TERMINAL_SIZE));
        screen.startScreen();
        new EquipmentView(Layout.EQUIPMENT).draw(screen, player, new Board(player.content));
        int y = Layout.EQUIPMENT.y() + 1, x = Layout.EQUIPMENT.x() + 2;
        assertEquals(" Weapon [.:]S0-0 x123", row(screen, y).substring(1, 22));
        assertTrue(row(screen, y + 1).contains("Armor  none"));
        assertEquals(TextColor.ANSI.YELLOW, screen.getBackCharacter(x, y).getForegroundColor(), "Weapon in dark yellow");
        assertEquals(SkillStyle.darkOf(skill), screen.getBackCharacter(x + 7, y).getForegroundColor(), "[ dark");
        assertEquals(SkillStyle.darkOf(skill), screen.getBackCharacter(x + 9, y).getForegroundColor(), "tier dots dark");
        assertEquals(SkillStyle.getColorForSkill(skill), screen.getBackCharacter(x + 11, y).getForegroundColor(), "S0-0 bright");
        assertEquals(TextColor.ANSI.BLACK_BRIGHT, screen.getBackCharacter(x + 16, y).getForegroundColor(), "x grey");
        assertEquals(TextColor.ANSI.WHITE, screen.getBackCharacter(x + 17, y).getForegroundColor(), "count white");        player.inventory.put(item, 1113);
        new EquipmentView(Layout.EQUIPMENT).draw(screen, player, new Board(player.content));
        assertEquals(" Weapon [.:]S0-0 x1.1k\u2502", row(screen, y).substring(1), "a thousand and more, shortened");
        player.inventory.put(item, 10_250);
        new EquipmentView(Layout.EQUIPMENT).draw(screen, player, new Board(player.content));
        assertEquals(" Weapon [.:]S0-0x10.2k\u2502", row(screen, y).substring(1), "the space goes before the label is cut");

    }
}
