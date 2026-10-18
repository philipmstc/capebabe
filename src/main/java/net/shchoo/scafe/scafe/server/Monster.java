package net.shchoo.scafe.scafe.server;

import net.shchoo.scafe.scafe.model.MonsterType;
import net.shchoo.scafe.scafe.model.Position;
import net.shchoo.scafe.scafe.protocol.Event;

import java.util.LinkedHashSet;
import java.util.Set;

/** One monster out in the world (server-side, not saved). Ids are negative so they never clash with players. */
public class Monster implements Walker.Mobile {
    final int id;
    final MonsterType type;
    final Position home;
    int x;
    int y;
    int hp;
    final Walker walker = new Walker(this);
    /** Characters currently fighting it; it hits back at the first one. */
    final Set<Integer> fighters = new LinkedHashSet<>();
    /** Ticks in a row with no player anywhere near. */
    int lonelyTicks;
    /** Ticks until its next step ({@link MonsterType#moveTicks} between steps). */
    int stepCooldown;

    Monster(int id, MonsterType type, Position at) {
        this.id = id;
        this.type = type;
        this.home = at;
        this.x = at.x();
        this.y = at.y();
        this.hp = type.maxHp;
    }

    /** Who it's hitting back, or null. */
    Integer target() {
        return fighters.isEmpty() ? null : fighters.iterator().next();
    }

    @Override
    public Position position() {
        return new Position(x, y);
    }

    @Override
    public void moveTo(Position position) {
        x = position.x();
        y = position.y();
    }

    Event.EntityInfo info() {
        return Event.EntityInfo.monster(id, type.name, position(), type.id, hp, type.maxHp);
    }
}
