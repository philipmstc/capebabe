package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.client.Player;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.model.World;
import net.shchoo.scafe.scafe.protocol.Intent;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NodePlacementTest {
    private final Set<Position> nodes = new HashSet<>();

    private boolean safe(int x, int y) {
        return NodePlacement.safe(nodes::contains, new Position(x, y));
    }

    private void node(int x, int y) {
        nodes.add(new Position(x, y));
    }

    @Test
    void theLastSquareOfACheckerboardIsRefused() {
        //  # . # . #
        //  . # . # .
        //  # . # . #     the middle row's dots are boxed in once the last '#' goes down
        int[][] board = {{0, 0}, {2, 0}, {4, 0}, {1, 1}, {3, 1}, {0, 2}, {4, 2}, {2, 2}};
        for (int i = 0; i < board.length - 1; i++) {
            assertTrue(safe(board[i][0], board[i][1]), "square " + i);
            node(board[i][0], board[i][1]);
        }
        assertFalse(safe(2, 2), "(2,1) would be shut in: nodes on all four sides, and no squeezing past corners");
        assertTrue(safe(2, 3), "somewhere else nearby is fine");
    }

    @Test
    void aNodeCantBeBuriedSoNobodyCanStandNextToIt() {
        node(0, 0);
        node(1, 0);
        node(-1, 0);
        node(0, 1);
        assertFalse(safe(0, -1), "(0,0) would have no open side left to use it from");
        assertTrue(safe(5, 5));
    }

    @Test
    void aLineOfNodesIsFine() {
        for (int x = -5; x <= 5; x++) {
            assertTrue(safe(x, 0), "a wall doesn't enclose anything on its own (x=" + x + ")");
            node(x, 0);
        }
    }

    @Test
    void problemsThatWereAlreadyThereDontBlockNewNodes() {
        node(0, 1);
        node(0, -1);
        node(1, 0);
        node(-1, 0);
        assertFalse(NodePlacement.problems(nodes::contains, new Position(0, 0)).isEmpty(), "(0,0) is already a pocket");
        assertTrue(safe(3, 3));
    }

    @Test
    void aNewSkillBringsATierZeroNodeNearWhoeverFoundIt() {
        GameServer server = new GameServer(new World(1, "w", 3));
        server.setDiscovery(new Discovery(new Random(5)));
        server.setRandom(new Random(5));
        Player alice = new Player(new LocalConnection(server.connect()));
        alice.send(new Intent.Join("alice"));
        alice.send(new Intent.Admin("1", List.of("3")));
        server.tick();
        alice.receive();
        for (var skill : alice.content.craftingSkills()) {
            var tier0 = skill.productLines.get(0).recipes.get(0);
            assertTrue(alice.content.nodes().stream().anyMatch(node -> node.recipe.equals(tier0)), skill.name + " has a node");
        }
    }
}
