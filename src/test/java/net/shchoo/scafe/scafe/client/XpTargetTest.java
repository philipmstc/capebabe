package net.shchoo.scafe.scafe.client;

import net.shchoo.scafe.scafe.model.Goal;
import net.shchoo.scafe.scafe.model.Levels;
import net.shchoo.scafe.scafe.model.Product;
import net.shchoo.scafe.scafe.model.Skill;
import net.shchoo.scafe.scafe.model.World;
import net.shchoo.scafe.scafe.protocol.Intent;
import net.shchoo.scafe.scafe.server.Discovery;
import net.shchoo.scafe.scafe.server.GameServer;
import net.shchoo.scafe.scafe.server.LocalConnection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class XpTargetTest {
    private GameServer server;
    private Player player;
    private Skill skill;
    private Skill other;

    @BeforeEach
    void setUp() {
        server = new GameServer(new World(1, "w", 1));
        server.setDiscovery(Discovery.off());
        player = new Player(new LocalConnection(server.connect()));
        player.send(new Intent.Join("alice"));
        player.send(new Intent.Admin("1", List.of("2")));
        tick();
        skill = player.content.skills.get(0);
        other = player.content.skills.get(1);
    }

    private void tick() {
        server.tick();
        player.receive();
    }

    /** Gives xp until the skill is at {@code level}. */
    private void reach(int level) {
        long needed = Skill.XP_PER_LEVEL[level - 1] - player.getExperience(skill);
        player.send(new Intent.Admin("5", List.of(skill.name, String.valueOf(needed))));
        tick();
        assertEquals(level, player.getLevel(skill));
    }

    private void goal(Skill skill, int level) {
        player.goals.add(new Goal(skill, level, new Product(9000 + level, "p" + level)));
    }

    @Test
    void withoutAGoalItsTheNextLevel() {
        reach(54);
        XpTarget target = XpTarget.of(player, skill);
        assertFalse(target.goal());
        assertEquals(55, target.targetLevel());
        assertEquals(Skill.XP_PER_LEVEL[53], target.fromXp());
        assertEquals(Skill.XP_PER_LEVEL[54], target.toXp());
        assertEquals(0.0, target.fraction(), 1e-9, "just levelled");
    }

    @Test
    void theNearestUnmetGoalInThatSkillWins() {
        reach(54);
        goal(skill, 80);
        goal(skill, 70);
        goal(skill, 40);
        goal(other, 60);
        XpTarget target = XpTarget.of(player, skill);
        assertTrue(target.goal());
        assertEquals(70, target.targetLevel(), "not the met one (40), not the further one (80), not another skill's");
        assertEquals(Skill.XP_PER_LEVEL[69], target.toXp());
        assertEquals(Skill.XP_PER_LEVEL[69] - Skill.XP_PER_LEVEL[53], target.remaining());
        assertEquals((double) Skill.XP_PER_LEVEL[53] / Skill.XP_PER_LEVEL[69], target.fraction(), 1e-9,
                "all your xp out of what 70 needs, so it doesn't empty at each level up");

        reach(70);
        assertEquals(80, XpTarget.of(player, skill).targetLevel(), "70 met: on to the next goal");
    }

    @Test
    void maxLevelIsMaxed() {
        reach(Levels.MAX_LEVEL);
        goal(skill, 50);
        XpTarget target = XpTarget.of(player, skill);
        assertTrue(target.maxed());
        assertEquals(0, target.remaining());
        assertEquals(1.0, target.fraction(), 1e-9);
    }
}
