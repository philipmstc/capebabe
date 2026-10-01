package net.shchoo.scafe.scafe;

import java.util.*;

public class Board {
    public abstract static class Action {
        public abstract void perform();
    }
    public static final Action NOOP = new Action() {
        @Override
        public void perform() {
            // do nothin
        }
    };

    public static final Action SIMPLE = new Action() {
        @Override
        public void perform() {
            System.out.println("You did it");
        }
    };

    public Board(Collection<Skill> skills) {
        this.skills = skills;
    }

    private static List<List<Action>> actions = new ArrayList<>();
    public Collection<Skill> skills;

    static {
        for (int i = 0; i < 5; i++) {
            List<Action> row = new ArrayList<>();
            for (int j = 0; j < 5; j++) {
                double random = Math.random();
                if (random < 0.50) {
                    row.add(SIMPLE);
                }
                else {
                    row.add(NOOP);
                }
            }
            actions.add(row);
        }
    }


    public List<Action> availableActions(int x, int y) {
        if (actions.get(y).get(x) == SIMPLE)
            return Collections.singletonList(SIMPLE);
        else return new ArrayList<>();
    }

    public List<Action> availableActions(Player player) {
        if (actions.get(player.y+2).get(player.x+2) == SIMPLE)
            return Collections.singletonList(new Action() {
                @Override
                public void perform() {
                    Recipe recipe = skills.stream().filter(s -> s.type == SkillType.PRIMARY).findFirst().get()
                            .productLines.stream().iterator().next()
                            .recipes.get(0);
                    System.out.println("Making: " + recipe.product.name);
                    recipe.create(player);
                    System.out.println("Done!");
                }
            });
        else return new ArrayList<>();
    }
}
