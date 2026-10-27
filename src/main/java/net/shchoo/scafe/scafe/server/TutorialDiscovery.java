package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.generation.WorldGenerator;
import net.shchoo.scafe.scafe.model.Recipe;
import net.shchoo.scafe.scafe.model.Skill;
import net.shchoo.scafe.scafe.model.SkillType;
import net.shchoo.scafe.scafe.model.Stat;
import net.shchoo.scafe.scafe.model.World;

import java.util.Random;

/**
 * The tutorial world's discoveries: a script instead of chance. In order -- a primary skill (and its node) once
 * you've walked a little, a secondary skill (and its node) once you've made a few items, a monster once you've made
 * something with the secondary, a node too high level for you once you've won a fight, then a tertiary skill once
 * you've set a goal. Each stage happens when the {@link Pacing} says it's due, or when {@link #force}d (tests, /6).
 * Meanwhile only known monsters turn up; after the last stage the world grows like any other.
 */
public class TutorialDiscovery extends Discovery {
    public enum Stage { PRIMARY, SECONDARY, MONSTER, LOCKED_NODE, TERTIARY }

    /** Whether {@code stage} (the next one) should happen now, for {@code character} in {@code world}. */
    public interface Pacing {
        boolean due(Stage stage, GameCharacter character, World world);
    }

    /** Steps before the first skill. */
    public static final int FIRST_STEPS = 8;
    /** Items made before the secondary skill. */
    public static final int FIRST_ITEMS = 5;

    /** The usual pacing: see the class comment. */
    public static final Pacing DEFAULT_PACING = (stage, character, world) -> switch (stage) {
        case PRIMARY -> character.stats.getOrDefault(Stat.STEPS, 0L) >= FIRST_STEPS;
        case SECONDARY -> character.stats.getOrDefault(Stat.ITEMS_CRAFTED, 0L) >= FIRST_ITEMS;
        case MONSTER -> world.content.craftingSkills().stream()
                .anyMatch(s -> s.type == SkillType.SECONDARY && character.getExperience(s) > 0);
        case LOCKED_NODE -> character.stats.getOrDefault(Stat.MONSTERS_KILLED, 0L) >= 1;
        case TERTIARY -> !character.goals.isEmpty();
    };

    /** Pacing that never moves on by itself: only {@link #force} does. */
    public static final Pacing ONLY_FORCED = (stage, character, world) -> false;

    private Pacing pacing;
    /** The next stage, or null once they've all happened. */
    private Stage next = Stage.PRIMARY;
    /** Forced: every stage up to and including this one happens on the next roll. */
    private Stage forcedThrough;

    public TutorialDiscovery(Random random) {
        this(random, DEFAULT_PACING);
    }

    public TutorialDiscovery(Random random, Pacing pacing) {
        super(random);
        this.pacing = pacing;
    }

    public void setPacing(Pacing pacing) {
        this.pacing = pacing;
    }

    /** The next stage, or null when the script's over. */
    public Stage next() {
        return next;
    }

    /** On the next roll, the next stage happens (null), or every stage up to {@code through}, whatever the pacing. */
    public void force(Stage through) {
        if (next != null) {
            forcedThrough = through != null ? through : next;
        }
    }

    @Override
    void discover(GameServer server, GameCharacter character, Trigger trigger) {
        if (next == null) {
            super.discover(server, character, trigger);
            return;
        }
        while (next != null && (forcedThrough != null && next.ordinal() <= forcedThrough.ordinal()
                || pacing.due(next, character, server.world))) {
            run(server, character, next);
            next = next.ordinal() + 1 < Stage.values().length ? Stage.values()[next.ordinal() + 1] : null;
        }
        if (forcedThrough != null && (next == null || next.ordinal() > forcedThrough.ordinal())) {
            forcedThrough = null;
        }
        // something to fight once there's a kind of monster (and more of it after a win)
        rollMonsterSpawn(server, character, trigger);
    }

    private void run(GameServer server, GameCharacter character, Stage stage) {
        World world = server.world;
        switch (stage) {
            case PRIMARY -> discoverSkill(server, character, WorldGenerator.newSkill(world, SkillType.PRIMARY));
            case SECONDARY -> discoverSkill(server, character, WorldGenerator.newSkill(world, SkillType.SECONDARY));
            case MONSTER -> discoverMonsterKind(server, character);
            case LOCKED_NODE -> {
                Recipe locked = lowestLocked(world, character);
                if (locked != null) {
                    discoverNode(server, character, recipe -> recipe.id == locked.id ? 1 : 0);
                }
            }
            case TERTIARY -> discoverSkill(server, character, WorldGenerator.newSkill(world, SkillType.TERTIARY));
        }
    }

    /** The lowest-level recipe {@code character} can't do yet (the primary's tier 1, usually). */
    static Recipe lowestLocked(World world, GameCharacter character) {
        Recipe lowest = null;
        for (Skill skill : world.content.craftingSkills()) {
            for (var line : skill.productLines) {
                for (Recipe recipe : line.recipes) {
                    if (!recipe.levelMet(character.getLevel(skill)) && (lowest == null || recipe.levelNeeded < lowest.levelNeeded)) {
                        lowest = recipe;
                    }
                }
            }
        }
        return lowest;
    }
}
