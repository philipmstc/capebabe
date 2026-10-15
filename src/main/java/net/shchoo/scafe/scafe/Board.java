package net.shchoo.scafe.scafe;

import java.util.*;

public class Board {
    public abstract static class Action {
        public abstract void perform();
    }
    public static final Action NOOP = new Action() {
        @Override
        public void perform() {
            System.out.println("Nothing to see here");
        }
    };

    public static final Action SIMPLE = new Action() {
        @Override
        public void perform() {
            System.out.println("You did it");
        }
    };

    public Board(List<Skill> skills) {
        this.skills = skills;
        actions = new ArrayList<>();
        initActions();
    }

    public final List<List<List<Action>>> actions;
    public List<Skill> skills;

    private void initActions() {
        for (int i = 0; i < Main.BOARD_HEIGHT; i++) {
            List<List<Action>> row = new ArrayList<>();
            for (int j = 0; j < Main.BOARD_WIDTH; j++) {
                row.add(new ArrayList<>());
            }
            actions.add(row);
        }
    }

    public List<Action> availableActions(int x, int y) {
        return actions.get(y).get(x);
    }

    public List<Action> availableActions(Player player) {
        return actions.get(player.y+2).get(player.x+2);
    }
}
