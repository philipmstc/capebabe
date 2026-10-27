package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.generation.WorldGenerator;
import net.shchoo.scafe.scafe.model.Recipe;
import net.shchoo.scafe.scafe.model.Stacks;
import net.shchoo.scafe.scafe.model.World;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NodeTiersAndSlotsTest {
    @Test
    void newNodesAreMostlyLowTiers() {
        World world = new World(1, "w", 3);
        world.content.apply(WorldGenerator.newSkills(world, 1));
        List<Recipe> tiers = world.content.craftingSkills().get(0).productLines.get(0).recipes;
        double[] fresh = shares(tiers, 1), maxed = shares(tiers, 100);
        assertEquals(0.84, fresh[0], 0.01, "Lv. 1: mostly tier 0");
        assertEquals(0.10, fresh[1], 0.01);
        assertEquals(0.57, maxed[0], 0.01, "everything unlocked: still mostly tier 0");
        assertEquals(0.28, maxed[1], 0.01);
        assertTrue(maxed[4] < 0.05, "tier 4 rare");
    }

    private static double[] shares(List<Recipe> tiers, int level) {
        double total = tiers.stream().mapToInt(r -> Discovery.nodeWeight(r, level)).sum();
        return tiers.stream().mapToDouble(r -> Discovery.nodeWeight(r, level) / total).toArray();
    }

    @Test
    void fifteenSlotsAndOneATrophy() {
        World world = new World(1, "w", 3);
        world.content.apply(WorldGenerator.newMonsterType(world));
        int monster = world.content.monsters.get(0).id;
        assertEquals(15, Stacks.capacity(world.content.trophies("alice")));
        world.content.apply(WorldGenerator.newDrop(world, monster, "alice"));
        world.content.apply(WorldGenerator.newDrop(world, monster, "bob"));
        world.content.apply(WorldGenerator.newDrop(world, monster, "alice"));
        assertEquals(17, Stacks.capacity(world.content.trophies("alice")));
        assertEquals(16, Stacks.capacity(world.content.trophies("bob")));
    }
}
