package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.generation.WorldGenerator;
import net.shchoo.scafe.scafe.model.ContentBatch;
import net.shchoo.scafe.scafe.model.Direction;
import net.shchoo.scafe.scafe.model.MonsterType;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.model.Product;
import net.shchoo.scafe.scafe.model.Skill;
import net.shchoo.scafe.scafe.protocol.Event;

import java.util.List;
import java.util.Objects;

/**
 * Fighting monsters. You and the monster swing every {@link #SWING_TICKS} ticks while you're next to it. Damage
 * you deal gives combat xp; a kill might drop the monster's next one-of-a-kind item (each only ever drops once
 * per world). Being knocked out sends you back to the origin at full hp.
 */
class Combat {
    static final int SWING_TICKS = 2;
    static final double PLAYER_ACCURACY = 0.8;
    static final double MONSTER_ACCURACY = 0.6;
    static final int XP_PER_DAMAGE = 4;
    /** Out of a fight you get 1 hp back every this many ticks. */
    static final int REGEN_TICKS = 10;
    static final Position RESPAWN = new Position(0, 0);

    static int maxHp(int combatLevel) {
        return 9 + combatLevel;
    }

    static int maxHit(int combatLevel) {
        return 1 + combatLevel / 4;
    }

    /** A character's fight with one monster. */
    static class Fight {
        final Monster monster;
        long nextSwing;

        Fight(Monster monster, long nextSwing) {
            this.monster = monster;
            this.nextSwing = nextSwing;
        }
    }

    private final GameServer server;

    Combat(GameServer server) {
        this.server = server;
    }

    /** The Attack intent: start fighting the monster next to you, or stop if you already are. */
    void attack(GameCharacter character, long tick) {
        if (character.currentFight != null) {
            endFight(character, "You stop fighting.");
            return;
        }
        Monster monster = server.monsters().all().stream()
                .filter(m -> m.position().equals(character.ahead())).findFirst()
                .orElseGet(() -> server.monsters().nextTo(character.position()));
        if (monster == null) {
            character.say("Nothing to fight here.");
            return;
        }
        Direction toward = Direction.toward(character.position(), monster.position());
        if (toward != null && toward != character.facing) {
            character.face(toward);
            Event turned = new Event.EntityMoved(character.id, character.position(), character.facing);
            character.emit(turned);
            server.toObservers(character.id, turned);
        }
        character.stopAction(null);
        character.stopWalking();
        monster.walker.stop();
        monster.fighters.add(character.id);
        character.currentFight = new Fight(monster, tick + 1);
        character.emit(new Event.FightStarted(monster.id));
        character.say("You attack " + monster.type.name + " (Lv. " + monster.type.level + ").");
    }

    void endFight(GameCharacter character, String reason) {
        Fight fight = character.currentFight;
        if (fight == null) {
            return;
        }
        fight.monster.fighters.remove(character.id);
        character.currentFight = null;
        if (reason != null) {
            character.say(reason);
        }
        character.emit(new Event.FightEnded());
    }

    void tick(long tick) {
        for (GameCharacter character : List.copyOf(server.onlineCharacters())) {
            Fight fight = character.currentFight;
            if (fight == null) {
                continue;
            }
            Monster monster = fight.monster;
            if (!server.monsters().contains(monster)) {
                endFight(character, null);
                continue;
            }
            if (Monsters.distance(character.position(), monster.position()) > 1) {
                endFight(character, "You stopped fighting.");
                continue;
            }
            if (tick < fight.nextSwing) {
                continue;
            }
            fight.nextSwing = tick + SWING_TICKS;
            swing(character, monster);
        }
    }

    private void swing(GameCharacter character, Monster monster) {
        int damage = Math.min(monster.hp, roll(PLAYER_ACCURACY, maxHit(character.combatLevel())));
        monster.hp -= damage;
        server.toObservers(monster.id, new Event.Hit(monster.id, damage, monster.position()));
        server.toObservers(monster.id, new Event.EntityUpdated(monster.info()));
        if (damage > 0) {
            Skill combat = server.world.content.combatSkill();
            character.gainExperience(combat, (long) damage * XP_PER_DAMAGE);
        }
        if (monster.hp <= 0) {
            kill(character, monster);
            return;
        }
        if (Objects.equals(monster.target(), character.id)) {
            int hit = Math.min(character.hp, roll(MONSTER_ACCURACY, monster.type.maxHit));
            character.hp -= hit;
            character.infoChanged = true;
            Event event = new Event.Hit(character.id, hit, character.position());
            character.emit(event);
            server.toObservers(character.id, event);
            character.emit(new Event.HpChanged(character.hp, character.maxHp()));
            if (character.hp <= 0) {
                knockOut(character, monster);
            }
        }
    }

    /** 0 for a miss, else 1..maxHit. */
    private int roll(double accuracy, int maxHit) {
        return server.random().nextDouble() < accuracy ? 1 + server.random().nextInt(Math.max(1, maxHit)) : 0;
    }

    private void kill(GameCharacter killer, Monster monster) {
        MonsterType type = monster.type;
        for (int fighterId : List.copyOf(monster.fighters)) {
            GameCharacter fighter = server.online(fighterId);
            if (fighter != null) {
                endFight(fighter, fighter == killer ? null : type.name + " was defeated.");
            }
        }
        server.removeMonster(monster);
        killer.say("You defeated " + type.name + "!");
        if (server.random().nextInt(type.nextDropOneIn()) == 0) {
            dropOneOfAKind(killer, type);
        }
        killer.triggers.add(Discovery.Trigger.KILLED);
    }

    /**
     * The monster's next drop: the only one there will ever be in this world. The drop and the finder owning it
     * are saved together, so it can't end up found-but-lost.
     */
    private void dropOneOfAKind(GameCharacter finder, MonsterType type) {
        ContentBatch batch = WorldGenerator.newDrop(server.world, type.id, finder.name);
        int productId = batch.drops().get(0).productId();
        try {
            server.applyContent(batch, new Storage.Grant(finder.id, productId, 1));
        } catch (RuntimeException e) {
            e.printStackTrace();
            finder.say("Something glinted on the ground, but it's gone.");
            return;
        }
        Product product = server.world.content.product(productId);
        finder.changeInventory(product, 1);
        finder.saveSoon = true;
        finder.say("You found " + product.name + " -- the only one there will ever be!");
        server.tellOthers(finder, finder.name + " found " + product.name + ". There won't be another.");
    }

    private void knockOut(GameCharacter character, Monster monster) {
        endFight(character, null);
        character.stopAction(null);
        character.stopWalking();
        character.hp = character.maxHp();
        character.moveTo(RESPAWN);
        character.emit(new Event.StepsCleared(RESPAWN, character.facing));
        server.toObservers(character.id, new Event.EntityMoved(character.id, RESPAWN, character.facing));
        character.emit(new Event.HpChanged(character.hp, character.maxHp()));
        character.say(monster.type.name + " knocked you out. You wake up at the origin.");
    }

    /** Out of a fight, hp slowly comes back (for monsters too). */
    void regenerate(long tick) {
        if (tick % REGEN_TICKS != 0) {
            return;
        }
        for (GameCharacter character : server.onlineCharacters()) {
            if (character.currentFight == null && character.hp < character.maxHp()) {
                character.hp++;
                character.infoChanged = true;
                character.emit(new Event.HpChanged(character.hp, character.maxHp()));
            }
        }
        for (Monster monster : server.monsters().all()) {
            if (monster.fighters.isEmpty() && monster.hp < monster.type.maxHp) {
                monster.hp++;
                server.toObservers(monster.id, new Event.EntityUpdated(monster.info()));
            }
        }
    }
}
