package net.shchoo.scafe.scafe.model;

import net.shchoo.scafe.scafe.generation.WorldGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OldTierLevelsTest {
    @Test
    void aWorldSavedWithTheOldLevelsLoadsWithTheNewOnes() {
        int[] old = {0, 20, 35, 50, 70};
        ContentBatch batch = WorldGenerator.newSkills(new World(1, "w", 3), 1);
        List<ContentBatch.RecipeDef> recipes = new java.util.ArrayList<>();
        java.util.Map<Integer, Integer> tierInLine = new java.util.HashMap<>();
        for (ContentBatch.RecipeDef r : batch.recipes()) {
            int tier = tierInLine.merge(r.productLineId(), 1, Integer::sum) - 1;
            recipes.add(new ContentBatch.RecipeDef(r.id(), r.productLineId(), r.productId(), old[tier], r.expGained(), r.components()));
        }
        ContentBatch saved = new ContentBatch(batch.version(), batch.skills(), batch.products(), batch.productLines(),
                recipes, batch.nodes(), batch.monsters(), batch.drops(), batch.buildings(), batch.origin());
        WorldContent content = new WorldContent();
        content.apply(saved);
        for (ProductLine line : content.craftingSkills().get(0).productLines) {
            for (int tier = 0; tier < line.recipes.size(); tier++) {
                assertEquals(Tiers.LEVEL_NEEDED[tier], line.recipes.get(tier).levelNeeded, "tier " + tier);
            }
        }
    }
}
