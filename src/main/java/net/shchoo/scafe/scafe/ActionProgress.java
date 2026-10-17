package net.shchoo.scafe.scafe;

/** An action in progress: fills over {@link Board.Action#durationTicks()} ticks, performs, and repeats while it still can. */
public class ActionProgress {
    private final Board.Action action;
    private final Board.Position startedAt;
    private int ticksDone = 0;

    public ActionProgress(Board.Action action, Player player) {
        this.action = action;
        this.startedAt = player.position();
    }

    /** Returns false once finished or interrupted. */
    public boolean tick(Player player) {
        if (!player.position().equals(startedAt)) {
            GameLog.say("Interrupted.");
            return false;
        }
        ticksDone++;
        if (ticksDone >= action.durationTicks()) {
            action.perform(player);
            if (!action.canPerform(player)) {
                GameLog.say("Can't make any more " + action.progressLabel() + ".");
                return false;
            }
            ticksDone = 0;
        }
        return true;
    }

    public double fraction() {
        return Math.min(1.0, (double) ticksDone / action.durationTicks());
    }

    public String label() {
        return action.progressLabel();
    }
}
